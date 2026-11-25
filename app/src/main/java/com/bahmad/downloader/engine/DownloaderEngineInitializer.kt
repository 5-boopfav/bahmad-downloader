package com.bahmad.downloader.engine

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object DownloaderEngineInitializer {
    private const val TAG = "BahmadEngineInit"
    private val initialized = AtomicBoolean(false)
    private val lastUpdateAttemptMs = AtomicLong(0L)

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized.get()) return
        val appContext = context.applicationContext
        try {
            YoutubeDL.getInstance().init(appContext)
            try {
                FFmpeg.getInstance().init(appContext)
            } catch (ffmpegErr: Throwable) {
                Log.w(TAG, "FFmpeg init warning: ${ffmpegErr.message}")
            }
            initialized.set(true)
        } catch (err: Throwable) {
            Log.e(TAG, "Failed to initialize YoutubeDL engine: ${err.message}", err)
        }
    }

    fun updateYtDlpIfNeeded(context: Context, force: Boolean = false) {
        ensureInitialized(context)
        val now = System.currentTimeMillis()
        val elapsed = now - lastUpdateAttemptMs.get()
        if (!force && elapsed < 12 * 60 * 60 * 1000L) return

        lastUpdateAttemptMs.set(now)
        try {
            YoutubeDL.getInstance().updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.STABLE
            )
        } catch (err: Throwable) {
            Log.w(TAG, "yt-dlp update check failed: ${err.message}")
        }
    }
}
