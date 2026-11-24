package com.bahmad.downloader.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
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

    fun downloadAndSave(
        context: Context,
        info: ResolvedLinkInfo,
        selectedFormat: String
    ): DownloadOutcome {
        DownloaderEngineInitializer.ensureInitialized(context)

        val isAudioOnly = selectedFormat.contains("MP3", ignoreCase = true) ||
            selectedFormat.contains("صوت", ignoreCase = true)

        val targetUrl = info.originalUrl.ifBlank { info.directDownloadUrl }
        val baseTitle = sanitizeBaseName(info.fileName)

        // 1. محاولة التنزيل عبر YoutubeDL إذا كان الرابط يدعم الاستخراج
        if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
            val ytResult = tryDownloadWithYoutubeDL(context, targetUrl, baseTitle, selectedFormat, isAudioOnly)
            if (ytResult != null) return ytResult
        }

        // 2. مسار احتياطي للروابط المباشرة عبر OkHttp + الحفظ في Downloads / MediaStore
        val directUrl = info.directDownloadUrl.trim()
        if (!directUrl.startsWith("http://") && !directUrl.startsWith("https://")) {
            return DownloadOutcome(false, "الرابط غير صالح للتحميل المباشر.")
        }

        val tempDir = File(context.cacheDir, "bahmad_downloads").apply { mkdirs() }
        val ext = if (isAudioOnly) "mp3" else resolveExtension(info.fileName, directUrl)
        val tempFile = File(tempDir, "${baseTitle}_${System.currentTimeMillis()}.$ext")

        return try {
            val req = Request.Builder()
                .url(directUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return DownloadOutcome(false, "فشل التحميل المباشر (رمز الاستجابة: ${resp.code}).")
                }
                val body = resp.body ?: return DownloadOutcome(false, "تعذر قراءة بيانات الملف من الخادم.")
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
            DownloadOutcome(false, "حدث خطأ أثناء التحميل: ${e.message ?: "خطأ غير معروف"}")
        }
    }

    private fun tryDownloadWithYoutubeDL(
        context: Context,
        url: String,
        baseTitle: String,
        selectedFormat: String,
        isAudioOnly: Boolean
    ): DownloadOutcome? {
        return try {
            val workDir = File(context.cacheDir, "ydl_work_${System.currentTimeMillis()}").apply { mkdirs() }
            val request = YoutubeDLRequest(url).apply {
                addOption("-o", File(workDir, "$baseTitle.%(ext)s").absolutePath)
                addOption("--no-playlist")
                addOption("--no-warnings")

                if (isAudioOnly) {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                } else {
                    val heightMatch = Regex("(\\d{3,4})p").find(selectedFormat)?.groupValues?.get(1)
                    if (heightMatch != null) {
                        addOption("-f", "bv*[height<=$heightMatch]+ba/b[height<=$heightMatch]/bv*+ba/b")
                    } else {
                        addOption("-f", "bv*+ba/b")
                    }
                    addOption("--merge-output-format", "mp4")
                }
            }

            YoutubeDL.getInstance().execute(request, null, null)

            val outputFile = workDir.listFiles()
                ?.filter { it.isFile && it.length() > 0L }
                ?.maxByOrNull { it.lastModified() }

            if (outputFile == null) {
                workDir.deleteRecursively()
                return null
            }

            val finalExt = outputFile.extension.ifBlank { if (isAudioOnly) "mp3" else "mp4" }
            val finalName = "$baseTitle.$finalExt"
            val savedPath = saveFileToPublicDownloads(
                context = context,
                sourceFile = outputFile,
                fileName = finalName,
                mimeType = guessMimeType(finalExt)
            )
            workDir.deleteRecursively()

            if (savedPath != null) {
                DownloadOutcome(true, "تم حفظ الملف في مجلد التنزيلات: $savedPath", savedPath)
            } else {
                DownloadOutcome(false, "تم تنزيل الملف ولكن تعذر نقله إلى مجلد التنزيلات.")
            }
        } catch (_: Throwable) {
            null
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
