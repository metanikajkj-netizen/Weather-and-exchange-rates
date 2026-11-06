package com.weathercurrency.app

import com.weathercurrency.app.proxy.ProxyConfig
import com.weathercurrency.app.proxy.ProxyEmbedded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Embedded proxy configuration (TASK25): must be complete and valid
 * without any user input. Never asserts or prints the password value.
 */
class EmbeddedProxyTest {

    @Test
    fun embeddedConfigIsComplete() {
        val c = ProxyEmbedded.config
        assertTrue(c.isConfigured)
        assertEquals(ProxyConfig.Type.HTTP, c.type)
        assertEquals(47194, c.port)
        assertTrue(c.host.isNotBlank())
        assertTrue(c.username.isNotBlank())
        assertTrue(c.password.isNotEmpty())
    }

    @Test
    fun embeddedConfigPassesValidation() {
        val c = ProxyEmbedded.config
        val err = ProxyConfig.validate(
            c.type.name, c.host, c.port.toString(), c.username, c.password
        )
        assertEquals(null, err)
    }

    @Test
    fun embeddedProxyUrlShapeHasNoUserEcho() {
        val url = ProxyEmbedded.config.toProxyUrl()
        // http://user:pass@host:port — shape only; do not assert secret equality.
        assertTrue(url.startsWith("http://"))
        assertTrue(url.contains("47194"))
        assertTrue(url.contains("@"))
        assertTrue(url.contains("141.11.162.93"))
    }
}
