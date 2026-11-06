package com.weathercurrency.app.update

/**
 * Pure helpers: metadata parsing and versionCode comparison.
 * Unit-testable without Android or network.
 */
object UpdateLogic {

    const val META_PREFIX = "UPDATE_META"
    const val EXPECTED_PACKAGE = "com.weathercurrency.app"

    /**
     * Extracts the first UPDATE_META JSON object from a release body.
     * Tolerates fenced code blocks and surrounding prose.
     */
    fun parseMetadata(body: String?): ReleaseMetadata? {
        if (body.isNullOrBlank()) return null
        val idx = body.indexOf(META_PREFIX)
        if (idx < 0) return null
        val start = body.indexOf('{', idx)
        if (start < 0) return null
        // Brace matching (metadata JSON has no nested objects of concern, but be safe)
        var depth = 0
        var end = -1
        for (i in start until body.length) {
            val ch = body[i]
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) {
                    end = i
                    break
                }
            }
        }
        if (end < 0) return null
        val json = body.substring(start, end + 1)
        return try {
            val gson = com.google.gson.Gson()
            val obj = gson.fromJson(json, com.google.gson.JsonObject::class.java)
            val packageName = obj.get("packageName")?.asString ?: return null
            val versionCode = try {
                obj.get("versionCode")?.asInt ?: return null
            } catch (_: Exception) {
                return null
            }
            val versionName = obj.get("versionName")?.asString ?: return null
            val apkFileName = obj.get("apkFileName")?.asString ?: return null
            val sha256 = obj.get("sha256")?.asString?.trim()?.lowercase() ?: return null
            if (sha256.length != 64 || !sha256.all { it in "0123456789abcdef" }) return null
            if (versionCode <= 0) return null
            ReleaseMetadata(
                packageName = packageName,
                versionCode = versionCode,
                versionName = versionName,
                apkFileName = apkFileName,
                sha256 = sha256
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compares by versionCode (not versionName string).
     * - metadata missing / wrong package → not an update
     * - release.versionCode > installed → update
     * - equal or older → up to date (rejects older APKs)
     */
    fun decide(
        installedVersionCode: Long,
        installedPackage: String,
        release: GitHubRelease
    ): UpdateCheckResult {
        if (release.draft || release.prerelease) {
            return UpdateCheckResult.Error("Найден только предварительный релиз — стабильный не опубликован")
        }
        val meta = release.metadata
            ?: return UpdateCheckResult.Error(
                "В релизе нет машиночитаемых метаданных UPDATE_META (versionCode)"
            )
        if (meta.packageName != installedPackage || meta.packageName != EXPECTED_PACKAGE) {
            return UpdateCheckResult.Error("Метаданные релиза для другого приложения")
        }
        if (release.apkDownloadUrl.isNullOrBlank()) {
            return UpdateCheckResult.Error("В релизе нет APK-файла")
        }
        return when {
            meta.versionCode.toLong() > installedVersionCode ->
                UpdateCheckResult.UpdateAvailable(release, meta)
            else -> UpdateCheckResult.UpToDate
        }
    }

    /** Picks the APK asset by exact name, else any .apk asset. */
    fun pickApkAsset(
        preferredName: String?,
        assets: List<Pair<String, String>> // name to browser_download_url
    ): Pair<String, String>? {
        if (assets.isEmpty()) return null
        if (preferredName != null) {
            assets.firstOrNull { it.first == preferredName }?.let { return it }
        }
        return assets.firstOrNull { it.first.endsWith(".apk", ignoreCase = true) }
    }

    /** Constant-time-ish SHA-256 hex compare (both already lowercase hex). */
    fun sha256Matches(expected: String, actual: String): Boolean {
        val e = expected.trim().lowercase()
        val a = actual.trim().lowercase()
        if (e.length != 64 || a.length != 64) return false
        var diff = 0
        for (i in 0 until 64) {
            diff = diff or (e[i].code xor a[i].code)
        }
        return diff == 0
    }
}
