package com.bahmad.downloader.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

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

        val normalizedUrl = normalizeKnownHosts(trimmed)
        val sourceLabel = detectSourceLabel(normalizedUrl)

        return try {
            val probeRequest = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
                .build()

            client.newCall(probeRequest).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase()
                val contentDisposition = response.header("Content-Disposition")
                val contentLength = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }

                val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true) ||
                    contentType?.contains("mpegurl") == true

                if (isM3u8) {
                    val playlistText = response.body?.string().orEmpty()
                    val bestVariantUrl = extractBestHlsVariantUrl(finalUrl, playlistText)
                    val title = extractFileName(
                        url = bestVariantUrl ?: finalUrl,
                        disposition = contentDisposition,
                        fallbackPrefix = "bahmad_stream"
                    ).let { name ->
                        val base = name.substringBeforeLast(".")
                        "$base.mp4"
                    }

                    return ResolvedLinkInfo(
                        originalUrl = trimmed,
                        directDownloadUrl = bestVariantUrl ?: finalUrl,
                        fileName = title,
                        mimeType = "video/mp4",
                        fileSizeBytes = null,
                        isStreamPlaylist = true,
                        isHtmlPage = false,
                        sourceLabel = if (sourceLabel == "رابط مباشر") "بث مباشر HLS (.m3u8)" else sourceLabel,
                        note = "تم استخراج قائمة البث وجاهز للتحميل",
                        suggestedFormats = listOf("MP4 (أفضل جودة)", "MP3 (صوت فقط)")
                    )
                }

                val isHtml = contentType?.contains("text/html") == true &&
                    !finalUrl.contains("drive.google.com/uc", ignoreCase = true)

                val resolvedName = extractFileName(
                    url = finalUrl,
                    disposition = contentDisposition,
                    fallbackPrefix = "bahmad_media"
                )
                val formats = buildAvailableFormats(resolvedName, contentType)

                ResolvedLinkInfo(
                    originalUrl = trimmed,
                    directDownloadUrl = finalUrl,
                    fileName = resolvedName,
                    mimeType = contentType,
                    fileSizeBytes = contentLength,
                    isStreamPlaylist = false,
                    isHtmlPage = isHtml,
                    sourceLabel = sourceLabel,
                    note = if (isHtml) {
                        "الرابط صفحة ويب؛ يُفضل فتحه في المتصفح الداخلي لالتقاط الوسائط."
                    } else {
                        "تم فحص الرابط بنجاح وجاهز للتحميل."
                    },
                    suggestedFormats = formats
                )
            }
        } catch (_: Exception) {
            val fallbackName = extractFileName(normalizedUrl, null, "bahmad_media")
            ResolvedLinkInfo(
                originalUrl = trimmed,
                directDownloadUrl = normalizedUrl,
                fileName = fallbackName,
                isStreamPlaylist = normalizedUrl.contains(".m3u8", ignoreCase = true),
                isHtmlPage = false,
                sourceLabel = sourceLabel,
                note = "تعذر الفحص المسبق الكامل؛ يمكنك محاولة التحميل مباشرة.",
                suggestedFormats = buildAvailableFormats(fallbackName, null)
            )
        }
    }

    private fun detectSourceLabel(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("drive.google.com") -> "Google Drive"
            lower.contains("dailymotion.com") || lower.contains("dai.ly") -> "Dailymotion"
            lower.contains(".m3u8") -> "بث مباشر HLS"
            else -> "رابط مباشر"
        }
    }

    private fun normalizeKnownHosts(url: String): String {
        val driveFileRegex = Pattern.compile("https?://drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)")
        val driveMatcher = driveFileRegex.matcher(url)
        if (driveMatcher.find()) {
            val fileId = driveMatcher.group(1)
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }

        if (url.contains("dai.ly/", ignoreCase = true)) {
            val videoId = url.substringAfter("dai.ly/").substringBefore("?").substringBefore("/").trim()
            if (videoId.isNotEmpty()) {
                return "https://www.dailymotion.com/video/$videoId"
            }
        }

        return url
    }

    private fun extractBestHlsVariantUrl(basePlaylistUrl: String, playlistBody: String): String? {
        if (playlistBody.isBlank()) return null
        val lines = playlistBody.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var highestBandwidth = -1
        var bestUri: String? = null

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(line)
                val bandwidth = bwMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val nextLine = lines.getOrNull(i + 1)
                if (nextLine != null && !nextLine.startsWith("#") && bandwidth > highestBandwidth) {
                    highestBandwidth = bandwidth
                    bestUri = nextLine
                }
            }
        }

        val chosen = bestUri ?: lines.firstOrNull { !it.startsWith("#") } ?: return null
        return resolveRelativeUrl(basePlaylistUrl, chosen)
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            return candidate
        }
        return try {
            java.net.URI(baseUrl).resolve(candidate).toString()
        } catch (_: Exception) {
            candidate
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

    private fun buildAvailableFormats(fileName: String, mimeType: String?): List<String> {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        val isAudio = ext in listOf("mp3", "m4a", "aac", "ogg", "wav", "flac") ||
            mimeType?.startsWith("audio/") == true

        return if (isAudio) {
            listOf("MP3 (صوت فقط)", "الأصلية")
        } else {
            listOf("MP4 (أفضل جودة)", "MP3 (صوت فقط)", "الأصلية")
        }
    }
}
