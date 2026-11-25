package com.bahmad.downloader.engine

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.Locale

data class ResolvedLinkInfo(
    val originalUrl: String,
    val resolvedUrl: String,
    val fileName: String,
    val sourceLabel: String,
    val suggestedFormats: List<String>,
    val isHtmlPage: Boolean = false,
    val note: String? = null
)

object LinkResolver {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun applyCommonOptions(request: YoutubeDLRequest, targetUrl: String) {
        val lower = targetUrl.lowercase(Locale.US)
        request.addOption("--no-update")
        request.addOption("--no-playlist")
        request.addOption("--user-agent", BROWSER_USER_AGENT)
        if ("dailymotion.com" in lower || "dai.ly" in lower) {
            request.addOption("--referer", "https://www.dailymotion.com/")
        }
    }

    fun resolve(rawUrl: String): ResolvedLinkInfo {
        val cleaned = rawUrl.trim()
        val normalizedUrl = if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            cleaned
        } else {
            "https://$cleaned"
        }

        val lower = normalizedUrl.lowercase(Locale.US)
        val sourceLabel = when {
            "dailymotion.com" in lower || "dai.ly" in lower -> "Dailymotion"
            "youtube.com" in lower || "youtu.be" in lower -> "YouTube"
            "tiktok.com" in lower -> "TikTok"
            "instagram.com" in lower -> "Instagram"
            "x.com" in lower || "twitter.com" in lower -> "X / Twitter"
            else -> "رابط مباشر / صفحة ويب"
        }

        val formats = listOf("MP4 (أفضل جودة)", "MP4 (720p)", "MP3 / صوت فقط")

        return try {
            val request = YoutubeDLRequest(normalizedUrl).apply {
                applyCommonOptions(this, normalizedUrl)
                addOption("--skip-download")
            }
            val info = YoutubeDL.getInstance().getInfo(request)
            val title = sanitizeFileName(info.title ?: "bahmad_video")
            val directUrl = info.url ?: normalizedUrl

            ResolvedLinkInfo(
                originalUrl = normalizedUrl,
                resolvedUrl = directUrl,
                fileName = "$title.mp4",
                sourceLabel = sourceLabel,
                suggestedFormats = formats,
                isHtmlPage = false,
                note = "تم تحليل الرابط عبر محرك yt-dlp وجاهز للتحميل."
            )
        } catch (_: Throwable) {
            val fallbackName = sanitizeFileName(
                normalizedUrl.substringAfterLast('/').substringBefore('?').ifBlank { "bahmad_media" }
            )
            ResolvedLinkInfo(
                originalUrl = normalizedUrl,
                resolvedUrl = normalizedUrl,
                fileName = if (fallbackName.endsWith(".mp4", true)) fallbackName else "$fallbackName.mp4",
                sourceLabel = sourceLabel,
                suggestedFormats = formats,
                isHtmlPage = false,
                note = "سيتم تمرير الرابط مباشرة إلى محرك التحميل (yt-dlp / FFmpeg)."
            )
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]+"""), "_").take(80).ifBlank { "bahmad_video" }
    }
}
