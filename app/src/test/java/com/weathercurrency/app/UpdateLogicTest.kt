package com.weathercurrency.app

import com.weathercurrency.app.update.GitHubRelease
import com.weathercurrency.app.update.GitHubReleaseClient
import com.weathercurrency.app.update.ReleaseMetadata
import com.weathercurrency.app.update.UpdateCheckResult
import com.weathercurrency.app.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    private fun meta(
        code: Int = 6,
        name: String = "1.5",
        pkg: String = "com.weathercurrency.app",
        sha: String = "a".repeat(64)
    ) = ReleaseMetadata(pkg, code, name, "WeatherCurrency-v$name.apk", sha)

    private fun release(
        meta: ReleaseMetadata? = meta(),
        url: String? = "https://github.com/x/y/releases/download/v1.5/WeatherCurrency-v1.5.apk",
        draft: Boolean = false,
        prerelease: Boolean = false
    ) = GitHubRelease(
        tagName = "v1.5",
        name = "1.5",
        body = meta?.let {
            "notes\nUPDATE_META {\"packageName\":\"${it.packageName}\",\"versionCode\":${it.versionCode},\"versionName\":\"${it.versionName}\",\"apkFileName\":\"${it.apkFileName}\",\"sha256\":\"${it.sha256}\"}\n"
        } ?: "no meta",
        prerelease = prerelease,
        draft = draft,
        publishedAt = "2026-11-05",
        apkAssetName = meta?.apkFileName,
        apkDownloadUrl = url,
        apkSizeBytes = 12_000_000,
        metadata = meta
    )

    // ---- metadata parsing ----

    @Test
    fun parseMetadataFromBody() {
        val body = """
            Changelog line
            UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":6,"versionName":"1.5","apkFileName":"WeatherCurrency-v1.5.apk","sha256":"${"ab".repeat(32)}"}
            tail
        """.trimIndent()
        val m = UpdateLogic.parseMetadata(body)
        assertNotNull(m)
        assertEquals(6, m!!.versionCode)
        assertEquals("1.5", m.versionName)
        assertEquals("com.weathercurrency.app", m.packageName)
        assertEquals("WeatherCurrency-v1.5.apk", m.apkFileName)
        assertEquals("ab".repeat(32), m.sha256)
    }

    @Test
    fun parseMetadataMissingReturnsNull() {
        assertNull(UpdateLogic.parseMetadata("no metadata here"))
        assertNull(UpdateLogic.parseMetadata(null))
        assertNull(UpdateLogic.parseMetadata(""))
    }

    @Test
    fun parseMetadataBadShaRejected() {
        val body = """UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":6,"versionName":"1.5","apkFileName":"a.apk","sha256":"zz"}"""
        assertNull(UpdateLogic.parseMetadata(body))
    }

    @Test
    fun parseMetadataMissingPackageNameRejected() {
        // Regression: v2.8.23 release body had no packageName field
        val body = """UPDATE_META {"versionCode":43,"versionName":"2.8.23","apkFileName":"a.apk","sha256":"${"ab".repeat(32)}"}"""
        assertNull(UpdateLogic.parseMetadata(body))
    }

    @Test
    fun parseMetadataMissingApkFileNameRejected() {
        // Regression: v2.8.23 used "apkName" instead of "apkFileName"
        val body = """UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":43,"versionName":"2.8.23","apkName":"WeatherCurrency-v2.8.23.apk","sha256":"${"ab".repeat(32)}"}"""
        assertNull(UpdateLogic.parseMetadata(body))
    }

    @Test
    fun parseMetadataExactV2823BrokenFormatRejected() {
        // The exact body published in v2.8.23 before the fix — must be rejected
        val body = """TASK 30.4: Fix empty users list.

UPDATE_META {"versionName":"2.8.23","versionCode":43,"apkName":"WeatherCurrency-v2.8.23.apk","sha256":"4d0c47ec84388183be21deaeb4ba28fc896099eaa11fde57d367db4128355041","minVersionCode":1}"""
        assertNull(UpdateLogic.parseMetadata(body))
    }

    @Test
    fun parseMetadataCorrectV2823FormatAccepted() {
        // The fixed v2.8.23 body — must parse correctly
        val body = """TASK 30.4: Fix empty users list.

UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":43,"versionName":"2.8.23","apkFileName":"WeatherCurrency-v2.8.23.apk","sha256":"4d0c47ec84388183be21deaeb4ba28fc896099eaa11fde57d367db4128355041"}"""
        val m = UpdateLogic.parseMetadata(body)
        assertNotNull(m)
        assertEquals("com.weathercurrency.app", m!!.packageName)
        assertEquals(43, m.versionCode)
        assertEquals("2.8.23", m.versionName)
        assertEquals("WeatherCurrency-v2.8.23.apk", m.apkFileName)
        assertEquals("4d0c47ec84388183be21deaeb4ba28fc896099eaa11fde57d367db4128355041", m.sha256)
    }

    // ---- version compare by versionCode ----

    @Test
    fun equalVersionCodeIsUpToDate() {
        val r = release(meta(code = 6))
        val result = UpdateLogic.decide(6L, "com.weathercurrency.app", r)
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun newerLocalVersionIsUpToDateRejectsOlderRelease() {
        // installed 7, release 6 → reject older
        val r = release(meta(code = 6))
        val result = UpdateLogic.decide(7L, "com.weathercurrency.app", r)
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun higherReleaseCodeIsUpdate() {
        val r = release(meta(code = 6))
        val result = UpdateLogic.decide(5L, "com.weathercurrency.app", r)
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val u = result as UpdateCheckResult.UpdateAvailable
        assertEquals(6, u.metadata.versionCode)
        assertEquals("1.5", u.metadata.versionName)
    }

    @Test
    fun missingMetadataIsErrorNotSilentUpdate() {
        val r = release(meta = null, url = "https://x/apk.apk")
        val result = UpdateLogic.decide(5L, "com.weathercurrency.app", r)
        assertTrue(result is UpdateCheckResult.Error)
    }

    @Test
    fun wrongPackageRejected() {
        val r = release(meta = meta(pkg = "com.other.app"))
        val result = UpdateLogic.decide(5L, "com.weathercurrency.app", r)
        assertTrue(result is UpdateCheckResult.Error)
    }

    @Test
    fun missingApkUrlIsError() {
        val r = release(meta = meta(), url = null)
        val result = UpdateLogic.decide(5L, "com.weathercurrency.app", r)
        assertTrue(result is UpdateCheckResult.Error)
        assertTrue((result as UpdateCheckResult.Error).message.contains("APK"))
    }

    @Test
    fun prereleaseRejected() {
        val r = release(meta = meta(), prerelease = true)
        val result = UpdateLogic.decide(5L, "com.weathercurrency.app", r)
        assertTrue(result is UpdateCheckResult.Error)
    }

    @Test
    fun draftRejected() {
        val r = release(meta = meta(), draft = true)
        val result = UpdateLogic.decide(5L, "com.weathercurrency.app", r)
        assertTrue(result is UpdateCheckResult.Error)
    }

    // ---- asset pick ----

    @Test
    fun pickApkPrefersExactName() {
        val assets = listOf(
            "notes.txt" to "https://x/notes.txt",
            "WeatherCurrency-v1.5.apk" to "https://x/a.apk"
        )
        val picked = UpdateLogic.pickApkAsset("WeatherCurrency-v1.5.apk", assets)
        assertEquals("WeatherCurrency-v1.5.apk", picked!!.first)
    }

    @Test
    fun pickApkFallsBackToAnyApk() {
        val assets = listOf("other.apk" to "https://x/o.apk")
        val picked = UpdateLogic.pickApkAsset("missing.apk", assets)
        assertEquals("other.apk", picked!!.first)
    }

    @Test
    fun pickApkEmptyReturnsNull() {
        assertNull(UpdateLogic.pickApkAsset(null, emptyList()))
    }

    // ---- sha256 compare ----

    @Test
    fun sha256MatchesIgnoresCase() {
        val a = "AB".repeat(32)
        val b = "ab".repeat(32)
        assertTrue(UpdateLogic.sha256Matches(a, b))
    }

    @Test
    fun sha256Mismatch() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        assertFalse(UpdateLogic.sha256Matches(a, b))
        assertFalse(UpdateLogic.sha256Matches("short", "short"))
    }
}
