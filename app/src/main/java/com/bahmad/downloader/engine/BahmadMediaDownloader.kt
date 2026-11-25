package com.bahmad.downloader.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

object BahmadMediaDownloader {
    fun uniqueWorkName(workId: String): String = "bahmad_dl_$workId"

    fun enqueueBackgroundDownload(
        context: Context,
        info: ResolvedLinkInfo,
        format: String,
        existingWorkId: String? = null
    ): String {
        val workId = existingWorkId ?: UUID.randomUUID().toString()
        val inputData = workDataOf(
            BahmadDownloadWorker.KEY_WORK_ID to workId,
            BahmadDownloadWorker.KEY_URL to info.originalUrl,
            BahmadDownloadWorker.KEY_FILE_NAME to info.fileName,
            BahmadDownloadWorker.KEY_FORMAT to format
        )

        val request = OneTimeWorkRequestBuilder<BahmadDownloadWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueWorkName(workId), ExistingWorkPolicy.REPLACE, request)

        return workId
    }

    fun pauseDownload(context: Context, workId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(uniqueWorkName(workId))
    }

    fun resumeDownload(
        context: Context,
        workId: String,
        url: String,
        fileName: String,
        format: String
    ) {
        val inputData = workDataOf(
            BahmadDownloadWorker.KEY_WORK_ID to workId,
            BahmadDownloadWorker.KEY_URL to url,
            BahmadDownloadWorker.KEY_FILE_NAME to fileName,
            BahmadDownloadWorker.KEY_FORMAT to format
        )

        val request = OneTimeWorkRequestBuilder<BahmadDownloadWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueWorkName(workId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAndCleanupDownload(context: Context, workId: String, url: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(uniqueWorkName(workId))
        BahmadDownloadWorker.tempDirFor(context.applicationContext, workId, url).deleteRecursively()
    }
}

private data class SavedMediaResult(
    val locationLabel: String,
    val uriString: String,
    val mimeType: String
)

class BahmadDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_WORK_ID = "key_work_id"
        const val KEY_URL = "key_url"
        const val KEY_FILE_NAME = "key_file_name"
        const val KEY_FORMAT = "key_format"
        const val KEY_PROGRESS = "key_progress"
        const val KEY_STATUS = "key_status"
        const val KEY_RESULT_MESSAGE = "key_result_message"
        const val KEY_RESULT_URI = "key_result_uri"
        const val KEY_RESULT_MIME = "key_result_mime"

        private const val CHANNEL_ID = "bahmad_downloads_channel"
        private const val CHANNEL_NAME = "تنزيلات بحمد"

        fun tempDirFor(context: Context, workId: String, url: String): File {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$workId|$url".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            return File(context.cacheDir, "bahmad_dl_$digest")
        }
    }

    override suspend fun doWork(): Result {
        val workId = inputData.getString(KEY_WORK_ID)?.trim().orEmpty().ifBlank { id.toString() }
        val url = inputData.getString(KEY_URL)?.trim().orEmpty()
        val rawFileName = inputData.getString(KEY_FILE_NAME)?.trim().orEmpty().ifBlank { "bahmad_video.mp4" }
        val format = inputData.getString(KEY_FORMAT).orEmpty()
        val notifId = workId.hashCode().and(0x7FFFFFFF).coerceAtLeast(1001)

        if (url.isEmpty()) {
            return Result.failure(outputMessage("الرابط فارغ، تعذر بدء التحميل."))
        }

        ensureNotificationChannel()
        try {
            setForeground(createForegroundInfo(notifId, rawFileName, "جاري تجهيز المحرك...", 5, indeterminate = true))
        } catch (_: Throwable) {
        }

        setProgress(workDataOf(KEY_PROGRESS to 5, KEY_STATUS to "جاري تجهيز المحرك..."))
        DownloaderEngineInitializer.ensureInitialized(applicationContext)
        DownloaderEngineInitializer.updateYtDlpIfNeeded(applicationContext)
        setProgress(workDataOf(KEY_PROGRESS to 15, KEY_STATUS to "جاري سحب بيانات الفيديو..."))

        val tempDir = tempDirFor(applicationContext, workId, url).apply {
            mkdirs()
        }

        var completedSuccessfully = false
        var lastNotifUpdateMs = 0L

        return try {
            val isAudioOnly = format.contains("MP3", ignoreCase = true) || format.contains("صوت", ignoreCase = true)
            val baseName = rawFileName.substringBeforeLast('.')
            val outputTemplate = File(tempDir, "$baseName.%(ext)s").absolutePath

            val dlRequest = YoutubeDLRequest(url).apply {
                LinkResolver.applyCommonOptions(this, url)
                addOption("-o", outputTemplate)
                addOption("--newline")
                addOption("-c")
                if (isAudioOnly) {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                } else if (format.contains("720p", ignoreCase = true)) {
                    addOption("-f", "bestvideo[height<=720]+bestaudio/best[height<=720]/best")
                    addOption("--merge-output-format", "mp4")
                } else {
                    addOption("-f", "bestvideo+bestaudio/best")
                    addOption("--merge-output-format", "mp4")
                }
            }

            YoutubeDL.getInstance().execute(dlRequest) { progress, _, line ->
                if (isStopped) return@execute
                val clamped = progress.toInt().coerceIn(15, 92)
                val statusText = formatStatusLine(progress, line)
                trySendProgress(clamped, statusText)

                val now = System.currentTimeMillis()
                if (now - lastNotifUpdateMs >= 1200L) {
                    lastNotifUpdateMs = now
                    try {
                        setForegroundAsync(
                            createForegroundInfo(notifId, rawFileName, statusText, clamped, indeterminate = false)
                        )
                    } catch (_: Throwable) {
                    }
                }
            }

            if (isStopped) {
                showCompletionNotification(notifId, rawFileName, "تم إيقاف التحميل مؤقتاً.", success = false)
                return Result.failure(outputMessage("تم إيقاف التحميل مؤقتاً."))
            }

            setProgress(workDataOf(KEY_PROGRESS to 95, KEY_STATUS to "جاري حفظ الملف في التنزيلات..."))

            val downloadedFile = tempDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && it.length() > 1024L }
                ?.maxByOrNull { it.length() }

            if (downloadedFile == null) {
                showCompletionNotification(notifId, rawFileName, "لم يتم حفظ ملف وسائط صالح من الرابط.", success = false)
                return Result.failure(outputMessage("لم يتم حفظ ملف وسائط صالح من الرابط."))
            }

            val finalName = if (isAudioOnly) {
                "$baseName.mp3"
            } else if (downloadedFile.extension.isNotBlank()) {
                "$baseName.${downloadedFile.extension}"
            } else {
                "$baseName.mp4"
            }

            val saved = saveToDownloads(applicationContext, downloadedFile, finalName, isAudioOnly)
            completedSuccessfully = true
            showCompletionNotification(notifId, finalName, "تم الحفظ في ${saved.locationLabel}", success = true)

            Result.success(
                workDataOf(
                    KEY_PROGRESS to 100,
                    KEY_RESULT_MESSAGE to "تم حفظ الملف (${saved.locationLabel})",
                    KEY_RESULT_URI to saved.uriString,
                    KEY_RESULT_MIME to saved.mimeType
                )
            )
        } catch (e: Throwable) {
            if (isStopped) {
                Result.failure(outputMessage("تم إيقاف التحميل مؤقتاً."))
            } else {
                val errMsg = "تعذر تحميل الفيديو: ${e.message ?: "خطأ غير معروف"}"
                showCompletionNotification(notifId, rawFileName, errMsg, success = false)
                Result.failure(outputMessage(errMsg))
            }
        } finally {
            if (completedSuccessfully) {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun formatStatusLine(progress: Float, line: String?): String {
        val cleanLine = line?.trim().orEmpty()
        if (cleanLine.contains("[Merger]", ignoreCase = true)) {
            return "جاري دمج الصوت والصورة..."
        }
        if (cleanLine.contains("[ExtractAudio]", ignoreCase = true)) {
            return "جاري تحويل الصوت..."
        }
        val speedMatch = Regex("""(\d+(?:\.\d+)?[KMG]i?B/s)""").find(cleanLine)?.groupValues?.getOrNull(1)
        val etaMatch = Regex("""ETA\s+(\d{1,2}:\d{2}(?::\d{2})?)""").find(cleanLine)?.groupValues?.getOrNull(1)
        val pct = progress.toInt().coerceIn(0, 100)
        return buildString {
            append("جاري التنزيل ($pct%)")
            if (!speedMatch.isNullOrBlank()) append(" • $speedMatch")
            if (!etaMatch.isNullOrBlank()) append(" • المتبقي $etaMatch")
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعارات تقدم وحالة التنزيلات في الخلفية"
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(
        notifId: Int,
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()
        return ForegroundInfo(notifId, notification)
    }

    private fun showCompletionNotification(
        notifId: Int,
        title: String,
        message: String,
        success: Boolean
    ) {
        try {
            val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(
                    if (success) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.stat_notify_error
                )
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            mgr.notify(notifId + 50000, notification)
        } catch (_: Throwable) {
        }
    }

    private fun trySendProgress(percent: Int, status: String) {
        try {
            setProgressAsync(workDataOf(KEY_PROGRESS to percent, KEY_STATUS to status))
        } catch (_: Throwable) {
        }
    }

    private fun outputMessage(msg: String): Data {
        return workDataOf(KEY_RESULT_MESSAGE to msg)
    }

    private fun saveToDownloads(
        context: Context,
        sourceFile: File,
        displayName: String,
        isAudio: Boolean
    ): SavedMediaResult {
        val mimeType = if (isAudio) "audio/mpeg" else "video/mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BahmadDownloader")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("تعذر إنشاء ملف في مجلد التنزيلات")

            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("تعذر فتح مجرى الكتابة للملف")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedMediaResult(
                locationLabel = "Downloads/BahmadDownloader/$displayName",
                uriString = uri.toString(),
                mimeType = mimeType
            )
        } else {
            val targetDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "BahmadDownloader"
            ).apply { mkdirs() }
            val destFile = File(targetDir, displayName)
            sourceFile.copyTo(destFile, overwrite = true)
            return SavedMediaResult(
                locationLabel = destFile.absolutePath,
                uriString = Uri.fromFile(destFile).toString(),
                mimeType = mimeType
            )
        }
    }
}
