package com.bahmad.downloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bahmad.downloader.engine.BahmadMediaDownloader
import com.bahmad.downloader.engine.DownloaderEngineInitializer
import com.bahmad.downloader.engine.ResolvedLinkInfo

class DownloadForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("جاري تجهيز خدمة التحميل..."))

        val url = intent?.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME)?.trim().orEmpty().ifBlank { "bahmad_video.mp4" }
        val format = intent?.getStringExtra(EXTRA_FORMAT)?.trim().orEmpty().ifBlank { "MP4 (أفضل جودة)" }

        if (url.isNotEmpty()) {
            DownloaderEngineInitializer.ensureInitialized(this)
            val info = ResolvedLinkInfo(
                originalUrl = url,
                resolvedUrl = url,
                fileName = fileName,
                sourceLabel = "ForegroundService",
                suggestedFormats = listOf(format),
                isHtmlPage = false,
                note = null
            )
            BahmadMediaDownloader.enqueueBackgroundDownload(this, info, format)
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تحميل الوسائط",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager: NotificationManager? =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("بحمد للتحميل")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "bahmad_download_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_FORMAT = "extra_format"
    }
}
