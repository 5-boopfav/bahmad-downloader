package com.bahmad.downloader.engine

import android.content.Context
import java.io.File
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
            return try {
                val appContext = context.applicationContext
                val workDir = File(appContext.cacheDir, "bahmad_engine")
                val outputStagingDir = File(appContext.filesDir, "bahmad_staging")
                workDir.mkdirs()
                outputStagingDir.mkdirs()

                val ready = workDir.exists() && workDir.canWrite() &&
                    outputStagingDir.exists() && outputStagingDir.canWrite()

                lastStatusMessage = if (ready) {
                    "تمت تهيئة مسارات ومحرك التحميل بنجاح"
                } else {
                    "تعذر الوصول إلى مسارات العمل الداخلية"
                }
                initialized.set(ready)
                ready
            } catch (e: Exception) {
                lastStatusMessage = "فشل تهيئة المحرك: ${e.message ?: "خطأ غير معروف"}"
                false
            }
        }
    }
}
