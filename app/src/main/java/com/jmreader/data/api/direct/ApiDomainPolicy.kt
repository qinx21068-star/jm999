package com.jmreader.data.api.direct

import java.util.Locale

/** API discovery accepts public DNS hostnames only; a URL, IP, port or path is not a hostname. */
internal object ApiDomainPolicy {
    private val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val tld = Regex("[a-z]{2,63}")

    fun normalize(value: String?): String? {
        val host = value?.trim()?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return null
        if (host.length !in 4..253) return null
        val labels = host.split('.')
        if (labels.size < 2 || !tld.matches(labels.last()) || labels.any { !label.matches(it) }) return null
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".invalid") || host.endsWith(".test")) return null
        return host
    }
}
