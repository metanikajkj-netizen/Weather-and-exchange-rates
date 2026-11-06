package com.weathercurrency.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.weathercurrency.app.MainActivity
import com.weathercurrency.app.data.NetworkDiag

/**
 * System notifications for available updates.
 * Channel required on O+; single notification id so updates replace, not stack.
 */
object UpdateNotifier {

    const val CHANNEL_ID = "update_available"
    const val CHANNEL_NAME = "Обновления приложения"
    const val NOTIFICATION_ID = 4217
    const val EXTRA_OPEN_UPDATE = "open_update"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Уведомления о новых версиях WeatherCurrency"
        }
        nm.createNotificationChannel(channel)
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Builds and posts «Доступно обновление WeatherCurrency».
     * PendingIntent opens MainActivity with update screen (user must still
     * confirm download/install — no automatic APK fetch).
     */
    fun notifyUpdateAvailable(context: Context, versionName: String): Boolean {
        return try {
            ensureChannel(context)
            if (!canPostNotifications(context)) {
                NetworkDiag.logInfo("UpdateNotify", "notifications disabled/permission denied")
                return false
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_UPDATE, true)
            }
            val pending = PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Доступно обновление WeatherCurrency")
                .setContentText("Доступна версия $versionName")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("Доступна версия $versionName. Откройте раздел «Обновление приложения»."))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            NetworkDiag.logInfo("UpdateNotify", "posted for version $versionName")
            true
        } catch (e: SecurityException) {
            NetworkDiag.logError("UpdateNotify", e)
            false
        } catch (e: Exception) {
            NetworkDiag.logError("UpdateNotify", e)
            false
        }
    }
}
