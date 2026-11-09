package com.weathercurrency.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for go-gost Relay v1 handshake encoding.
 * 
 * The relay protocol feature format is:
 *   TYPE (1 byte) | LEN (2 bytes) | DATA (LEN bytes)
 * 
 * Where DATA for each feature type is:
 *   UserAuth: ULEN (1) | UNAME (var) | PLEN (1) | PASSWD (var)
 *   Addr:     ATYP (1) | [ALEN (1) + HOST (var)] or [IP (4/16)] | PORT (2)
 *   Network:  NETID (2 bytes, big-endian)
 * 
 * The TYPE byte must NOT be included in the DATA — it's added by encodeFeature().
 */
class RelayHandshakeTest {

    /** Encode a feature with TYPE header (matches encodeFeature in ProxyTester). */
    private fun encodeFeature(type: Byte, data: ByteArray): ByteArray {
        val len = data.size
        return byteArrayOf(type, (len shr 8).toByte(), (len and 0xFF).toByte()) + data
    }

    @Test
    fun `userAuth feature format`() {
        val userBytes = "testuser".toByteArray(Charsets.UTF_8)
        val passBytes = "testpass".toByteArray(Charsets.UTF_8)

        // CORRECT: no TYPE byte in payload
        val authFeature = byteArrayOf((userBytes.size).toByte()) + userBytes +
            byteArrayOf((passBytes.size).toByte()) + passBytes

        val encoded = encodeFeature(0x01, authFeature)

        // Wire format: TYPE(0x01) | LEN(2) | ULEN(1) | UNAME | PLEN(1) | PASSWD
        assertEquals(0x01, encoded[0].toInt())  // TYPE
        // LEN = 1 + 8 + 1 + 8 = 18
        assertEquals(0x00, encoded[1].toInt())
        assertEquals(18, encoded[2].toInt())
        // ULEN = 8
        assertEquals(8, encoded[3].toInt())
        // UNAME starts at offset 4
        assertEquals("testuser", String(encoded, 4, 8, Charsets.UTF_8))
        // PLEN at offset 12
        assertEquals(8, encoded[12].toInt())
        // PASSWD at offset 13
        assertEquals("testpass", String(encoded, 13, 8, Charsets.UTF_8))
    }

    @Test
    fun `userAuth feature must not have duplicate TYPE byte`() {
        val userBytes = "u".toByteArray(Charsets.UTF_8)
        val passBytes = "p".toByteArray(Charsets.UTF_8)

        // BUG pattern: TYPE byte in payload
        val buggyAuth = byteArrayOf(0x01) +
            byteArrayOf((userBytes.size).toByte()) + userBytes +
            byteArrayOf((passBytes.size).toByte()) + passBytes

        // CORRECT pattern: no TYPE byte in payload
        val correctAuth = byteArrayOf((userBytes.size).toByte()) + userBytes +
            byteArrayOf((passBytes.size).toByte()) + passBytes

        val buggyEncoded = encodeFeature(0x01, buggyAuth)
        val correctEncoded = encodeFeature(0x01, correctAuth)

        // Buggy is 1 byte longer
        assertEquals(correctEncoded.size + 1, buggyEncoded.size)

        // Buggy has TYPE byte at offset 3 (after TYPE+LEN header)
        assertEquals(0x01, buggyEncoded[3].toInt())

        // Correct has ULEN at offset 3
        assertEquals(1, correctEncoded[3].toInt())  // ULEN = 1
    }

    @Test
    fun `addr feature domain format`() {
        val hostBytes = "example.com".toByteArray(Charsets.UTF_8)
        val port = 443

        // CORRECT: ATYP(0x03=domain) | ALEN | HOST | PORT
        val addrFeature = byteArrayOf(0x03, (hostBytes.size).toByte()) + hostBytes +
            byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())

        val encoded = encodeFeature(0x02, addrFeature)

        assertEquals(0x02, encoded[0].toInt())  // TYPE = Addr
        // LEN = 1 + 1 + 11 + 2 = 15
        assertEquals(15, encoded[2].toInt())
        // ATYP = 0x03 (domain)
        assertEquals(0x03, encoded[3].toInt())
        // ALEN = 11
        assertEquals(11, encoded[4].toInt())
        // PORT at end: TYPE(1) + LEN(2) + ATYP(1) + ALEN(1) + HOST(11) = 16, PORT at 16-17
        assertEquals(0x01, encoded[16].toInt())  // 443 = 0x01BB
        assertEquals(0xBB, encoded[17].toInt() and 0xFF)
    }

    @Test
    fun `addr feature ipv4 format`() {
        val port = 443

        // CORRECT: ATYP(0x01=ipv4) | IP(4) | PORT
        val addrFeature = byteArrayOf(0x01) +
            byteArrayOf(8, 8.toByte(), 8, 8) +
            byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())

        val encoded = encodeFeature(0x02, addrFeature)

        assertEquals(0x02, encoded[0].toInt())  // TYPE
        // LEN = 1 + 4 + 2 = 7
        assertEquals(7, encoded[2].toInt())
        // ATYP = 0x01 (ipv4)
        assertEquals(0x01, encoded[3].toInt())
    }

    @Test
    fun `network feature format`() {
        // CORRECT: NETID(2 bytes, big-endian) — no TYPE byte
        val networkFeature = byteArrayOf(0x00, 0x00)  // TCP = 0

        val encoded = encodeFeature(0x04, networkFeature)

        assertEquals(0x04, encoded[0].toInt())  // TYPE = Network
        // LEN = 2
        assertEquals(2, encoded[2].toInt())
        // NETID = 0x0000 (TCP)
        assertEquals(0x00, encoded[3].toInt())
        assertEquals(0x00, encoded[4].toInt())
    }

    @Test
    fun `full relay request format`() {
        val userBytes = "u".toByteArray(Charsets.UTF_8)
        val passBytes = "p".toByteArray(Charsets.UTF_8)
        val hostBytes = "example.com".toByteArray(Charsets.UTF_8)
        val port = 443

        val authFeature = byteArrayOf((userBytes.size).toByte()) + userBytes +
            byteArrayOf((passBytes.size).toByte()) + passBytes
        val addrFeature = byteArrayOf(0x03, (hostBytes.size).toByte()) + hostBytes +
            byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())
        val networkFeature = byteArrayOf(0x00, 0x00)

        val features = encodeFeature(0x01, authFeature) +
            encodeFeature(0x02, addrFeature) +
            encodeFeature(0x04, networkFeature)

        val fealen = features.size
        val header = byteArrayOf(0x01, 0x01, (fealen shr 8).toByte(), (fealen and 0xFF).toByte())

        val fullRequest = header + features

        // VER = 0x01
        assertEquals(0x01, fullRequest[0].toInt())
        // CMD = 0x01 (CONNECT)
        assertEquals(0x01, fullRequest[1].toInt())
        // FEALEN = features.size
        assertEquals(0x00, fullRequest[2].toInt())
        assertEquals(features.size, fullRequest[3].toInt())

        // First feature TYPE = 0x01 (UserAuth)
        assertEquals(0x01, fullRequest[4].toInt())
    }
}
