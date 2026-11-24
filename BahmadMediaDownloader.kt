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
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadOutcome(
    val success: Boolean,
    val message: String,
    val savedLocation: String? = null
)

object DownloaderEngineInitializer {

    private val initialized = AtomicBoolean(false)

    @Volatile
    var lastStatusMessage: String = "محرك التحميل جاهز للتهيئة"
        private set

    fun ensureInitialized(context: Context): Boolean {
        if (initialized.get()) return true
        synchronized(this) {
            if (initialized.get()) return true
            return try {
                val appContext = context.applicationContext
                val workDir = File(appContext.cacheDir, "bahmad_engine")
                val stagingDir = File(appContext.filesDir, "bahmad_staging")
                workDir.mkdirs()
                stagingDir.mkdirs()

                val ready = workDir.exists() && workDir.canWrite() &&
                    stagingDir.exists() && stagingDir.canWrite()

                lastStatusMessage = if (ready) {
                    "تمت تهيئة مسارات ومحرك التحميل بنجاح"
                } else {
                    "تعذر الوصول إلى مسارات العمل الداخلية"
                }
                initialized.set(ready)
                ready
            } catch (e: Exception) {
                lastStatusMessage = "فشل تهيئة المحرك: ${e.message ?: "خطأ غير معروف"}"
                false
            }
        }
    }
}

object BahmadMediaDownloader {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    fun enqueueBackgroundDownload(
        context: Context,
        info: ResolvedLinkInfo,
        selectedFormat: String
    ): UUID {
        DownloaderEngineInitializer.ensureInitialized(context)
        val workId = UUID.randomUUID()
        val targetUrl = info.directDownloadUrl.ifBlank { info.originalUrl }
        val refererUrl = info.originalUrl.takeIf { it.startsWith("http") && it != targetUrl }.orEmpty()

        val inputData = Data.Builder()
            .putString(BahmadDownloadWorker.KEY_URL, targetUrl)
            .putString(BahmadDownloadWorker.KEY_REFERER, refererUrl)
            .putString(BahmadDownloadWorker.KEY_FILE_NAME, info.fileName)
            .putString(BahmadDownloadWorker.KEY_FORMAT, selectedFormat)
            .build()

        val request = OneTimeWorkRequestBuilder<BahmadDownloadWorker>()
            .setId(workId)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("bahmad_dl_$workId", ExistingWorkPolicy.REPLACE, request)

        return workId
    }

    fun downloadAndSaveNow(
        context: Context,
        url: String,
        rawFileName: String,
        selectedFormat: String,
        refererUrl: String = ""
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
            val reqBuilder = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")

            if (refererUrl.startsWith("http://") || refererUrl.startsWith("https://")) {
                reqBuilder.header("Referer", refererUrl)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return DownloadOutcome(false, "فشل التنزيل (رمز الاستجابة: ${resp.code}).")
                }
                val body = resp.body ?: return DownloadOutcome(false, "تعذر قراءة بيانات الملف.")
                val contentType = resp.header("Content-Type")?.lowercase().orEmpty()

                val isPlaylist = cleanUrl.contains(".m3u8", ignoreCase = true) ||
                    contentType.contains("mpegurl")

                if (isPlaylist) {
                    val playlistBody = body.string()
                    val downloadedSegments = downloadHlsSegments(
                        playlistUrl = cleanUrl,
                        playlistContent = playlistBody,
                        outputFile = tempFile,
                        refererUrl = refererUrl,
                        depth = 0
                    )
                    if (!downloadedSegments) {
                        tempFile.delete()
                        return DownloadOutcome(
                            false,
                            "تعذر تحميل مقاطع البث المباشر (قد يكون البث محمياً أو يتطلب محرك yt-dlp)."
                        )
                    }
                } else {
                    if (contentType.contains("text/html")) {
                        tempFile.delete()
                        return DownloadOutcome(
                            false,
                            "الخادم أرجع صفحة ويب (HTML) وليس ملف وسائط؛ افتح الرابط في المتصفح الداخلي."
                        )
                    }

                    body.byteStream().use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 32 * 1024)
                        }
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

    private fun downloadHlsSegments(
        playlistUrl: String,
        playlistContent: String,
        outputFile: File,
        refererUrl: String,
        depth: Int
    ): Boolean {
        if (depth > 3 || playlistContent.isBlank()) return false

        val lines = playlistContent.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val hasNestedVariant = lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }

        if (hasNestedVariant) {
            val bestChild = LinkResolver.resolve(playlistUrl).directDownloadUrl
            if (bestChild.isNotBlank() && bestChild != playlistUrl) {
                val childReqBuilder = Request.Builder()
                    .url(bestChild)
                    .header("User-Agent", USER_AGENT)
                if (refererUrl.isNotBlank()) childReqBuilder.header("Referer", refererUrl)

                httpClient.newCall(childReqBuilder.build()).execute().use { childResp ->
                    if (!childResp.isSuccessful) return false
                    val childText = childResp.body?.string().orEmpty()
                    return downloadHlsSegments(bestChild, childText, outputFile, refererUrl, depth + 1)
                }
            }
        }

        val segmentUrls = lines
            .filter { !it.startsWith("#") }
            .map { resolveUri(playlistUrl, it) }

        if (segmentUrls.isEmpty()) return false

        var writtenBytes = 0L
        outputFile.outputStream().use { out ->
            for (segUrl in segmentUrls) {
                val segReqBuilder = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", USER_AGENT)
                if (refererUrl.isNotBlank()) segReqBuilder.header("Referer", refererUrl)

                try {
                    httpClient.newCall(segReqBuilder.build()).execute().use { segResp ->
                        if (segResp.isSuccessful) {
                            val segBody = segResp.body
                            if (segBody != null) {
                                val bytes = segBody.byteStream().use { input ->
                                    input.copyTo(out, bufferSize = 32 * 1024)
                                }
                                writtenBytes += bytes
                            }
                        }
                    }
                } catch (_: Exception) {
                    // تجاهل مقطع مفرد متعثر إذا أمكن إكمال الباقي
                }
            }
        }
        return writtenBytes > 0L && outputFile.exists() && outputFile.length() > 0L
    }

    private fun resolveUri(baseUrl: String, candidate: String): String {
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            return candidate
        }
        return try {
            java.net.URI(baseUrl).resolve(candidate).toString()
        } catch (_: Exception) {
            candidate
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
                        input.copyTo(out, bufferSize = 32 * 1024)
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
                        input.copyTo(output, bufferSize = 32 * 1024)
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

class BahmadDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_REFERER = "referer"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_FORMAT = "format"
        const val KEY_RESULT_MESSAGE = "result_message"
    }

    override fun doWork(): Result {
        val url = inputData.getString(KEY_URL).orEmpty()
        val referer = inputData.getString(KEY_REFERER).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME).orEmpty().ifBlank {
            "bahmad_media_${System.currentTimeMillis()}.mp4"
        }
        val format = inputData.getString(KEY_FORMAT).orEmpty().ifBlank { "MP4 (أفضل جودة)" }

        val outcome = BahmadMediaDownloader.downloadAndSaveNow(
            context = applicationContext,
            url = url,
            rawFileName = fileName,
            selectedFormat = format,
            refererUrl = referer
        )

        val output = Data.Builder()
            .putString(KEY_RESULT_MESSAGE, outcome.message)
            .build()

        return if (outcome.success) {
            Result.success(output)
        } else {
            Result.failure(output)
        }
    }
}
