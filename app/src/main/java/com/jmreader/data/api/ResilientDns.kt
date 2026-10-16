package com.jmreader.data.api

import com.jmreader.core.Logger
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** DNS resolver for networks that hijack blocked domains to loopback. */
object ResilientDns : Dns {
    private val bootstrapAddresses = mapOf(
        "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
        "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
    )
    private val bootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            bootstrapAddresses[hostname.lowercase()]?.map { ip -> InetAddress.getByName(ip) }
                ?: Dns.SYSTEM.lookup(hostname)
    }
    private val dohClient by lazy {
        OkHttpClient.Builder()
            .dns(bootstrapDns)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val systemLookup = runCatching { Dns.SYSTEM.lookup(hostname) }
        val system = systemLookup.getOrElse { emptyList() }
        // Preserve explicit local endpoints (emulators, LAN backends, mDNS names and
        // user-configured loopback proxies). Public DoH must never rewrite these.
        if (isLocalEndpoint(hostname)) {
            return system.ifEmpty { throw IOException("无法解析本机地址：$hostname") }
        }
        val usableSystem = system.filterNot(::isBogusAddress)
        if (usableSystem.isNotEmpty()) return usableSystem

        val endpointTypes = listOf(
            Triple("dns.google", "https://dns.google/resolve", 1),
            Triple("dns.google", "https://dns.google/resolve", 28),
            Triple("cloudflare-dns.com", "https://cloudflare-dns.com/dns-query", 1),
            Triple("cloudflare-dns.com", "https://cloudflare-dns.com/dns-query", 28),
        )
        for ((_, baseUrl, recordType) in endpointTypes) {
            val queryType = if (recordType == 1) "A" else "AAAA"
            val endpoint = "$baseUrl?name=${hostname.urlEncoded()}&type=$queryType"
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/dns-json")
                    .get()
                    .build()
                dohClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val answers = JSONObject(body).optJSONArray("Answer") ?: return@use
                    val addresses = buildList {
                        for (index in 0 until answers.length()) {
                            val answer = answers.optJSONObject(index) ?: continue
                            if (answer.optInt("type") != recordType) continue
                            val value = answer.optString("data")
                            if ((recordType == 1 && value.matches(Regex("^[0-9.]+$"))) ||
                                (recordType == 28 && value.matches(Regex("^[0-9a-fA-F:]+$")))) {
                                runCatching { InetAddress.getByName(value) }.getOrNull()?.let(::add)
                            }
                        }
                    }.filterNot(::isBogusAddress)
                    if (addresses.isNotEmpty()) {
                        Logger.i("Net", "DNS-over-HTTPS resolved $hostname to ${addresses.joinToString()}")
                        return addresses.distinct()
                    }
                }
            } catch (error: Throwable) {
                Logger.w("Net", "DoH lookup failed for $hostname: ${Logger.brief(error)}")
            }
        }
        throw IOException("DNS returned loopback/local addresses and public DNS-over-HTTPS could not resolve $hostname")
    }

    private fun isLocalEndpoint(hostname: String): Boolean {
        val host = hostname.trim().lowercase().trim('[', ']')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        // Only preserve explicit IP literals. Never infer "local" from system DNS here:
        // a hijacked public hostname may itself resolve to 127.0.0.1 and must reach DoH fallback.
        val isIpv4 = host.matches(Regex("^[0-9.]+$"))
        val isIpv6 = host.contains(':') && host.matches(Regex("^[0-9a-f:.]+$"))
        if (!isIpv4 && !isIpv6) return false
        return runCatching {
            val literal = InetAddress.getByName(host)
            literal.isLoopbackAddress || literal.isAnyLocalAddress || literal.isLinkLocalAddress
        }.getOrDefault(false)
    }

    private fun isBogusAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
