package com.bahmad.downloader.engine

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED
}

data class DownloadTaskItem(
    val id: String,
    val title: String,
    val url: String,
    val source: String,
    val formatLabel: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val filePath: String = ""
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

object BahmadDownloadManager {
    private val _tasks = MutableStateFlow<List<DownloadTaskItem>>(emptyList())
    val tasks: StateFlow<List<DownloadTaskItem>> = _tasks

    private val pausedIds = mutableSetOf<String>()

    fun enqueue(context: Context, info: ResolvedLinkInfo, formatLabel: String): String {
        val id = "${System.currentTimeMillis()}_${(1000..9999).random()}"
        val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            File(it, "BahmadDownloads").apply { mkdirs() }
        } ?: context.filesDir

        val safeName = info.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outputFile = File(folder, "${id}_$safeName")

        val newItem = DownloadTaskItem(
            id = id,
            title = safeName,
            url = info.directDownloadUrl,
            source = info.sourceLabel,
            formatLabel = formatLabel,
            totalBytes = info.fileSizeBytes,
            downloadedBytes = 0L,
            status = DownloadStatus.DOWNLOADING,
            filePath = outputFile.absolutePath
        )

        _tasks.value = listOf(newItem) + _tasks.value
        return id
    }

    fun togglePause(id: String) {
        val current = _tasks.value.find { it.id == id } ?: return
        if (current.status == DownloadStatus.DOWNLOADING) {
            pausedIds.add(id)
            updateTask(id) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
        } else if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.FAILED) {
            pausedIds.remove(id)
            updateTask(id) { it.copy(status = DownloadStatus.DOWNLOADING) }
        }
    }

    fun isPaused(id: String): Boolean = pausedIds.contains(id)

    fun updateTask(id: String, transform: (DownloadTaskItem) -> DownloadTaskItem) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }
}

class FastDownloadEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun runDownload(
        taskId: String,
        onProgress: (title: String, percent: Int, speedText: String, completed: Boolean, failed: Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val initialTask = BahmadDownloadManager.tasks.value.find { it.id == taskId } ?: return@withContext
        val outFile = File(initialTask.filePath)
        outFile.parentFile?.mkdirs()

        try {
            val totalSize = initialTask.totalBytes
            val canChunk = totalSize > 2 * 1024 * 1024L
            val chunks = if (canChunk) 4 else 1

            val downloadedCounter = AtomicLong(0L)
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()

            if (chunks == 1) {
                downloadRange(
                    url = initialTask.url,
                    startByte = 0L,
                    endByte = -1L,
                    outFile = outFile,
                    taskId = taskId,
                    counter = downloadedCounter,
                    onTick = { currentBytes ->
                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastTime).coerceAtLeast(1L)
                        val speed = ((currentBytes - lastBytes) * 1000L) / elapsed
                        lastBytes = currentBytes
                        lastTime = now

                        val pct = if (totalSize > 0) ((currentBytes * 100) / totalSize).toInt().coerceIn(0, 100) else 0
                        BahmadDownloadManager.updateTask(taskId) {
                            it.copy(
                                downloadedBytes = currentBytes,
                                speedBytesPerSec = speed,
                                status = if (BahmadDownloadManager.isPaused(taskId)) DownloadStatus.PAUSED else DownloadStatus.DOWNLOADING
                            )
                        }
                        onProgress(initialTask.title, pct, formatSpeed(speed), false, false)
                    }
                )
            } else {
                val partSize = totalSize / chunks
                RandomAccessFile(outFile, "rw").use { it.setLength(totalSize) }

                coroutineScope {
                    (0 until chunks).map { index ->
                        async {
                            val start = index * partSize
                            val end = if (index == chunks - 1) totalSize - 1 else (start + partSize - 1)
                            downloadRangeToOffset(
                                url = initialTask.url,
                                startByte = start,
                                endByte = end,
                                outFile = outFile,
                                taskId = taskId,
                                counter = downloadedCounter
                            )
                        }
                    }.awaitAll()

                    val finalBytes = downloadedCounter.get()
                    val pct = if (totalSize > 0) ((finalBytes * 100) / totalSize).toInt().coerceIn(0, 100) else 100
                    BahmadDownloadManager.updateTask(taskId) {
                        it.copy(downloadedBytes = finalBytes, speedBytesPerSec = 0L)
                    }
                    onProgress(initialTask.title, pct, "سرعة قصوى", false, false)
                }
            }

            if (BahmadDownloadManager.isPaused(taskId)) {
                onProgress(initialTask.title, 0, "متوقف مؤقتاً", false, false)
                return@withContext
            }

            BahmadDownloadManager.updateTask(taskId) {
                it.copy(
                    downloadedBytes = if (outFile.exists()) outFile.length() else it.downloadedBytes,
                    speedBytesPerSec = 0L,
                    status = DownloadStatus.COMPLETED
                )
            }
            onProgress(initialTask.title, 100, "اكتمل التحميل", true, false)
        } catch (_: Exception) {
            BahmadDownloadManager.updateTask(taskId) {
                it.copy(status = DownloadStatus.FAILED, speedBytesPerSec = 0L)
            }
            onProgress(initialTask.title, 0, "فشل التحميل", false, true)
        }
    }

    private fun downloadRange(
        url: String,
        startByte: Long,
        endByte: Long,
        outFile: File,
        taskId: String,
        counter: AtomicLong,
        onTick: (Long) -> Unit
    ) {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) BahmadDownloader/1.0")
        if (startByte > 0 || endByte > 0) {
            val rangeHeader = if (endByte > 0) "bytes=$startByte-$endByte" else "bytes=$startByte-"
            reqBuilder.header("Range", rangeHeader)
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Empty body")
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (BahmadDownloadManager.isPaused(taskId)) break
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        val total = counter.addAndGet(read.toLong())
                        onTick(total)
                    }
                    output.flush()
                }
            }
        }
    }

    private fun downloadRangeToOffset(
        url: String,
        startByte: Long,
        endByte: Long,
        outFile: File,
        taskId: String,
        counter: AtomicLong
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) BahmadDownloader/1.0")
            .header("Range", "bytes=$startByte-$endByte")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Empty body")
            RandomAccessFile(outFile, "rw").use { raf ->
                raf.seek(startByte)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var currentPos = startByte
                    while (currentPos <= endByte) {
                        if (BahmadDownloadManager.isPaused(taskId)) break
                        val toRead = minOf(buffer.size.toLong(), endByte - currentPos + 1).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        currentPos += read
                        counter.addAndGet(read.toLong())
                    }
                }
            }
        }
    }

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0L -> "0 KB/s"
        bytesPerSec < 1024L -> "$bytesPerSec B/s"
        bytesPerSec < 1024L * 1024L -> "${bytesPerSec / 1024L} KB/s"
        else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    }
}
