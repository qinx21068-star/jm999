package com.jmreader.data.api.direct

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class CancellableHttpTest {
    @Test fun cancelledRequestDoesNotKeepParentJobAlive() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val job = async { client.executeCancellable(Request.Builder().url(server.url("/")).build()).use { it.body?.string() } }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.junit.Assert.assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun closingResponseCompletesItsOwnershipJob() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        server.enqueue(MockResponse().setBody("image bytes"))
        server.start()
        try {
            withTimeout(2_000) {
                val result = async {
                    client.executeCancellable(Request.Builder().url(server.url("/")).build()).use { response ->
                        assertTrue(response.body!!.string().isNotEmpty())
                    }
                }
                result.await() // Also waits for child resource ownership to finish.
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun cancellationAfterHeadersInterruptsStreamingBodyRead() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        server.enqueue(MockResponse().setBody("abcdef").setBodyDelay(10, TimeUnit.SECONDS))
        server.start()
        val responseReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val job = async(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    client.executeCancellable(Request.Builder().url(server.url("/")).build()).use { response ->
                        responseReady.complete(Unit)
                        response.body!!.string()
                    }
                } catch (_: IOException) {
                    // OkHttp may surface cancellation as an IOException while the body is reading.
                }
            }
            withTimeout(2_000) { responseReady.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
