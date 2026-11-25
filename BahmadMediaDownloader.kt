package com.bahmad.downloader.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadOutcome(
    val success: Boolean,
    val message: String,
    val savedLocation: String? = null
)

object DownloaderEngineInitializer {

    private val initialized = AtomicBoolean(false)
    private val updatedOnce = AtomicBoolean(false)

    @Volatile
    var lastStatusMessage: String = "محرك التحميل جاهز للتهيئة"
        private set

    fun ensureInitialized(context: Context): Boolean {
        if (initialized.get()) return true
        synchronized(this) {
            if (initialized.get()) return true
            return try {
                val appContext = context.applicationContext
                val workDir = File(appContext.cacheDir, "bahmad_engine").apply { mkdirs() }
                val stagingDir = File(appContext.filesDir, "bahmad_staging").apply { mkdirs() }

                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)

                val ready = workDir.exists() && stagingDir.exists()
                lastStatusMessage = if (ready) {
                    "تمت تهيئة محرك yt-dlp و FFmpeg بنجاح"
                } else {
                    "تعذر تهيئة مجلدات العمل"
                }
                initialized.set(ready)
                ready
            } catch (e: Exception) {
                lastStatusMessage = "فشل تهيئة المحرك: ${e.message ?: "خطأ غير معروف"}"
                false
            }
        }
    }

    fun ensureEngineUpdatedOnce(context: Context) {
        if (!updatedOnce.compareAndSet(false, true)) return
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.STABLE
            )
        }
    }
}

object BahmadMediaDownloader {

    private const val MIN_REAL_MEDIA_BYTES = 262_144L // 256 KB

    fun enqueueBackgroundDownload(
        context: Context,
        info: ResolvedLinkInfo,
        selectedFormat: String
    ): UUID {
        DownloaderEngineInitializer.ensureInitialized(context)
        val workId = UUID.randomUUID()
        val targetUrl = info.originalUrl.ifBlank { info.directDownloadUrl }
        val cookieHeader = runCatching {
            CookieManager.getInstance().getCookie(targetUrl).orEmpty()
        }.getOrDefault("")

        val inputData = Data.Builder()
            .putString(BahmadDownloadWorker.KEY_URL, targetUrl)
            .putString(BahmadDownloadWorker.KEY_REFERER, targetUrl)
            .putString(BahmadDownloadWorker.KEY_COOKIE, cookieHeader)
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
        refererUrl: String = "",
        cookieHeader: String = ""
    ): DownloadOutcome {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return DownloadOutcome(false, "الرابط غير صالح للتحميل.")
        }

        if (LinkResolver.isLikelyAdOrSegmentUrl(cleanUrl)) {
            return DownloadOutcome(
                false,
                "تم رفض الرابط لأنه إعلان أو مقطع بث مجزأ وليس رابط الفيديو الأساسي."
            )
        }

        if (!DownloaderEngineInitializer.ensureInitialized(context)) {
            return DownloadOutcome(false, DownloaderEngineInitializer.lastStatusMessage)
        }

        DownloaderEngineInitializer.ensureEngineUpdatedOnce(context)

        val isAudioOnly = selectedFormat.contains("MP3", ignoreCase = true) ||
            selectedFormat.contains("صوت", ignoreCase = true)
        val is720p = selectedFormat.contains("720", ignoreCase = true)

        val effectiveReferer = refererUrl.ifBlank { cleanUrl }
        val effectiveCookie = cookieHeader.ifBlank {
            runCatching { CookieManager.getInstance().getCookie(cleanUrl).orEmpty() }.getOrDefault("")
        }

        val baseTitle = sanitizeBaseName(rawFileName)
        val stagingDir = File(context.cacheDir, "bahmad_ytdl_out_${UUID.randomUUID()}").apply { mkdirs() }
        val logBuilder = StringBuilder()

        return try {
            val request = YoutubeDLRequest(cleanUrl).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--geo-bypass")
                addOption("--user-agent", LinkResolver.BROWSER_USER_AGENT)
                addOption("--referer", effectiveReferer)
                if (effectiveCookie.isNotBlank()) {
                    addOption("--add-header", "Cookie:$effectiveCookie")
                }
                addOption("--retries", "4")
                addOption("--fragment-retries", "4")

                if (isAudioOnly) {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("-o", "${stagingDir.absolutePath}/$baseTitle.%(ext)s")
                } else if (is720p) {
                    addOption("-f", "bestvideo[height<=720]+bestaudio/best[height<=720]/best")
                    addOption("--merge-output-format", "mp4")
                    addOption("-o", "${stagingDir.absolutePath}/$baseTitle.%(ext)s")
                } else {
                    addOption("-f", "bestvideo+bestaudio/best")
                    addOption("--merge-output-format", "mp4")
                    addOption("-o", "${stagingDir.absolutePath}/$baseTitle.%(ext)s")
                }
            }

            YoutubeDL.getInstance().execute(
                request,
                "bahmad_${System.currentTimeMillis()}"
            ) { _, _, line ->
                if (!line.isNullOrBlank()) {
                    if (logBuilder.length > 2500) {
                        logBuilder.delete(0, logBuilder.length - 1500)
                    }
                    logBuilder.append(line).append('\n')
                }
            }

            val outputFile = stagingDir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.length() >= MIN_REAL_MEDIA_BYTES &&
                        !it.name.endsWith(".part") &&
                        !it.name.endsWith(".ytdl") &&
                        !it.name.endsWith(".temp") &&
                        !it.name.endsWith(".tmp")
                }
                ?.maxByOrNull { it.length() }

            if (outputFile == null) {
                deleteRecursively(stagingDir)
                val lastErr = logBuilder.toString()
                    .lineSequence()
                    .lastOrNull { it.isNotBlank() }
                    ?.take(180)
                return DownloadOutcome(
                    false,
                    if (lastErr != null) {
                        "فشل الاستخراج: $lastErr"
                    } else {
                        "لم يتم العثور على ملف فيديو مكتمل (أقل من 256 KB أو غير متاح)."
                    }
                )
            }

            val finalExt = outputFile.extension.ifBlank { if (isAudioOnly) "mp3" else "mp4" }
            val finalFileName = "$baseTitle.$finalExt"
            val savedPath = saveFileToPublicDownloads(
                context = context,
                sourceFile = outputFile,
                fileName = finalFileName,
                mimeType = guessMimeType(finalExt)
            )
            deleteRecursively(stagingDir)

            if (savedPath != null) {
                DownloadOutcome(true, "تم حفظ الفيديو بنجاح في: $savedPath", savedPath)
            } else {
                DownloadOutcome(false, "تعذر حفظ الملف النهائي في مجلد التنزيلات.")
            }
        } catch (e: Exception) {
            deleteRecursively(stagingDir)
            val detail = logBuilder.toString()
                .lineSequence()
                .lastOrNull { it.isNotBlank() }
                ?: e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }
                ?: "خطأ غير معروف"
            DownloadOutcome(false, "فشل التنزيل: ${detail.take(180)}")
        }
    }

    private fun deleteRecursively(dir: File) {
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { deleteRecursively(it) }
        }
        dir.delete()
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

    private fun guessMimeType(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
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
        const val KEY_COOKIE = "cookie"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_FORMAT = "format"
        const val KEY_RESULT_MESSAGE = "result_message"
    }

    override fun doWork(): Result {
        val url = inputData.getString(KEY_URL).orEmpty()
        val referer = inputData.getString(KEY_REFERER).orEmpty()
        val cookie = inputData.getString(KEY_COOKIE).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME).orEmpty().ifBlank {
            "bahmad_media_${System.currentTimeMillis()}.mp4"
        }
        val format = inputData.getString(KEY_FORMAT).orEmpty().ifBlank { "MP4 (أفضل جودة)" }

        val outcome = BahmadMediaDownloader.downloadAndSaveNow(
            context = applicationContext,
            url = url,
            rawFileName = fileName,
            selectedFormat = format,
            refererUrl = referer,
            cookieHeader = cookie
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
