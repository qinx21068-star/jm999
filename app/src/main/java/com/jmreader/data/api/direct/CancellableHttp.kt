package com.jmreader.data.api.direct

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation owns the call until the response body closes, including streaming body reads. */
internal suspend fun OkHttpClient.executeCancellable(request: Request): Response {
    // This child has no running coroutine: cancelling the parent immediately completes it,
    // so even a blocking ResponseBody read is interrupted by Call.cancel().
    val call = newCall(request)
    val owner = Job(currentCoroutineContext()[Job])
    owner.invokeOnCompletion { if (owner.isCancelled) call.cancel() }
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { owner.cancel() }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    owner.complete()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body
                    val delivered = if (body == null) {
                        owner.complete()
                        response
                    } else {
                        val source = object : ForwardingSource(body.source()) {
                            override fun close() {
                                try { super.close() } finally { owner.complete() }
                            }
                        }.buffer()
                        val wrapped = object : ResponseBody() {
                            override fun contentType(): MediaType? = body.contentType()
                            override fun contentLength(): Long = body.contentLength()
                            override fun source(): BufferedSource = source
                        }
                        response.newBuilder().body(wrapped).build()
                    }
                    continuation.resume(delivered) { _, resource, _ -> resource.close() }
                }
            })
        } catch (e: Throwable) {
            call.cancel()
            owner.complete()
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
}
