package com.bahmad.downloader.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileInputStream
import java.util.UUID

object BahmadMediaDownloader {
    fun enqueueBackgroundDownload(
        context: Context,
        info: ResolvedLinkInfo,
        format: String
    ): String {
        val workId = UUID.randomUUID().toString()
        val inputData = workDataOf(
            BahmadDownloadWorker.KEY_URL to info.originalUrl,
            BahmadDownloadWorker.KEY_FILE_NAME to info.fileName,
            BahmadDownloadWorker.KEY_FORMAT to format
        )

        val request = OneTimeWorkRequestBuilder<BahmadDownloadWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("bahmad_dl_$workId", ExistingWorkPolicy.REPLACE, request)

        return workId
    }
}

class BahmadDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_URL = "key_url"
        const val KEY_FILE_NAME = "key_file_name"
        const val KEY_FORMAT = "key_format"
        const val KEY_RESULT_MESSAGE = "key_result_message"
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)?.trim().orEmpty()
        val rawFileName = inputData.getString(KEY_FILE_NAME)?.trim().orEmpty().ifBlank { "bahmad_video.mp4" }
        val format = inputData.getString(KEY_FORMAT).orEmpty()

        if (url.isEmpty()) {
            return Result.failure(outputMessage("الرابط فارغ، تعذر بدء التحميل."))
        }

        DownloaderEngineInitializer.ensureInitialized(applicationContext)

        val tempDir = File(applicationContext.cacheDir, "bahmad_dl_${UUID.randomUUID()}").apply {
            mkdirs()
        }

        return try {
            val isAudioOnly = format.contains("MP3", ignoreCase = true) || format.contains("صوت", ignoreCase = true)
            val baseName = rawFileName.substringBeforeLast('.')
            val outputTemplate = File(tempDir, "$baseName.%(ext)s").absolutePath

            val dlRequest = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--user-agent", LinkResolver.BROWSER_USER_AGENT)
                addOption("-o", outputTemplate)
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

            YoutubeDL.getInstance().execute(dlRequest, null)

            val downloadedFile = tempDir.listFiles()
                ?.filter { it.isFile && it.length() > 1024L }
                ?.maxByOrNull { it.length() }

            if (downloadedFile == null) {
                return Result.failure(outputMessage("لم يتم حفظ ملف وسائط صالح من الرابط."))
            }

            val finalName = if (isAudioOnly) {
                "$baseName.mp3"
            } else if (downloadedFile.extension.isNotBlank()) {
                "$baseName.${downloadedFile.extension}"
            } else {
                "$baseName.mp4"
            }

            val savedLocation = saveToDownloads(applicationContext, downloadedFile, finalName, isAudioOnly)
            Result.success(outputMessage("تم حفظ الملف ($savedLocation)"))
        } catch (e: Throwable) {
            Result.failure(outputMessage("تعذر تحميل الفيديو: ${e.message ?: "خطأ غير معروف"}"))
        } finally {
            tempDir.deleteRecursively()
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
    ): String {
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
            return "Downloads/BahmadDownloader/$displayName"
        } else {
            val targetDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "BahmadDownloader"
            ).apply { mkdirs() }
            val destFile = File(targetDir, displayName)
            sourceFile.copyTo(destFile, overwrite = true)
            return destFile.absolutePath
        }
    }
}
