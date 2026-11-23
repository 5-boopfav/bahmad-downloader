package com.bahmad.downloader.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
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
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    init {
        ensureNewPipeInitialized()
    }

    private fun ensureNewPipeInitialized() {
        try {
            if (NewPipe.getDownloader() == null) {
                NewPipe.init(OkHttpNewPipeDownloader(client, userAgent))
            }
        } catch (_: Exception) {
        }
    }

    fun resolve(rawInput: String): ResolvedLinkInfo {
        val cleanUrl = rawInput.trim()
        val lower = cleanUrl.lowercase()

        if (lower.contains("dailymotion.com") || lower.contains("dai.ly")) {
            return resolveDailymotion(cleanUrl)
        }

        if (lower.contains("drive.google.com")) {
            return resolveGoogleDrive(cleanUrl)
        }

        // محاولة الاستخراج عبر NewPipeExtractor للمنصات المدعومة
        tryExtractWithNewPipe(cleanUrl)?.let { return it }

        val probe = probeUrl(cleanUrl)
        val sourceName = detectSourceLabel(lower)
        val generatedName = probe.fileName ?: buildFallbackName(lower, sourceName, probe.isHtml)

        val formats = when {
            generatedName.endsWith(".mp3") || generatedName.endsWith(".m4a") || generatedName.endsWith(".wav") ->
                listOf("MP3 (جودة عالية)", "الملف الأصلي")
            generatedName.endsWith(".mp4") || generatedName.endsWith(".mkv") || generatedName.endsWith(".webm") ->
                listOf("MP4 (ملف مباشر)", "720p", "480p", "MP3 (صوت فقط)")
            generatedName.endsWith(".m3u8") ->
                listOf("قائمة بث (.m3u8)")
            else ->
                listOf("أعلى سرعة (مباشر)", "MP4", "MP3")
        }

        val htmlNote = if (probe.isHtml) {
            "هذا الرابط صفحة ويب وليس ملفاً مباشراً؛ افتحه في المتصفح الداخلي وشغّل الفيديو لالتقاط رابط الوسائط."
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

    private fun tryExtractWithNewPipe(url: String): ResolvedLinkInfo? {
        return try {
            ensureNewPipeInitialized()
            val service = NewPipe.getServiceByUrl(url) ?: return null
            val info = StreamInfo.getInfo(service, url)
            val title = sanitizeFileName(info.name.ifBlank { "bahmad_stream" })

            val bestVideo = info.videoStreams
                .filter { !it.content.isNullOrBlank() }
                .maxByOrNull { it.height }

            val bestAudio = info.audioStreams
                .filter { !it.content.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }

            val chosenUrl = bestVideo?.content ?: bestAudio?.content ?: return null
            val isHls = chosenUrl.contains(".m3u8", ignoreCase = true) ||
                bestVideo?.isVideoOnly == true

            ResolvedLinkInfo(
                originalUrl = url,
                directDownloadUrl = chosenUrl,
                fileName = "$title.${if (isHls) "m3u8" else "mp4"}",
                sourceLabel = "${service.serviceInfo.name} (NewPipe)",
                fileSizeBytes = -1L,
                supportsRange = false,
                suggestedFormats = listOf("MP4", "720p", "MP3"),
                note = if (isHls) {
                    "تم استخراج مسار بث عبر NewPipe؛ بعض الروابط المقسّمة تحتاج محرك دمج لتحويلها إلى MP4 كامل."
                } else {
                    "تم استخراج رابط وسائط عبر NewPipe."
                },
                isHtmlPage = false
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveGoogleDrive(url: String): ResolvedLinkInfo {
        val fileId = extractGoogleDriveId(url)
        val directUrl = if (fileId != null) {
            "https://drive.google.com/uc?export=download&confirm=t&id=$fileId"
        } else {
            url
        }
        val probe = probeUrl(directUrl)
        return ResolvedLinkInfo(
            originalUrl = url,
            directDownloadUrl = probe.finalUrl ?: directUrl,
            fileName = probe.fileName ?: "bahmad_gdrive_${System.currentTimeMillis()}",
            sourceLabel = "Google Drive",
            fileSizeBytes = probe.contentLength,
            supportsRange = probe.acceptRanges,
            suggestedFormats = listOf("الملف الأصلي", "ZIP / أرشيف", "MP4", "MP3"),
            note = if (probe.isHtml) {
                "يتطلب الرابط مشاركة عامة (Anyone with the link) لتنزيل الملف مباشرة."
            } else {
                null
            },
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
                    .header("User-Agent", userAgent)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string().orEmpty()
                        val json = JSONObject(bodyStr)
                        val title = sanitizeFileName(
                            json.optString("title").ifBlank { "dailymotion_$videoId" }
                        )
                        val qualities = json.optJSONObject("qualities")
                        val streamUrl = extractBestStreamUrl(qualities)

                        if (!streamUrl.isNullOrBlank()) {
                            val probe = probeUrl(streamUrl)
                            val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
                            val ext = if (isHls) ".m3u8" else ".mp4"
                            return ResolvedLinkInfo(
                                originalUrl = url,
                                directDownloadUrl = streamUrl,
                                fileName = "$title$ext",
                                sourceLabel = "Dailymotion",
                                fileSizeBytes = probe.contentLength,
                                supportsRange = probe.acceptRanges,
                                suggestedFormats = if (isHls) {
                                    listOf("قائمة بث (.m3u8)")
                                } else {
                                    listOf("MP4 (ملف مباشر)", "MP3 (صوت فقط)")
                                },
                                note = if (isHls) {
                                    "الرابط المستخرج قائمة بث HLS (.m3u8) وليست ملف MP4 واحداً؛ لتحويلها إلى MP4 يلزم تنزيل المقاطع ودمجها."
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
            fileName = "dailymotion_${videoId ?: System.currentTimeMillis()}.html",
            sourceLabel = "Dailymotion",
            fileSizeBytes = -1L,
            supportsRange = false,
            suggestedFormats = listOf("MP4", "MP3"),
            note = "لم يتم العثور على رابط ملف MP4 مباشر من Dailymotion؛ افتحه في المتصفح الداخلي وشغّل الفيديو لالتقاط الروابط.",
            isHtmlPage = true
        )
    }

    private fun extractBestStreamUrl(qualities: JSONObject?): String? {
        if (qualities == null) return null
        val order = listOf("1080", "720", "480", "380", "240", "auto")
        for (q in order) {
            val arr: JSONArray = qualities.optJSONArray(q) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val candidate = item.optString("url")
                if (candidate.startsWith("http")) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF ]"), "_")
            .trim()
            .take(60)
        return cleaned.ifBlank { "bahmad_media" }
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
            return sanitizeFileName(lastSegment)
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
        try {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", userAgent)
                .build()
            client.newCall(headReq).execute().use { resp ->
                val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                val isHtml = contentType.contains("text/html") || contentType.contains("application/xhtml")
                val disposition = resp.header("Content-Disposition")
                val headerName = disposition?.let {
                    Regex("filename=\"?([^\";]+)\"?").find(it)?.groupValues?.get(1)
                }?.let(::sanitizeFileName)
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
                .header("User-Agent", userAgent)
                .build()

            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                val isHtml = contentType.contains("text/html") || contentType.contains("application/xhtml")
                val disposition = response.header("Content-Disposition")
                val headerName = disposition?.let {
                    Regex("filename=\"?([^\";]+)\"?").find(it)?.groupValues?.get(1)
                }?.let(::sanitizeFileName)
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

private class OkHttpNewPipeDownloader(
    private val client: OkHttpClient,
    private val defaultUserAgent: String
) : Downloader() {

    @Throws(IOException::class, ExtractionException::class)
    override fun execute(request: NpRequest): NpResponse {
        val builder = Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.let { data ->
                okhttp3.RequestBody.create(null, data)
            })

        var hasUserAgent = false
        for ((key, values) in request.headers()) {
            if (key.equals("User-Agent", ignoreCase = true)) {
                hasUserAgent = true
            }
            for (v in values) {
                builder.addHeader(key, v)
            }
        }
        if (!hasUserAgent) {
            builder.header("User-Agent", defaultUserAgent)
        }

        client.newCall(builder.build()).execute().use { resp ->
            val responseBodyStr = resp.body?.string()
            val headersMap = mutableMapOf<String, List<String>>()
            for (name in resp.headers().names()) {
                headersMap[name] = resp.headers(name)
            }
            return NpResponse(
                resp.code,
                resp.message,
                headersMap,
                responseBodyStr,
                resp.request.url.toString()
            )
        }
    }
}
