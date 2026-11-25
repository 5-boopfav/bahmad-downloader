package com.bahmad.downloader

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BahmadBgDark = Color(0xFF0E1116)
private val BahmadSurface = Color(0xFF171C24)
private val BahmadGold = Color(0xFFE5B84B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloaderEngineInitializer.ensureInitialized(this)
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
                    BahmadMainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BahmadMainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var resolvedInfo by remember { mutableStateOf<ResolvedLinkInfo?>(null) }
    var selectedFormat by remember { mutableStateOf("MP4 (أفضل جودة)") }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var currentPageUrl by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val downloadLogs = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DownloaderEngineInitializer.ensureInitialized(context)
        }
    }

    fun startBackgroundDownload(info: ResolvedLinkInfo, format: String) {
        resolvedInfo = null
        selectedTab = 1
        val workId = BahmadMediaDownloader.enqueueBackgroundDownload(context, info, format)
        val logIndex = downloadLogs.size
        downloadLogs.add("قيد التنزيل في الخلفية: ${info.fileName} ($format)")
        statusMessage = "تمت جدولة التحميل في الخلفية: ${info.fileName}"

        scope.launch(Dispatchers.IO) {
            val wm = WorkManager.getInstance(context.applicationContext)
            repeat(180) {
                val infoList = try {
                    wm.getWorkInfosForUniqueWork("bahmad_dl_$workId").get()
                } catch (_: Exception) {
                    emptyList<WorkInfo>()
                }
                val current = infoList.firstOrNull()
                if (current != null && (current.state == WorkInfo.State.SUCCEEDED || current.state == WorkInfo.State.FAILED)) {
                    val msg = current.outputData.getString(BahmadDownloadWorker.KEY_RESULT_MESSAGE)
                        ?: if (current.state == WorkInfo.State.SUCCEEDED) {
                            "تم حفظ الملف في مجلد التنزيلات"
                        } else {
                            "فشل تنزيل الملف"
                        }
                    withContext(Dispatchers.Main) {
                        statusMessage = msg
                        if (logIndex < downloadLogs.size) {
                            downloadLogs[logIndex] = if (current.state == WorkInfo.State.SUCCEEDED) {
                                "مكتمل: $msg"
                            } else {
                                "فشل: $msg"
                            }
                        }
                    }
                    return@launch
                }
                delay(1000L)
            }
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
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val target = urlInput.trim()
                                if (target.isNotEmpty() && !isChecking) {
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
                            },
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
                    Text(
                        text = "قائمة التحميلات",
                        fontWeight = FontWeight.Bold,
                        color = BahmadGold
                    )
                    if (downloadLogs.isEmpty()) {
                        Text(
                            text = "يتم جدولة التنزيل في الخلفية وحفظ الملفات في مجلد التنزيلات (Downloads).",
                            color = Color(0xFFB0B7C3)
                        )
                    } else {
                        downloadLogs.forEach { entry ->
                            Text(
                                text = entry,
                                color = Color(0xFFE0E6F0),
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                            onClick = {
                                if (!isChecking) {
                                    isChecking = true
                                    statusMessage = "جاري استخراج الفيديو من الصفحة الحالية..."
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            DownloaderEngineInitializer.ensureInitialized(context)
                                            LinkResolver.resolve(activePage)
                                        }
                                        isChecking = false
                                        resolvedInfo = result
                                        selectedFormat = result.suggestedFormats.firstOrNull() ?: "MP4 (أفضل جودة)"
                                    }
                                }
                            },
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
