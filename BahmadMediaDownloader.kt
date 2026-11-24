package com.bahmad.downloader.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DownloadOutcome(
    val success: Boolean,
    val message: String,
    val savedLocation: String? = null
)

object BahmadMediaDownloader {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun enqueueBackgroundDownload(
        context: Context,
        info: ResolvedLinkInfo,
        selectedFormat: String
    ): UUID {
        DownloaderEngineInitializer.ensureInitialized(context)
        val workId = UUID.randomUUID()
        val inputData = Data.Builder()
            .putString(BahmadDownloadWorker.KEY_URL, info.directDownloadUrl.ifBlank { info.originalUrl })
            .putString(BahmadDownloadWorker.KEY_FILE_NAME, info.fileName)
            .putString(BahmadDownloadWorker.KEY_FORMAT, selectedFormat)
            .build()

        val request = OneTimeWorkRequestBuilder<BahmadDownloadWorker>()
            .setId(workId)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("bahmad_dl_${workId}", ExistingWorkPolicy.REPLACE, request)

        return workId
    }

    fun downloadAndSaveNow(
        context: Context,
        url: String,
        rawFileName: String,
        selectedFormat: String
    ): DownloadOutcome {
        DownloaderEngineInitializer.ensureInitialized(context)

        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return DownloadOutcome(false, "الرابط غير صالح للتحميل المباشر.")
        }

        val isAudioOnly = selectedFormat.contains("MP3", ignoreCase = true) ||
            selectedFormat.contains("صوت", ignoreCase = true)

        val baseTitle = sanitizeBaseName(rawFileName)
        val ext = if (isAudioOnly) "mp3" else resolveExtension(rawFileName, cleanUrl)
        val tempDir = File(context.cacheDir, "bahmad_downloads").apply { mkdirs() }
        val tempFile = File(tempDir, "${baseTitle}_${System.currentTimeMillis()}.$ext")

        return try {
            val req = Request.Builder()
                .url(cleanUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return DownloadOutcome(false, "فشل التنزيل (رمز الاستجابة: ${resp.code}).")
                }
                val body = resp.body ?: return DownloadOutcome(false, "تعذر قراءة بيانات الملف.")
                val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                if (contentType.contains("text/html")) {
                    tempFile.delete()
                    return DownloadOutcome(
                        false,
                        "الخادم أرجع صفحة ويب (HTML) وليس ملف وسائط؛ افتح الرابط في المتصفح الداخلي."
                    )
                }

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return DownloadOutcome(false, "الملف المحمّل فارغ أو غير مكتمل.")
            }

            val savedPath = saveFileToPublicDownloads(
                context = context,
                sourceFile = tempFile,
                fileName = "$baseTitle.$ext",
                mimeType = guessMimeType(ext)
            )
            tempFile.delete()

            if (savedPath != null) {
                DownloadOutcome(true, "تم حفظ الملف في مجلد التنزيلات: $savedPath", savedPath)
            } else {
                DownloadOutcome(false, "تعذر حفظ الملف النهائي في مجلد التنزيلات.")
            }
        } catch (e: Exception) {
            tempFile.delete()
            DownloadOutcome(false, "حدث خطأ أثناء التنزيل: ${e.message ?: "خطأ غير معروف"}")
        }
    }

    private fun saveFileToPublicDownloads(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String
    ): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

                resolver.openOutputStream(uri)?.use { out: OutputStream ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, bufferSize = 16 * 1024)
                    }
                }

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .apply { mkdirs() }
                val dest = File(downloadsDir, fileName)
                FileInputStream(sourceFile).use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                    }
                }
                dest.absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeBaseName(rawName: String): String {
        val withoutExt = rawName.substringBeforeLast(".")
        val cleaned = withoutExt
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(55)
        return cleaned.ifBlank { "bahmad_media_${System.currentTimeMillis()}" }
    }

    private fun resolveExtension(fileName: String, url: String): String {
        val fromName = fileName.substringAfterLast(".", "").lowercase()
        if (fromName in listOf("mp4", "mp3", "m4a", "webm", "mkv", "pdf", "zip", "apk")) {
            return fromName
        }
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains(".mp3") -> "mp3"
            lowerUrl.contains(".m4a") -> "m4a"
            lowerUrl.contains(".webm") -> "webm"
            lowerUrl.contains(".mkv") -> "mkv"
            else -> "mp4"
        }
    }

    private fun guessMimeType(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
