package com.bahmad.downloader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.bahmad.downloader.engine.*
import com.bahmad.downloader.service.DownloadForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val BahmadBgDark = Color(0xFF0D0F13)
private val BahmadCardDark = Color(0xFF161A21)
private val BahmadGold = Color(0xFFD9AE38)
private val BahmadGoldLight = Color(0xFFF6D776)
private val BahmadTextWhite = Color(0xFFF8F9FA)
private val BahmadTextMuted = Color(0xFF9BA3B4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = BahmadGold,
                    secondary = BahmadGoldLight,
                    background = BahmadBgDark,
                    surface = BahmadCardDark,
                    onPrimary = BahmadBgDark,
                    onBackground = BahmadTextWhite,
                    onSurface = BahmadTextWhite
                )
            ) {
                BahmadMainScreen(initialSharedUrl = sharedText)
            }
        }
    }
}

private fun isVideoPageUrl(url: String): Boolean {
    val lower = url.trim().lowercase()
    val videoHosts = listOf(
        "dailymotion.com", "dai.ly",
        "youtube.com", "youtu.be",
        "tiktok.com",
        "instagram.com",
        "facebook.com", "fb.watch",
        "twitter.com", "x.com",
        "vimeo.com", "twitch.tv"
    )
    return videoHosts.any { lower.contains(it) }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "غير معروف مسبقاً"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format("%.1f MB", mb) else "${bytes / 1024L} KB"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BahmadMainScreen(initialSharedUrl: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = remember { LinkResolver() }

    var urlInput by remember { mutableStateOf(initialSharedUrl ?: "") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var isResolving by remember { mutableStateOf(false) }
    var resolvedInfo by remember { mutableStateOf<ResolvedLinkInfo?>(null) }
    var selectedFormat by remember { mutableStateOf("أعلى سرعة (مباشر)") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var browserUrl by remember { mutableStateOf<String?>(null) }

    val tasks by BahmadDownloadManager.tasks.collectAsState()

    val quickPlatforms = listOf(
        "Dailymotion", "Google Drive", "YouTube", "TikTok",
        "Instagram", "X / Twitter", "Facebook", "ملفات مباشرة"
    )

    fun analyzeAndOpenSheet(targetUrl: String) {
        val trimmed = targetUrl.trim()
        if (trimmed.isEmpty()) {
            statusMessage = "الصق رابطاً أولاً لبدء التحميل"
            return
        }

        if (isVideoPageUrl(trimmed)) {
            statusMessage = null
            browserUrl = trimmed
            return
        }

        isResolving = true
        statusMessage = null
        scope.launch {
            val info = withContext(Dispatchers.IO) { resolver.resolve(trimmed) }
            isResolving = false
            resolvedInfo = info
            selectedFormat = info.suggestedFormats.firstOrNull() ?: "أعلى سرعة (مباشر)"
        }
    }

    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            analyzeAndOpenSheet(initialSharedUrl)
        }
    }

    if (browserUrl != null) {
        BahmadWebBrowserView(
            initialUrl = browserUrl!!,
            onClose = { browserUrl = null },
            onDownloadCurrentUrl = { targetMediaOrPageUrl ->
                browserUrl = null
                isResolving = true
                scope.launch {
                    val info = withContext(Dispatchers.IO) { resolver.resolve(targetMediaOrPageUrl) }
                    isResolving = false
                    resolvedInfo = info
                    selectedFormat = info.suggestedFormats.firstOrNull() ?: "أعلى سرعة (مباشر)"
                }
            }
        )
        return
    }

    Scaffold(
        containerColor = BahmadBgDark,
        topBar = {
            Surface(
                color = BahmadCardDark,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_bahmad_logo),
                            contentDescription = "شعار بحمد",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "بحمد",
                                color = BahmadGoldLight,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "متصفح داخلي + محمل فائق السرعة",
                                color = BahmadTextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Surface(
                        color = BahmadGold.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BahmadGold.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = BahmadGoldLight,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "سرعة قصوى",
                                color = BahmadGoldLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = BahmadCardDark,
                contentColor = BahmadGold
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Rounded.DownloadForOffline, contentDescription = null) },
                    label = { Text("الرئيسية") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BahmadBgDark,
                        selectedTextColor = BahmadGoldLight,
                        indicatorColor = BahmadGold,
                        unselectedIconColor = BahmadTextMuted,
                        unselectedTextColor = BahmadTextMuted
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (tasks.isNotEmpty()) {
                                    Badge(containerColor = BahmadGold) {
                                        Text("${tasks.size}", color = BahmadBgDark)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.FolderZip, contentDescription = null)
                        }
                    },
                    label = { Text("التحميلات") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BahmadBgDark,
                        selectedTextColor = BahmadGoldLight,
                        indicatorColor = BahmadGold,
                        unselectedIconColor = BahmadTextMuted,
                        unselectedTextColor = BahmadTextMuted
                    )
                )
            }
        }
    ) { innerPadding ->
        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BahmadCardDark),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, BahmadGold.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "الصق رابط الفيديو، المنصة، قوقل، أو الملف",
                                color = BahmadTextWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )

                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        "https://...",
                                        color = BahmadTextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                trailingIcon = {
                                    Row {
                                        if (urlInput.isNotEmpty()) {
                                            IconButton(onClick = { urlInput = "" }) {
                                                Icon(
                                                    Icons.Rounded.Close,
                                                    contentDescription = "مسح",
                                                    tint = BahmadTextMuted
                                                )
                                            }
                                        }
                                        IconButton(onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                            if (!clip.isNullOrBlank()) {
                                                urlInput = clip
                                                analyzeAndOpenSheet(clip)
                                            } else {
                                                statusMessage = "الحافظة فارغة"
                                            }
                                        }) {
                                            Icon(
                                                Icons.Rounded.ContentPaste,
                                                contentDescription = "لصق",
                                                tint = BahmadGoldLight
                                            )
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BahmadGold,
                                    unfocusedBorderColor = Color(0xFF2B313D),
                                    focusedTextColor = BahmadTextWhite,
                                    unfocusedTextColor = BahmadTextWhite
                                )
                            )

                            Button(
                                onClick = { analyzeAndOpenSheet(urlInput) },
                                enabled = !isResolving,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BahmadGold,
                                    contentColor = BahmadBgDark
                                )
                            ) {
                                if (isResolving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = BahmadBgDark,
                                        strokeWidth = 2.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("جاري فحص الرابط...", fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Rounded.RocketLaunch, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("فحص أو فتح في المتصفح", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }

                            if (urlInput.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { browserUrl = urlInput.trim() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, BahmadGold.copy(alpha = 0.6f))
                                ) {
                                    Icon(
                                        Icons.Rounded.Public,
                                        contentDescription = null,
                                        tint = BahmadGoldLight
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "فتح الرابط في المتصفح الداخلي",
                                        color = BahmadGoldLight,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            statusMessage?.let {
                                Text(it, color = BahmadGoldLight, fontSize = 12.sp)
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "المنصات والروابط المدعومة",
                        color = BahmadTextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(quickPlatforms) { platform ->
                            Surface(
                                color = BahmadCardDark,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color(0xFF262C38)),
                                modifier = Modifier.clickable {
                                    statusMessage = "تم اختيار ($platform) — الصق الرابط أعلاه للفتح أو التحميل"
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.linearGradient(listOf(BahmadGoldLight, BahmadGold))
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = platform,
                                        color = BahmadTextWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BahmadCardDark),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, Color(0xFF252B37))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "كيف يعمل بحمد الآن؟",
                                color = BahmadGoldLight,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("• روابط الفيديو والمواقع تفتح داخل المتصفح الداخلي وتلتقط روابط الوسائط أثناء التشغيل.", color = BahmadTextMuted, fontSize = 12.sp)
                            Text("• يفحص التطبيق نوع المحتوى (ملف مباشر vs صفحة ويب) قبل التحميل.", color = BahmadTextMuted, fontSize = 12.sp)
                            Text("• استمرار التحميل في الخلفية مع إشعارات فورية.", color = BahmadTextMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("لا توجد تحميلات بعد — الصق رابطاً للبدء", color = BahmadTextMuted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        val engine = remember { FastDownloadEngine() }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BahmadCardDark),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, BahmadGold.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = task.title,
                                        color = BahmadTextWhite,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = task.source,
                                        color = BahmadGoldLight,
                                        fontSize = 11.sp
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = { task.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    color = BahmadGold,
                                    trackColor = Color(0xFF262C38)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = when (task.status) {
                                            DownloadStatus.COMPLETED -> "مكتمل (${formatFileSize(task.downloadedBytes)}) • ${task.formatLabel}"
                                            DownloadStatus.PAUSED -> "متوقف مؤقتاً"
                                            DownloadStatus.FAILED -> "فشل التحميل"
                                            else -> "${(task.progress * 100).toInt()}% • ${engine.formatSpeed(task.speedBytesPerSec)}"
                                        },
                                        color = BahmadTextMuted,
                                        fontSize = 12.sp
                                    )

                                    Row {
                                        if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED) {
                                            IconButton(onClick = {
                                                val wasPaused = task.status == DownloadStatus.PAUSED
                                                BahmadDownloadManager.togglePause(task.id)
                                                if (wasPaused) {
                                                    DownloadForegroundService.startTask(context, task.id)
                                                }
                                            }) {
                                                Icon(
                                                    imageVector = if (task.status == DownloadStatus.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                                    contentDescription = null,
                                                    tint = BahmadGoldLight
                                                )
                                            }
                                        }
                                        if (task.status == DownloadStatus.COMPLETED) {
                                            IconButton(onClick = {
                                                val file = File(task.filePath)
                                                if (file.exists()) {
                                                    val uri = FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        file
                                                    )
                                                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, "*/*")
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(Intent.createChooser(openIntent, "فتح الملف عبر"))
                                                }
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.OpenInNew,
                                                    contentDescription = "فتح",
                                                    tint = BahmadGoldLight
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        resolvedInfo?.let { info ->
            ModalBottomSheet(
                onDismissRequest = { resolvedInfo = null },
                containerColor = BahmadCardDark,
                contentColor = BahmadTextWhite
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = info.fileName,
                        color = BahmadGoldLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "المصدر: ${info.sourceLabel} • الحجم: ${formatFileSize(info.fileSizeBytes)}",
                        color = BahmadTextMuted,
                        fontSize = 12.sp
                    )

                    info.note?.let { noteText ->
                        Surface(
                            color = BahmadGold.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BahmadGold.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = noteText,
                                color = BahmadGoldLight,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(info.suggestedFormats) { fmt ->
                            FilterChip(
                                selected = selectedFormat == fmt,
                                onClick = { selectedFormat = fmt },
                                label = { Text(fmt) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BahmadGold,
                                    selectedLabelColor = BahmadBgDark
                                )
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val taskId = BahmadDownloadManager.enqueue(context, info, selectedFormat)
                            DownloadForegroundService.startTask(context, taskId)
                            resolvedInfo = null
                            selectedTab = 1
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
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ابدأ التحميل الآن", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun BahmadWebBrowserView(
    initialUrl: String,
    onClose: () -> Unit,
    onDownloadCurrentUrl: (String) -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("متصفح بحمد") }
    var detectedMediaUrl by remember { mutableStateOf<String?>(null) }

    Surface(
        color = BahmadBgDark,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = BahmadCardDark,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.ArrowForward, contentDescription = "رجوع", tint = BahmadTextWhite)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = pageTitle,
                                color = BahmadGoldLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentUrl,
                                color = BahmadTextMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = { onDownloadCurrentUrl(detectedMediaUrl ?: currentUrl) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BahmadGold,
                                contentColor = BahmadBgDark
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (detectedMediaUrl != null) "تحميل الوسائط" else "تحميل",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    detectedMediaUrl?.let { media ->
                        Text(
                            text = "تم التقاط رابط وسائط: $media",
                            color = BahmadGoldLight,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                if (!title.isNullOrBlank()) {
                                    pageTitle = title
                                }
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                }
                            }

                            override fun onLoadResource(view: WebView?, resUrl: String?) {
                                super.onLoadResource(view, resUrl)
                                if (!resUrl.isNullOrBlank()) {
                                    val lower = resUrl.lowercase()
                                    if (
                                        lower.contains(".mp4") ||
                                        lower.contains(".m3u8") ||
                                        lower.contains(".mp3") ||
                                        lower.contains(".m4a") ||
                                        lower.contains(".webm")
                                    ) {
                                        detectedMediaUrl = resUrl
                                    }
                                }
                            }
                        }
                        loadUrl(initialUrl)
                    }
                }
            )
        }
    }
}
