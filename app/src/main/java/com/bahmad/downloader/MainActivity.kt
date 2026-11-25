package com.bahmad.downloader

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bahmad.downloader.engine.BahmadDownloadWorker
import com.bahmad.downloader.engine.BahmadMediaDownloader
import com.bahmad.downloader.engine.DownloaderEngineInitializer
import com.bahmad.downloader.engine.LinkResolver
import com.bahmad.downloader.engine.ResolvedLinkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BahmadBgDark = Color(0xFF0E1116)
private val BahmadSurface = Color(0xFF171C24)
private val BahmadGold = Color(0xFFE5B84B)

private enum class DownloadItemState {
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED
}

private data class DownloadUiItem(
    val workId: String,
    val url: String,
    val fileName: String,
    val format: String,
    val progress: Int = 0,
    val status: String = "قيد التنزيل في الخلفية...",
    val state: DownloadItemState = DownloadItemState.ACTIVE,
    val savedUri: String? = null,
    val mimeType: String = "video/mp4"
)

class MainActivity : ComponentActivity() {
    private var sharedUrlState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloaderEngineInitializer.ensureInitialized(this)
        extractSharedUrl(intent)?.let { sharedUrlState.value = it }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = BahmadGold,
                    onPrimary = BahmadBgDark,
                    background = BahmadBgDark,
                    surface = BahmadSurface
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BahmadBgDark
                ) {
                    BahmadMainScreen(
                        incomingSharedUrl = sharedUrlState.value,
                        onConsumeSharedUrl = { sharedUrlState.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedUrl(intent)?.let { sharedUrlState.value = it }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val match = Regex("""https?://\S+""").find(text)?.value
            return match ?: text.takeIf { it.isNotBlank() }
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BahmadMainScreen(
    incomingSharedUrl: String?,
    onConsumeSharedUrl: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    var isUpdatingEngine by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var resolvedInfo by remember { mutableStateOf<ResolvedLinkInfo?>(null) }
    var selectedFormat by remember { mutableStateOf("MP4 (أفضل جودة)") }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var currentPageUrl by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val downloadItems = remember { mutableStateListOf<DownloadUiItem>() }
    val watcherJobs = remember { mutableMapOf<String, Job>() }

    fun runLinkCheck(targetUrl: String) {
        val target = targetUrl.trim()
        if (target.isEmpty() || isChecking) return
        isChecking = true
        statusMessage = "جاري فحص الرابط واستخراج الفيديو..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DownloaderEngineInitializer.ensureInitialized(context)
                LinkResolver.resolve(target)
            }
            isChecking = false
            resolvedInfo = result
            selectedFormat = result.suggestedFormats.firstOrNull() ?: "MP4 (أفضل جودة)"
            statusMessage = result.note
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DownloaderEngineInitializer.ensureInitialized(context)
        }
    }

    LaunchedEffect(incomingSharedUrl) {
        if (!incomingSharedUrl.isNullOrBlank()) {
            urlInput = incomingSharedUrl
            selectedTab = 0
            onConsumeSharedUrl()
            runLinkCheck(incomingSharedUrl)
        }
    }

    fun startWatchingWork(workId: String) {
        watcherJobs[workId]?.cancel()
        watcherJobs[workId] = scope.launch(Dispatchers.IO) {
            val wm = WorkManager.getInstance(context.applicationContext)
            repeat(300) {
                val idx = downloadItems.indexOfFirst { it.workId == workId }
                if (idx < 0) return@launch
                if (downloadItems[idx].state == DownloadItemState.PAUSED) return@launch

                val infoList = try {
                    wm.getWorkInfosForUniqueWork(BahmadMediaDownloader.uniqueWorkName(workId)).get()
                } catch (_: Exception) {
                    emptyList<WorkInfo>()
                }
                val current = infoList.firstOrNull()
                if (current != null) {
                    val liveProgress = current.progress.getInt(BahmadDownloadWorker.KEY_PROGRESS, -1)
                    val liveStatus = current.progress.getString(BahmadDownloadWorker.KEY_STATUS)

                    if (current.state == WorkInfo.State.SUCCEEDED || current.state == WorkInfo.State.FAILED) {
                        val succeeded = current.state == WorkInfo.State.SUCCEEDED
                        val msg = current.outputData.getString(BahmadDownloadWorker.KEY_RESULT_MESSAGE)
                            ?: if (succeeded) "تم حفظ الملف في مجلد التنزيلات" else "فشل تنزيل الملف"
                        val uriStr = current.outputData.getString(BahmadDownloadWorker.KEY_RESULT_URI)
                        val mimeStr = current.outputData.getString(BahmadDownloadWorker.KEY_RESULT_MIME) ?: "video/mp4"

                        withContext(Dispatchers.Main) {
                            val latestIdx = downloadItems.indexOfFirst { it.workId == workId }
                            if (latestIdx >= 0 && downloadItems[latestIdx].state != DownloadItemState.PAUSED) {
                                statusMessage = msg
                                downloadItems[latestIdx] = downloadItems[latestIdx].copy(
                                    progress = if (succeeded) 100 else downloadItems[latestIdx].progress,
                                    status = if (succeeded) "مكتمل: $msg" else "فشل: $msg",
                                    state = if (succeeded) DownloadItemState.COMPLETED else DownloadItemState.FAILED,
                                    savedUri = uriStr,
                                    mimeType = mimeStr
                                )
                            }
                        }
                        return@launch
                    } else if (current.state == WorkInfo.State.CANCELLED) {
                        withContext(Dispatchers.Main) {
                            val latestIdx = downloadItems.indexOfFirst { it.workId == workId }
                            if (latestIdx >= 0 && downloadItems[latestIdx].state != DownloadItemState.PAUSED) {
                                downloadItems[latestIdx] = downloadItems[latestIdx].copy(
                                    status = "تم إلغاء التحميل",
                                    state = DownloadItemState.FAILED
                                )
                            }
                        }
                        return@launch
                    } else if (liveProgress >= 0 || !liveStatus.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            val latestIdx = downloadItems.indexOfFirst { it.workId == workId }
                            if (latestIdx >= 0 && downloadItems[latestIdx].state == DownloadItemState.ACTIVE) {
                                val existing = downloadItems[latestIdx]
                                downloadItems[latestIdx] = existing.copy(
                                    progress = if (liveProgress >= 0) liveProgress else existing.progress,
                                    status = liveStatus ?: existing.status
                                )
                            }
                        }
                    }
                }
                delay(700L)
            }
        }
    }

    fun startBackgroundDownload(info: ResolvedLinkInfo, format: String) {
        resolvedInfo = null
        selectedTab = 1
        val workId = BahmadMediaDownloader.enqueueBackgroundDownload(context, info, format)
        downloadItems.add(
            0,
            DownloadUiItem(
                workId = workId,
                url = info.originalUrl,
                fileName = info.fileName,
                format = format,
                progress = 5,
                status = "تمت جدولة التحميل في الخلفية...",
                state = DownloadItemState.ACTIVE
            )
        )
        statusMessage = "تمت جدولة التحميل في الخلفية: ${info.fileName}"
        startWatchingWork(workId)
    }

    fun pauseItem(item: DownloadUiItem) {
        watcherJobs[item.workId]?.cancel()
        BahmadMediaDownloader.pauseDownload(context, item.workId)
        val idx = downloadItems.indexOfFirst { it.workId == item.workId }
        if (idx >= 0) {
            downloadItems[idx] = item.copy(
                status = "متوقف مؤقتاً (${item.progress}%)",
                state = DownloadItemState.PAUSED
            )
        }
    }

    fun resumeItem(item: DownloadUiItem) {
        val idx = downloadItems.indexOfFirst { it.workId == item.workId }
        if (idx >= 0) {
            downloadItems[idx] = item.copy(
                status = "جاري استئناف التحميل...",
                state = DownloadItemState.ACTIVE
            )
        }
        BahmadMediaDownloader.resumeDownload(
            context = context,
            workId = item.workId,
            url = item.url,
            fileName = item.fileName,
            format = item.format
        )
        startWatchingWork(item.workId)
    }

    fun removeItem(item: DownloadUiItem) {
        watcherJobs[item.workId]?.cancel()
        watcherJobs.remove(item.workId)
        BahmadMediaDownloader.cancelAndCleanupDownload(context, item.workId, item.url)
        downloadItems.removeAll { it.workId == item.workId }
    }

    fun openSavedMedia(item: DownloadUiItem) {
        val rawUri = item.savedUri ?: return
        try {
            val uri = Uri.parse(rawUri)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "فتح الملف"))
        } catch (_: Throwable) {
            statusMessage = "تعذر فتح الملف مباشرة، افتحه من مجلد التنزيلات."
        }
    }

    fun shareSavedMedia(item: DownloadUiItem) {
        val rawUri = item.savedUri ?: return
        try {
            val uri = Uri.parse(rawUri)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "مشاركة الملف"))
        } catch (_: Throwable) {
            statusMessage = "تعذر مشاركة الملف مباشرة."
        }
    }

    fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (clipText.isNotEmpty()) {
            urlInput = clipText
            statusMessage = "تم لصق الرابط من الحافظة."
        } else {
            statusMessage = "الحافظة فارغة."
        }
    }

    Scaffold(
        containerColor = BahmadBgDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "بحمد للتحميل",
                        fontWeight = FontWeight.Bold,
                        color = BahmadGold
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = BahmadSurface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    label = { Text("الرئيسية") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                    label = { Text("التحميلات") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        if (browserUrl == null) {
                            val initial = if (urlInput.startsWith("http")) urlInput.trim() else "https://www.dailymotion.com"
                            browserUrl = initial
                            currentPageUrl = initial
                        }
                    },
                    icon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                    label = { Text("المتصفح") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTab) {
                0 -> {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("الصق الرابط هنا") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { pasteFromClipboard() }) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "لصق من الحافظة")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { runLinkCheck(urlInput) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BahmadGold,
                                contentColor = BahmadBgDark
                            )
                        ) {
                            Text(if (isChecking) "جاري الفحص..." else "فحص الرابط", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val target = urlInput.trim().ifEmpty { "https://www.dailymotion.com" }
                                val formatted = if (target.startsWith("http")) target else "https://$target"
                                browserUrl = formatted
                                currentPageUrl = formatted
                                selectedTab = 2
                            }
                        ) {
                            Icon(Icons.Rounded.Public, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("المتصفح")
                        }
                    }

                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = Color(0xFFD0D6E0),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                1 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "قائمة التحميلات",
                            fontWeight = FontWeight.Bold,
                            color = BahmadGold
                        )
                        OutlinedButton(
                            onClick = {
                                if (!isUpdatingEngine) {
                                    isUpdatingEngine = true
                                    statusMessage = "جاري تحديث محرك yt-dlp..."
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            DownloaderEngineInitializer.updateYtDlpIfNeeded(context, force = true)
                                        }
                                        isUpdatingEngine = false
                                        statusMessage = "تم فحص وتحديث محرك yt-dlp."
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isUpdatingEngine) "جاري التحديث..." else "تحديث المحرك")
                        }
                    }

                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = BahmadGold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (downloadItems.isEmpty()) {
                        Text(
                            text = "يتم جدولة التنزيل في الخلفية وحفظ الملفات في مجلد التنزيلات (Downloads).",
                            color = Color(0xFFB0B7C3)
                        )
                    } else {
                        downloadItems.forEach { item ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = BahmadSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${item.fileName} (${item.format})",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${item.progress.coerceIn(0, 100)}%",
                                            color = BahmadGold,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    LinearProgressIndicator(
                                        progress = { item.progress.coerceIn(0, 100) / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        color = when (item.state) {
                                            DownloadItemState.FAILED -> Color(0xFFE55353)
                                            DownloadItemState.PAUSED -> Color(0xFF9AA5B4)
                                            else -> BahmadGold
                                        },
                                        trackColor = Color(0xFF2A3240)
                                    )

                                    Text(
                                        text = item.status,
                                        color = when (item.state) {
                                            DownloadItemState.FAILED -> Color(0xFFFF8A8A)
                                            DownloadItemState.PAUSED -> Color(0xFFC5D0DE)
                                            else -> Color(0xFFD0D6E0)
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        when (item.state) {
                                            DownloadItemState.ACTIVE -> {
                                                OutlinedButton(
                                                    onClick = { pauseItem(item) },
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Rounded.Pause, contentDescription = null)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("إيقاف مؤقت")
                                                }
                                            }
                                            DownloadItemState.PAUSED, DownloadItemState.FAILED -> {
                                                Button(
                                                    onClick = { resumeItem(item) },
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = BahmadGold,
                                                        contentColor = BahmadBgDark
                                                    )
                                                ) {
                                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        if (item.state == DownloadItemState.PAUSED) "استئناف" else "إعادة المحاولة",
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            DownloadItemState.COMPLETED -> {
                                                if (!item.savedUri.isNullOrBlank()) {
                                                    Button(
                                                        onClick = { openSavedMedia(item) },
                                                        modifier = Modifier.weight(1f),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = BahmadGold,
                                                            contentColor = BahmadBgDark
                                                        )
                                                    ) {
                                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("تشغيل", fontWeight = FontWeight.Bold)
                                                    }
                                                    OutlinedButton(
                                                        onClick = { shareSavedMedia(item) }
                                                    ) {
                                                        Icon(Icons.Rounded.Share, contentDescription = null)
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("مشاركة")
                                                    }
                                                }
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { removeItem(item) }
                                        ) {
                                            Icon(Icons.Rounded.Delete, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("إزالة")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = browserUrl ?: "",
                            onValueChange = { browserUrl = it },
                            label = { Text("عنوان الصفحة") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            browserUrl = null
                            currentPageUrl = null
                            selectedTab = 0
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "إغلاق")
                        }
                    }

                    val activePage = currentPageUrl ?: browserUrl
                    if (!activePage.isNullOrBlank() && activePage.startsWith("http")) {
                        Button(
                            onClick = { runLinkCheck(activePage) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BahmadGold,
                                contentColor = BahmadBgDark
                            )
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isChecking) "جاري استخراج الفيديو..." else "استخراج وتحميل فيديو الصفحة الحالية",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    BahmadWebBrowserView(
                        url = browserUrl ?: "https://www.dailymotion.com",
                        onPageUrlChanged = { updatedUrl ->
                            currentPageUrl = updatedUrl
                            browserUrl = updatedUrl
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        val info = resolvedInfo
        if (info != null) {
            ModalBottomSheet(
                onDismissRequest = { resolvedInfo = null },
                containerColor = BahmadSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = info.fileName,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "المصدر: ${info.sourceLabel}",
                        color = Color(0xFFB0B7C3)
                    )
                    info.note?.let { note ->
                        Text(
                            text = note,
                            color = BahmadGold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (!info.isHtmlPage && info.suggestedFormats.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            info.suggestedFormats.take(3).forEach { fmt ->
                                FilterChip(
                                    selected = selectedFormat == fmt,
                                    onClick = { selectedFormat = fmt },
                                    label = { Text(fmt) }
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (info.isHtmlPage) {
                                browserUrl = info.originalUrl
                                currentPageUrl = info.originalUrl
                                resolvedInfo = null
                                selectedTab = 2
                            } else {
                                startBackgroundDownload(info, selectedFormat)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BahmadGold,
                            contentColor = BahmadBgDark
                        )
                    ) {
                        Icon(
                            imageVector = if (info.isHtmlPage) Icons.Rounded.Public else Icons.Rounded.Download,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (info.isHtmlPage) "افتح في المتصفح الداخلي" else "ابدأ التحميل الآن",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BahmadWebBrowserView(
    url: String,
    onPageUrlChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = LinkResolver.BROWSER_USER_AGENT
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            val current = loadedUrl.orEmpty()
                            if (current.startsWith("http://") || current.startsWith("https://")) {
                                onPageUrlChanged(current)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false
                    }
                    loadUrl(url)
                }
            },
            update = { webView ->
                if (webView.url != url && url.isNotBlank()) {
                    webView.loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
