package com.jmreader.data.api.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiDomainPolicyTest {
    @Test fun normalizesBarePublicHostnames() {
        assertEquals("www.cdngwc.cc", ApiDomainPolicy.normalize(" WWW.CDNGWC.CC. "))
        assertEquals("api-2.example.com", ApiDomainPolicy.normalize("api-2.example.com"))
    }

    @Test fun rejectsUrlsIpsLocalNamesAndMalformedLabels() {
        listOf(
            "https://www.cdngwc.cc", "www.cdngwc.cc/path", "www.cdngwc.cc:443",
            "user@www.cdngwc.cc", "127.0.0.1", "[::1]", "localhost", "api.local",
            "api.internal", "api.test", "api.invalid", "a..example.com", "-api.example.com",
            "api-.example.com", "api.example.com?x=1", "api.example.com\n.other.com",
        ).forEach { assertNull(it, ApiDomainPolicy.normalize(it)) }
        assertNull(ApiDomainPolicy.normalize(null))
        assertNull(ApiDomainPolicy.normalize("a".repeat(64) + ".com"))
    }
}
