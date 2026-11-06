package com.weathercurrency.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.weathercurrency.app.data.NetworkDiag
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Local APK integrity checks before handing off to PackageInstaller.
 */
object ApkVerifier {

    data class Info(
        val packageName: String,
        val versionCode: Long,
        val versionName: String?
    )

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Reads package/versionCode from an APK file (no install). */
    fun readApkInfo(context: Context, apkFile: File): Info? {
        return try {
            val pm = context.packageManager
            val flags = PackageManager.GET_ACTIVITIES
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            } ?: return null
            val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            Info(
                packageName = pi.packageName ?: "",
                versionCode = versionCode,
                versionName = pi.versionName
            )
        } catch (e: Exception) {
            NetworkDiag.logError("ApkVerifier.read", e)
            null
        }
    }

    /**
     * Full pre-install gate. Returns null if OK, otherwise a user-facing reason.
     * Does not install anything.
     */
    fun verifyBeforeInstall(
        context: Context,
        apkFile: File,
        expected: ReleaseMetadata,
        installedVersionCode: Long
    ): String? {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() == 0L) {
            return "Файл обновления не найден или пуст"
        }
        if (expected.apkFileName.isNotBlank() &&
            !apkFile.name.equals(expected.apkFileName, ignoreCase = true) &&
            !apkFile.name.endsWith(".apk", ignoreCase = true)
        ) {
            return "Имя файла не соответствует релизу"
        }

        val actualSha = try {
            sha256Hex(apkFile)
        } catch (e: Exception) {
            NetworkDiag.logError("ApkVerifier.sha", e)
            return "Не удалось вычислить SHA-256 файла"
        }
        if (!UpdateLogic.sha256Matches(expected.sha256, actualSha)) {
            NetworkDiag.logError(
                "ApkVerifier",
                IllegalStateException("SHA-256 mismatch")
            )
            return "SHA-256 не совпадает с релизом — файл повреждён или подменён"
        }

        val info = readApkInfo(context, apkFile)
            ?: return "Не удалось прочитать информацию APK"
        if (info.packageName != expected.packageName) {
            return "APK для другого приложения (${info.packageName})"
        }
        if (info.versionCode != expected.versionCode.toLong()) {
            return "versionCode APK (${info.versionCode}) ≠ метаданные релиза (${expected.versionCode})"
        }
        if (info.versionCode <= installedVersionCode) {
            return "Эта версия не новее установленной"
        }

        // Signature of the archive must match currently installed app (same signing cert).
        val installedSig = installedSignatureSha256(context)
        val archiveSig = archiveSignatureSha256(context, apkFile)
        if (installedSig != null && archiveSig != null && installedSig != archiveSig) {
            return "Подпись APK не совпадает с установленной версией"
        }
        if (installedSig != null && archiveSig == null) {
            return "Не удалось проверить подпись APK"
        }
        return null
    }

    private fun installedSignatureSha256(context: Context): String? {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(
                    pkg,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signing = info.signingInfo ?: return null
                val sigs = signing.apkContentsSigners ?: return null
                sigs.firstOrNull()?.toSha256()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toSha256()
            }
        } catch (e: Exception) {
            NetworkDiag.logError("ApkVerifier.installedSig", e)
            null
        }
    }

    private fun archiveSignatureSha256(context: Context, apkFile: File): String? {
        return try {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val pi = if (Build.VERSION.SDK_INT >= 28) {
                // GET_SIGNING_CERTIFICATES works with archive path on modern APIs
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return null

            if (Build.VERSION.SDK_INT >= 28) {
                val signing = pi.signingInfo ?: return null
                val sigs = when {
                    signing.hasMultipleSigners() -> signing.apkContentsSigners
                    else -> signing.signingCertificateHistory
                }
                sigs?.lastOrNull()?.toSha256() ?: sigs?.firstOrNull()?.toSha256()
            } else {
                @Suppress("DEPRECATION")
                pi.signatures?.firstOrNull()?.toSha256()
            }
        } catch (e: Exception) {
            NetworkDiag.logError("ApkVerifier.archiveSig", e)
            null
        }
    }

    private fun android.content.pm.Signature.toSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Launches system install confirmation for a verified APK via FileProvider. */
    fun launchInstall(context: Context, apkFile: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            NetworkDiag.logError("ApkVerifier.install", e)
            false
        }
    }
}
