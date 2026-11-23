package com.bahmad.downloader.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ResolvedLinkInfo(
    val originalUrl: String,
    val directDownloadUrl: String,
    val fileName: String,
    val sourceLabel: String,
    val fileSizeBytes: Long,
    val supportsRange: Boolean,
    val suggestedFormats: List<String>,
    val note: String? = null,
    val isHtmlPage: Boolean = false
)

class LinkResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    fun resolve(rawInput: String): ResolvedLinkInfo {
        val cleanUrl = rawInput.trim()
        val lower = cleanUrl.lowercase()

        if (lower.contains("dailymotion.com") || lower.contains("dai.ly")) {
            return resolveDailymotion(cleanUrl)
        }

        if (lower.contains("drive.google.com")) {
            val fileId = extractGoogleDriveId(cleanUrl)
            val directUrl = if (fileId != null) {
                "https://drive.google.com/uc?export=download&confirm=t&id=$fileId"
            } else {
                cleanUrl
            }
            val head = probeUrl(directUrl)
            return ResolvedLinkInfo(
                originalUrl = cleanUrl,
                directDownloadUrl = directUrl,
                fileName = head.fileName ?: "bahmad_gdrive_${System.currentTimeMillis()}",
                sourceLabel = "Google Drive",
                fileSizeBytes = head.contentLength,
                supportsRange = head.acceptRanges,
                suggestedFormats = listOf("الملف الأصلي", "ZIP / أرشيف", "MP4", "MP3"),
                note = if (head.isHtml) "قد يتطلب الرابط مشاركة عامة (Anyone with the link) لتنزيل الملف مباشرة." else null,
                isHtmlPage = head.isHtml
            )
        }

        val probe = probeUrl(cleanUrl)
        val sourceName = detectSourceLabel(lower)
        val generatedName = probe.fileName ?: buildFallbackName(lower, sourceName, probe.isHtml)

        val formats = when {
            generatedName.endsWith(".mp3") || generatedName.endsWith(".m4a") || generatedName.endsWith(".wav") ->
                listOf("MP3 (جودة عالية)", "الملف الأصلي")
            generatedName.endsWith(".mp4") || generatedName.endsWith(".mkv") || generatedName.endsWith(".webm") ->
                listOf("1080p HD", "720p", "480p", "MP3 (صوت فقط)")
            generatedName.endsWith(".m3u8") ->
                listOf("قائمة بث HLS (.m3u8)")
            else ->
                listOf("أعلى سرعة (مباشر)", "1080p", "720p", "MP3")
        }

        val htmlNote = if (probe.isHtml) {
            "هذا الرابط صفحة ويب (HTML) وليس ملفاً مباشراً؛ يُفضّل فتحه في المتصفح الداخلي وتشغيل الفيديو لالتقاط رابط الوسائط."
        } else {
            null
        }

        return ResolvedLinkInfo(
            originalUrl = cleanUrl,
            directDownloadUrl = probe.finalUrl ?: cleanUrl,
            fileName = generatedName,
            sourceLabel = sourceName,
            fileSizeBytes = probe.contentLength,
            supportsRange = probe.acceptRanges,
            suggestedFormats = formats,
            note = htmlNote,
            isHtmlPage = probe.isHtml
        )
    }

    private fun resolveDailymotion(url: String): ResolvedLinkInfo {
        val videoId = Regex("(?:video/|dai\\.ly/)([a-zA-Z0-9]+)")
            .find(url)?.groupValues?.get(1)

        if (videoId != null) {
            try {
                val metaUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
                val req = Request.Builder()
                    .url(metaUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string().orEmpty()
                        val json = JSONObject(bodyStr)
                        val title = json.optString("title").ifBlank { "dailymotion_$videoId" }
                            .replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF ]"), "_")
                        val qualities = json.optJSONObject("qualities")
                        val streamUrl = extractBestStreamUrl(qualities)

                        if (streamUrl != null) {
                            val probe = probeUrl(streamUrl)
                            val ext = if (streamUrl.contains(".m3u8")) ".m3u8" else ".mp4"
                            return ResolvedLinkInfo(
                                originalUrl = url,
                                directDownloadUrl = streamUrl,
                                fileName = "$title$ext",
                                sourceLabel = "Dailymotion",
                                fileSizeBytes = probe.contentLength,
                                supportsRange = probe.acceptRanges,
                                suggestedFormats = listOf("MP4 / بث مباشر", "جودة عالية"),
                                note = if (ext == ".m3u8") {
                                    "تم العثور على قائمة بث HLS (.m3u8)؛ تحميل ملف .m3u8 وحده يحفظ قائمة المقاطع وليس فيديو مدمجاً."
                                } else {
                                    null
                                },
                                isHtmlPage = false
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return ResolvedLinkInfo(
            originalUrl = url,
            directDownloadUrl = url,
            fileName = "dailymotion_${videoId ?: System.currentTimeMillis()}.mp4",
            sourceLabel = "Dailymotion",
            fileSizeBytes = -1L,
            supportsRange = false,
            suggestedFormats = listOf("MP4"),
            note = "رابط صفحة Dailymotion وليس رابط ملف مباشر؛ افتحه في المتصفح الداخلي وشغّل الفيديو لالتقاط الروابط.",
            isHtmlPage = true
        )
    }

    private fun extractBestStreamUrl(qualities: JSONObject?): String? {
        if (qualities == null) return null
        val order = listOf("1080", "720", "480", "380", "240", "auto")
        for (q in order) {
            val arr = qualities.optJSONArray(q) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.startsWith("http")) return url
            }
        }
        return null
    }

    private fun extractGoogleDriveId(url: String): String? {
        val dMatch = Regex("/d/([a-zA-Z0-9_-]+)").find(url)
        if (dMatch != null) return dMatch.groupValues[1]
        val idMatch = Regex("[?&]id=([a-zA-Z0-9_-]+)").find(url)
        return idMatch?.groupValues?.get(1)
    }

    private fun detectSourceLabel(lowerUrl: String): String = when {
        lowerUrl.contains("dailymotion.com") || lowerUrl.contains("dai.ly") -> "Dailymotion"
        lowerUrl.contains("drive.google.com") -> "Google Drive"
        lowerUrl.contains("docs.google.com") -> "Google Docs"
        lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be") -> "YouTube"
        lowerUrl.contains("tiktok.com") -> "TikTok"
        lowerUrl.contains("instagram.com") -> "Instagram"
        lowerUrl.contains("x.com") || lowerUrl.contains("twitter.com") -> "X / Twitter"
        lowerUrl.contains("facebook.com") || lowerUrl.contains("fb.watch") -> "Facebook"
        lowerUrl.contains("snapchat.com") -> "Snapchat"
        lowerUrl.contains("pinterest.com") -> "Pinterest"
        lowerUrl.contains("t.me") || lowerUrl.contains("telegram") -> "Telegram"
        else -> "رابط / ملف مباشر"
    }

    private fun buildFallbackName(lowerUrl: String, source: String, isHtml: Boolean): String {
        val lastSegment = lowerUrl.substringAfterLast("/").substringBefore("?")
        if (!isHtml && lastSegment.contains(".") && lastSegment.length in 3..60) {
            return lastSegment
        }
        val ext = when {
            lowerUrl.contains(".m3u8") -> ".m3u8"
            lowerUrl.contains(".mp4") -> ".mp4"
            lowerUrl.contains(".mp3") -> ".mp3"
            lowerUrl.contains(".pdf") -> ".pdf"
            lowerUrl.contains(".zip") -> ".zip"
            lowerUrl.contains(".apk") -> ".apk"
            lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") -> ".jpg"
            lowerUrl.contains(".png") -> ".png"
            isHtml -> ".html"
            else -> ".bin"
        }
        return "bahmad_${source.replace(" ", "_").lowercase()}_${System.currentTimeMillis()}$ext"
    }

    private data class ProbeResult(
        val finalUrl: String?,
        val fileName: String?,
        val contentLength: Long,
        val acceptRanges: Boolean,
        val isHtml: Boolean
    )

    private fun probeUrl(url: String): ProbeResult {
        val ua = "Mozilla/5.0 (Linux; Android 14) BahmadDownloader/1.0"

        try {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", ua)
                .build()
            client.newCall(headReq).execute().use { resp ->
                val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                val isHtml = contentType.contains("text/html") || contentType.contains("application/xhtml")
                val disposition = resp.header("Content-Disposition")
                val headerName = disposition?.let {
                    Regex("filename=\"?([^\";]+)\"?").find(it)?.groupValues?.get(1)
                }
                val headLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val ranges = resp.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true || headLen > 1_000_000L
                if (headLen > 0L || isHtml) {
                    return ProbeResult(
                        finalUrl = resp.request.url.toString(),
                        fileName = headerName,
                        contentLength = headLen,
                        acceptRanges = ranges,
                        isHtml = isHtml
                    )
                }
            }
        } catch (_: Exception) {
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .build()

            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                val isHtml = contentType.contains("text/html") || contentType.contains("application/xhtml")
                val disposition = response.header("Content-Disposition")
                val headerName = disposition?.let {
                    Regex("filename=\"?([^\";]+)\"?").find(it)?.groupValues?.get(1)
                }
                val length = response.body?.contentLength() ?: -1L
                val ranges = response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true || length > 1_000_000L

                ProbeResult(
                    finalUrl = response.request.url.toString(),
                    fileName = headerName,
                    contentLength = length,
                    acceptRanges = ranges,
                    isHtml = isHtml
                )
            }
        } catch (_: Exception) {
            ProbeResult(url, null, -1L, false, false)
        }
    }
}
