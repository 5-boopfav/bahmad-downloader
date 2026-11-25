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
    val suggestedFormats: List<String> = listOf("MP4 (أفضل جودة)", "MP4 (720p)", "MP3 (صوت فقط)")
)

object LinkResolver {

    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val adOrFragmentKeywords = listOf(
        "/ad/", "/ads/", "ad_break", "preroll", "pre-roll", "midroll", "postroll",
        "vast", "vmap", "doubleclick", "googlesyndication", "adserver", "commercial",
        "rubiconproject", "pubmatic", "criteo", "taboola", "outbrain", "ima3",
        "seg-", "frag-", "chunk-", ".ts", ".m4s", "init.mp4"
    )

    fun isLikelyAdOrSegmentUrl(url: String): Boolean {
        val lower = url.lowercase()
        return adOrFragmentKeywords.any { lower.contains(it) } ||
            Regex("""(?:^|[/?_&-])(ad|ads|preroll|midroll|postroll|advert)(?:$|[/?_&-])""").containsMatchIn(lower)
    }

    fun isSupportedPlatformPage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("dailymotion.com/video/") ||
            lower.contains("dai.ly/") ||
            lower.contains("youtube.com/") ||
            lower.contains("youtu.be/") ||
            lower.contains("tiktok.com/") ||
            lower.contains("instagram.com/") ||
            lower.contains("facebook.com/") ||
            lower.contains("fb.watch/") ||
            lower.contains("x.com/") ||
            lower.contains("twitter.com/") ||
            lower.contains("vimeo.com/")
    }

    fun resolve(rawUrl: String): ResolvedLinkInfo {
        val trimmed = normalizeUrl(rawUrl.trim())
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return ResolvedLinkInfo(
                originalUrl = trimmed,
                directDownloadUrl = trimmed,
                fileName = "bahmad_media_${System.currentTimeMillis()}.mp4",
                sourceLabel = "رابط غير صالح",
                note = "تأكد من بدء الرابط بـ http:// أو https://",
                suggestedFormats = listOf("MP4 (أفضل جودة)")
            )
        }

        if (isLikelyAdOrSegmentUrl(trimmed)) {
            return ResolvedLinkInfo(
                originalUrl = trimmed,
                directDownloadUrl = trimmed,
                fileName = "blocked_ad_${System.currentTimeMillis()}.mp4",
                isHtmlPage = false,
                sourceLabel = "رابط إعلان أو مقطع مجزأ",
                note = "تم تجاهل هذا الرابط لأنه إعلان أو مقطع جزئي؛ استخدم رابط صفحة الفيديو الأساسي.",
                suggestedFormats = listOf("MP4 (أفضل جودة)")
            )
        }

        val sourceLabel = detectSourceLabel(trimmed)
        var ytdlpErrorMsg: String? = null

        val ytdlpResult = try {
            val request = YoutubeDLRequest(trimmed).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--geo-bypass")
                addOption("--user-agent", BROWSER_USER_AGENT)
                addOption("--referer", trimmed)
            }
            val videoInfo = YoutubeDL.getInstance().getInfo(request)
            val title = sanitizeFileName(
                videoInfo.title.orEmpty().ifBlank { "bahmad_video_${System.currentTimeMillis()}" }
            )
            val extractorLabel = videoInfo.extractor?.takeIf { it.isNotBlank() } ?: sourceLabel

            ResolvedLinkInfo(
                originalUrl = trimmed,
                directDownloadUrl = trimmed,
                fileName = "$title.mp4",
                mimeType = "video/mp4",
                fileSizeBytes = videoInfo.fileSize.takeIf { it > 0L },
                isStreamPlaylist = false,
                isHtmlPage = false,
                sourceLabel = extractorLabel,
                note = "جاهز للتحميل المباشر عبر محرك الاستخراج ($extractorLabel).",
                suggestedFormats = listOf("MP4 (أفضل جودة)", "MP4 (720p)", "MP3 (صوت فقط)")
            )
        } catch (e: Exception) {
            ytdlpErrorMsg = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(140)
            null
        }

        if (ytdlpResult != null) {
            return ytdlpResult
        }

        if (isSupportedPlatformPage(trimmed)) {
            val videoId = trimmed.substringAfterLast("/").substringBefore("?").take(24).ifBlank { "video" }
            return ResolvedLinkInfo(
                originalUrl = trimmed,
                directDownloadUrl = trimmed,
                fileName = "bahmad_${sourceLabel}_$videoId.mp4",
                mimeType = "video/mp4",
                isHtmlPage = false,
                sourceLabel = sourceLabel,
                note = if (ytdlpErrorMsg != null) {
                    "سيتم التنزيل عبر المحرك مباشرة (ملاحظة الفحص: $ytdlpErrorMsg)"
                } else {
                    "تم التعرف على رابط $sourceLabel؛ اضغط ابدأ التحميل الآن."
                },
                suggestedFormats = listOf("MP4 (أفضل جودة)", "MP4 (720p)", "MP3 (صوت فقط)")
            )
        }

        return try {
            val probeRequest = Request.Builder()
                .url(trimmed)
                .header("User-Agent", BROWSER_USER_AGENT)
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
                        "هذه صفحة ويب؛ افتحها في المتصفح الداخلي وانتقل لصفحة الفيديو ثم اضغط استخراج وتحميل فيديو الصفحة الحالية."
                    } else {
                        "تم فحص الرابط المباشر بنجاح وجاهز للتحميل."
                    },
                    suggestedFormats = listOf("MP4 (أفضل جودة)", "MP3 (صوت فقط)")
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

    private fun normalizeUrl(url: String): String {
        if (url.contains("dai.ly/", ignoreCase = true)) {
            val id = url.substringAfter("dai.ly/").substringBefore("?").substringBefore("/").trim()
            if (id.isNotEmpty()) return "https://www.dailymotion.com/video/$id"
        }
        return url
    }

    private fun detectSourceLabel(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("dailymotion.com") || lower.contains("dai.ly") -> "Dailymotion"
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "YouTube"
            lower.contains("tiktok.com") -> "TikTok"
            lower.contains("instagram.com") -> "Instagram"
            lower.contains("facebook.com") || lower.contains("fb.watch") -> "Facebook"
            lower.contains("drive.google.com") -> "Google Drive"
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
