package com.bahmad.downloader

import android.annotation.SuppressLint
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.bahmad.downloader.engine.LinkResolver
import com.bahmad.downloader.engine.ResolvedLinkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BahmadBgDark = Color(0xFF0E1116)
private val BahmadSurface = Color(0xFF171C24)
private val BahmadGold = Color(0xFFE5B84B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val resolver = remember { LinkResolver() }

    var urlInput by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var resolvedInfo by remember { mutableStateOf<ResolvedLinkInfo?>(null) }
    var selectedFormat by remember { mutableStateOf("MP4 (ملف مباشر)") }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var capturedMediaUrl by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

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
                            browserUrl = if (urlInput.startsWith("http")) urlInput.trim() else "https://www.dailymotion.com"
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
                                    statusMessage = "جاري فحص الرابط..."
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            resolver.resolve(target)
                                        }
                                        isChecking = false
                                        resolvedInfo = result
                                        selectedFormat = result.suggestedFormats.firstOrNull() ?: "MP4"
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
                                browserUrl = if (target.startsWith("http")) target else "https://$target"
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
                    Text(
                        text = "ستظهر المهام الجديدة هنا عند بدء تحميل ملف مباشر.",
                        color = Color(0xFFB0B7C3)
                    )
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
                            capturedMediaUrl = null
                            selectedTab = 0
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "إغلاق")
                        }
                    }

                    capturedMediaUrl?.let { mediaUrl ->
                        Button(
                            onClick = {
                                enqueueDirectDownload(
                                    context = context,
                                    url = mediaUrl,
                                    fileName = "bahmad_captured_${System.currentTimeMillis()}.mp4",
                                    format = "MP4 (ملتقط)"
                                )
                                selectedTab = 1
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BahmadGold,
                                contentColor = BahmadBgDark
                            )
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تحميل الوسائط الملتقطة", fontWeight = FontWeight.Bold)
                        }
                    }

                    BahmadWebBrowserView(
                        url = browserUrl ?: "https://www.dailymotion.com",
                        onMediaDetected = { detected ->
                            capturedMediaUrl = detected
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
                            info.suggestedFormats.forEach { fmt ->
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
                                capturedMediaUrl = null
                                resolvedInfo = null
                                selectedTab = 2
                            } else {
                                enqueueDirectDownload(
                                    context = context,
                                    url = info.directDownloadUrl,
                                    fileName = info.fileName,
                                    format = selectedFormat
                                )
                                resolvedInfo = null
                                selectedTab = 1
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
    onMediaDetected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onLoadResource(view: WebView?, resUrl: String?) {
                            val candidate = resUrl.orEmpty()
                            val lower = candidate.lowercase()
                            if (
                                lower.contains(".mp4") ||
                                lower.contains(".m3u8") ||
                                lower.contains(".mp3") ||
                                lower.contains("videoplayback")
                            ) {
                                onMediaDetected(candidate)
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

private fun enqueueDirectDownload(
    context: Context,
    url: String,
    fileName: String,
    format: String
) {
    // إذا كان لديك BahmadDownloadManager أو DownloadForegroundService في مشروعك،
    // اربطهما هنا حسب أسماء الدوال الموجودة في ملفاتهما.
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
