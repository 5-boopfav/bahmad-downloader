package com.bahmad.downloader.engine

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class ResolvedLinkInfo(
    val originalUrl: String,
    val directDownloadUrl: String,
    val fileName: String,
    val mimeType: String? = null,
    val fileSizeBytes: Long? = null,
    val isStreamPlaylist: Boolean = false,
    val isHtmlPage: Boolean = false,
    val sourceLabel: String = "رابط مباشر",
    val note: String? = null,
    val suggestedFormats: List<String> = listOf("MP4 (أفضل جودة)", "MP3 (صوت فقط)")
)

object LinkResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val adKeywords = listOf(
        "/ad/", "/ads/", "ad_break", "preroll", "pre-roll", "midroll", "postroll",
        "vast", "vmap", "doubleclick", "googlesyndication", "adserver", "commercial"
    )

    fun isLikelyAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return adKeywords.any { lower.contains(it) } ||
            Regex("""(?:^|[/?_&-])(ad|ads|preroll|midroll|postroll)(?:$|[/?_&-])""").containsMatchIn(lower)
    }

    fun resolve(rawUrl: String): ResolvedLinkInfo {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return ResolvedLinkInfo(
                originalUrl = trimmed,
                directDownloadUrl = trimmed,
                fileName = "bahmad_media_${System.currentTimeMillis()}.mp4",
                sourceLabel = "رابط غير مباشر",
                note = "تأكد من بدء الرابط بـ http:// أو https://",
                suggestedFormats = listOf("MP4 (أفضل جودة)")
            )
        }

        if (isLikelyAdUrl(trimmed)) {
            return ResolvedLinkInfo(
                originalUrl = trimmed,
                directDownloadUrl = trimmed,
                fileName = "bahmad_ad_blocked_${System.currentTimeMillis()}.mp4",
                isHtmlPage = false,
                sourceLabel = "رابط إعلاني مستبعد",
                note = "تم رصد مؤشرات إعلان في الرابط؛ شغّل الفيديو الرئيسي وانتظر بدء المحتوى الفعلي.",
                suggestedFormats = listOf("MP4 (أفضل جودة)")
            )
        }

        val sourceLabel = detectSourceLabel(trimmed)

        val ytdlpResult = runCatching {
            val request = YoutubeDLRequest(trimmed).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
            }
            val videoInfo = YoutubeDL.getInstance().getInfo(request)
            val title = sanitizeFileName(videoInfo.title.orEmpty().ifBlank { "bahmad_video_${System.currentTimeMillis()}" })
            val ext = videoInfo.ext?.takeIf { it.isNotBlank() } ?: "mp4"
            val streamUrl = videoInfo.url?.takeIf { it.isNotBlank() && !isLikelyAdUrl(it) } ?: trimmed
            val extractorLabel = videoInfo.extractor?.takeIf { it.isNotBlank() } ?: sourceLabel

            ResolvedLinkInfo(
                originalUrl = trimmed,
                directDownloadUrl = streamUrl,
                fileName = "$title.$ext",
                mimeType = if (ext == "mp3" || ext == "m4a") "audio/*" else "video/*",
                fileSizeBytes = videoInfo.fileSize.takeIf { it > 0L },
                isStreamPlaylist = streamUrl.contains(".m3u8", ignoreCase = true),
                isHtmlPage = false,
                sourceLabel = extractorLabel,
                note = "تم استخراج الفيديو الأساسي عبر محرك yt-dlp.",
                suggestedFormats = listOf("MP4 (أفضل جودة)", "MP3 (صوت فقط)", "الأصلية")
            )
        }.getOrNull()

        if (ytdlpResult != null) {
            return ytdlpResult
        }

        return try {
            val probeRequest = Request.Builder()
                .url(trimmed)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
                .build()

            client.newCall(probeRequest).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase()
                val contentDisposition = response.header("Content-Disposition")
                val contentLength = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                val isHtml = contentType?.contains("text/html") == true

                val resolvedName = extractFileName(
                    url = finalUrl,
                    disposition = contentDisposition,
                    fallbackPrefix = "bahmad_media"
                )

                ResolvedLinkInfo(
                    originalUrl = trimmed,
                    directDownloadUrl = finalUrl,
                    fileName = resolvedName,
                    mimeType = contentType,
                    fileSizeBytes = contentLength,
                    isStreamPlaylist = finalUrl.contains(".m3u8", ignoreCase = true),
                    isHtmlPage = isHtml,
                    sourceLabel = sourceLabel,
                    note = if (isHtml) {
                        "تعذر الاستخراج المباشر للصفحة؛ يمكنك فتحها في المتصفح الداخلي."
                    } else {
                        "تم فحص الرابط بنجاح وجاهز للتحميل."
                    },
                    suggestedFormats = listOf("MP4 (أفضل جودة)", "MP3 (صوت فقط)", "الأصلية")
                )
            }
        } catch (_: Exception) {
            val fallbackName = extractFileName(trimmed, null, "bahmad_media")
            ResolvedLinkInfo(
                originalUrl = trimmed,
                directDownloadUrl = trimmed,
                fileName = fallbackName,
                sourceLabel = sourceLabel,
                note = "سيتم تمرير الرابط مباشرة إلى محرك التحميل.",
                suggestedFormats = listOf("MP4 (أفضل جودة)", "MP3 (صوت فقط)")
            )
        }
    }

    private fun detectSourceLabel(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("dailymotion.com") || lower.contains("dai.ly") -> "Dailymotion"
            lower.contains("drive.google.com") -> "Google Drive"
            lower.contains(".m3u8") -> "بث مباشر HLS"
            else -> "رابط مباشر"
        }
    }

    private fun extractFileName(
        url: String,
        disposition: String?,
        fallbackPrefix: String
    ): String {
        if (!disposition.isNullOrBlank()) {
            val utf8Match = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
            val asciiMatch = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(disposition)
            val rawDisposed = utf8Match?.groupValues?.get(1) ?: asciiMatch?.groupValues?.get(1)
            if (!rawDisposed.isNullOrBlank()) {
                val decoded = runCatching {
                    URLDecoder.decode(rawDisposed.trim(), StandardCharsets.UTF_8.name())
                }.getOrDefault(rawDisposed.trim())
                val sanitized = sanitizeFileName(decoded)
                if (sanitized.isNotBlank()) return sanitized
            }
        }

        val pathPart = url.substringBefore("?").substringBefore("#").substringAfterLast("/")
        val decodedPath = runCatching {
            URLDecoder.decode(pathPart, StandardCharsets.UTF_8.name())
        }.getOrDefault(pathPart)

        val cleaned = sanitizeFileName(decodedPath)
        if (cleaned.isNotBlank() && cleaned.contains(".")) {
            return cleaned
        }
        return "${fallbackPrefix}_${System.currentTimeMillis()}.mp4"
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(70)
    }
}
