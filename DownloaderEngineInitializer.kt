package com.bahmad.downloader.engine

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.atomic.AtomicBoolean

object DownloaderEngineInitializer {

    private val initialized = AtomicBoolean(false)

    @Volatile
    var lastStatusMessage: String = "محرك التحميل جاهز للتهيئة"
        private set

    fun ensureInitialized(context: Context): Boolean {
        if (initialized.get()) return true

        synchronized(this) {
            if (initialized.get()) return true
            val appContext = context.applicationContext
            return try {
                YoutubeDL.getInstance().init(appContext)
                try {
                    FFmpeg.getInstance().init(appContext)
                    lastStatusMessage = "تمت تهيئة YoutubeDL و FFmpeg بنجاح"
                } catch (_: Throwable) {
                    lastStatusMessage = "تمت تهيئة YoutubeDL (FFmpeg غير متاح في هذه النسخة)"
                }
                initialized.set(true)
                true
            } catch (t: Throwable) {
                lastStatusMessage = "تعذرت تهيئة محرك الاستخراج: ${t.message ?: "خطأ غير معروف"}"
                false
            }
        }
    }
}
