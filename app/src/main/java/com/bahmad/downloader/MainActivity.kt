Button(
    onClick = {
        if (info.isHtmlPage) {
            browserUrl = info.originalUrl
            resolvedInfo = null
        } else {
            val taskId = BahmadDownloadManager.enqueue(context, info, selectedFormat)
            DownloadForegroundService.startTask(context, taskId)
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
