package com.weathercurrency.app.update

/**
 * Parsed GitHub Release subset used for OTA updates.
 * Machine-readable metadata lives in the release body (see [ReleaseMetadata]).
 */
data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val publishedAt: String?,
    val apkAssetName: String?,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long?,
    val metadata: ReleaseMetadata?
)

/**
 * Embed in release body so the client can compare by versionCode.
 * Example block:
 * ```
 * UPDATE_META {"packageName":"com.weathercurrency.app","versionCode":6,"versionName":"1.5","apkFileName":"WeatherCurrency-v1.5.apk","sha256":"…"}
 * ```
 */
data class ReleaseMetadata(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val apkFileName: String,
    val sha256: String
)

/** Outcome of comparing installed version with latest release. */
sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val release: GitHubRelease,
        val metadata: ReleaseMetadata
    ) : UpdateCheckResult()

    /** Already on the latest (or newer local) versionCode. */
    object UpToDate : UpdateCheckResult()

    data class Error(val message: String) : UpdateCheckResult()
}
