package com.weathercurrency.app

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for Gson JsonNull handling in admin user detail parsing.
 * Regression: JsonNull (not Kotlin null) caused UnsupportedOperationException("JsonNull").
 */
class JsonNullHandlingTest {

    private val gson = Gson()

    private fun safeStr(el: com.google.gson.JsonElement?): String? =
        if (el == null || el.isJsonNull) null else el.asString
    private fun safeLong(el: com.google.gson.JsonElement?): Long? =
        if (el == null || el.isJsonNull) null else el.asLong
    private fun safeInt(el: com.google.gson.JsonElement?): Int? =
        if (el == null || el.isJsonNull) null else el.asInt

    @Test
    fun jsonNullFieldReturnsNull() {
        val obj = JsonParser.parseString("""{"last_seen":null,"id":"abc"}""").asJsonObject
        assertNull(safeLong(obj.get("last_seen")))
        assertEquals("abc", safeStr(obj.get("id")))
    }

    @Test
    fun jsonNullStringFieldReturnsNull() {
        val obj = JsonParser.parseString("""{"promo_name":null,"id":"x"}""").asJsonObject
        assertNull(safeStr(obj.get("promo_name")))
    }

    @Test
    fun jsonNullIntFieldReturnsNull() {
        val obj = JsonParser.parseString("""{"duration_seconds":null,"id":"x"}""").asJsonObject
        assertNull(safeInt(obj.get("duration_seconds")))
    }

    @Test
    fun missingFieldReturnsNull() {
        val obj = JsonParser.parseString("""{"id":"x"}""").asJsonObject
        assertNull(safeLong(obj.get("last_seen")))
        assertNull(safeStr(obj.get("promo_name")))
    }

    @Test
    fun validFieldsParse() {
        val obj = JsonParser.parseString(
            """{"last_seen":12345,"id":"abc","promo_name":"test","duration_seconds":300}"""
        ).asJsonObject
        assertEquals(12345L, safeLong(obj.get("last_seen")))
        assertEquals("abc", safeStr(obj.get("id")))
        assertEquals("test", safeStr(obj.get("promo_name")))
        assertEquals(300, safeInt(obj.get("duration_seconds")))
    }

    @Test
    fun directJsonNullAccessThrows() {
        // Verify the original bug: calling asLong on JsonNull throws
        val obj = JsonParser.parseString("""{"last_seen":null}""").asJsonObject
        val el = obj.get("last_seen")
        assert(el is JsonNull)
        try {
            el.asLong
            assert(false) { "Should have thrown" }
        } catch (e: UnsupportedOperationException) {
            assertEquals("JsonNull", e.message)
        }
    }

    @Test
    fun fullDeviceJsonWithNullLastSeen() {
        // Simulate the exact device JSON from the API
        val json = """{"id":"dev-1","user_id":"usr-1","status":"active","created_at":1794034535,"last_seen":null}"""
        val o = JsonParser.parseString(json).asJsonObject
        val lastSeen = safeLong(o.get("last_seen"))
        assertNull(lastSeen)
        assertEquals("dev-1", safeStr(o.get("id")))
    }

    @Test
    fun fullRedemptionJsonWithNullPromoName() {
        // Simulate redemption with NULL promo_name (LEFT JOIN miss)
        val json = """{"id":"red-1","promo_id":"p-1","user_id":"u-1","device_id":"d-1","activated_at":123,"promo_name":null,"duration_days":null,"duration_seconds":null}"""
        val o = JsonParser.parseString(json).asJsonObject
        assertNull(safeStr(o.get("promo_name")))
        assertNull(safeInt(o.get("duration_seconds")))
        assertEquals(123L, safeLong(o.get("activated_at")))
    }

    @Test
    fun fullSubscriptionJsonWithNullFields() {
        val json = """{"user_id":"u-1","expires_at":null,"updated_at":null}"""
        val o = JsonParser.parseString(json).asJsonObject
        assertNull(safeLong(o.get("expires_at")))
        assertNull(safeLong(o.get("updated_at")))
    }

    @Test
    fun fullAuditJsonWithNullTarget() {
        val json = """{"id":1,"actor":"a","action":"test","target":null,"ts":123,"ip":"1.2.3.4"}"""
        val o = JsonParser.parseString(json).asJsonObject
        assertNull(safeStr(o.get("target")))
        assertEquals("test", safeStr(o.get("action")))
    }
}
