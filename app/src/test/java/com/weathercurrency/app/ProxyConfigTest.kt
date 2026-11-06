package com.weathercurrency.app

import com.weathercurrency.app.proxy.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyConfigTest {

    @Test
    fun validHttpConfigPasses() {
        assertNull(
            ProxyConfig.validate("HTTP", "192.168.1.10", "8080", "", "")
        )
    }

    @Test
    fun validHttpsWithAuthPasses() {
        assertNull(
            ProxyConfig.validate("HTTPS", "proxy.example.com", "443", "user", "pass")
        )
    }

    @Test
    fun missingHostFails() {
        val err = ProxyConfig.validate("HTTP", "", "8080", "", "")
        assertNotNull(err)
        assertTrue(err!!.contains("Укажите"))
    }

    @Test
    fun invalidPortFails() {
        assertNotNull(ProxyConfig.validate("HTTP", "h.example", "0", "", ""))
        assertNotNull(ProxyConfig.validate("HTTP", "h.example", "65536", "", ""))
        assertNotNull(ProxyConfig.validate("HTTP", "h.example", "abc", "", ""))
        assertNotNull(ProxyConfig.validate("HTTP", "h.example", "", "", ""))
    }

    @Test
    fun invalidTypeFails() {
        assertNotNull(ProxyConfig.validate("SOCKS", "h", "8080", "", ""))
    }

    @Test
    fun passwordWithoutLoginFails() {
        val err = ProxyConfig.validate("HTTP", "h.example", "8080", "", "secret")
        assertNotNull(err)
        assertTrue(err!!.contains("логин"))
    }

    @Test
    fun loginWithoutPasswordFails() {
        val err = ProxyConfig.validate("HTTP", "h.example", "8080", "user", "")
        assertNotNull(err)
        assertTrue(err!!.contains("пароль"))
    }

    @Test
    fun whitespaceHostFails() {
        assertNotNull(ProxyConfig.validate("HTTP", "bad host", "8080", "", ""))
    }

    @Test
    fun proxyUrlHttpNoAuth() {
        val c = ProxyConfig(ProxyConfig.Type.HTTP, "10.0.0.1", 8080)
        assertEquals("http://10.0.0.1:8080", c.toProxyUrl())
    }

    @Test
    fun proxyUrlHttpsWithAuth() {
        val c = ProxyConfig(ProxyConfig.Type.HTTPS, "p.example.com", 443, "alice", "s3cret")
        val url = c.toProxyUrl()
        assertTrue(url.startsWith("https://"))
        assertTrue(url.contains("p.example.com:443"))
        assertTrue(url.contains("alice"))
        assertTrue(url.contains("s3cret"))
    }

    @Test
    fun isConfiguredRequiresHostAndPort() {
        org.junit.Assert.assertFalse(ProxyConfig(ProxyConfig.Type.HTTP, "", 8080).isConfigured)
        assertTrue(ProxyConfig(ProxyConfig.Type.HTTP, "h", 1).isConfigured)
        assertTrue(ProxyConfig(ProxyConfig.Type.HTTP, "h", 65535).isConfigured)
        org.junit.Assert.assertFalse(ProxyConfig(ProxyConfig.Type.HTTP, "h", 0).isConfigured)
    }

    @Test
    fun parseTypeDefaultsToHttp() {
        assertEquals(ProxyConfig.Type.HTTP, ProxyConfig.parseType(null))
        assertEquals(ProxyConfig.Type.HTTP, ProxyConfig.parseType("http"))
        assertEquals(ProxyConfig.Type.HTTPS, ProxyConfig.parseType("HTTPS"))
        assertEquals(ProxyConfig.Type.HTTPS, ProxyConfig.parseType("https"))
    }

}
