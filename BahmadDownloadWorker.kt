package com.bahmad.downloader.engine

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters

class BahmadDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_FORMAT = "format"
        const val KEY_RESULT_MESSAGE = "result_message"
    }

    override fun doWork(): Result {
        val url = inputData.getString(KEY_URL).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME).orEmpty().ifBlank {
            "bahmad_media_${System.currentTimeMillis()}.mp4"
        }
        val format = inputData.getString(KEY_FORMAT).orEmpty().ifBlank { "MP4 (أفضل جودة)" }

        val outcome = BahmadMediaDownloader.downloadAndSaveNow(
            context = applicationContext,
            url = url,
            rawFileName = fileName,
            selectedFormat = format
        )

        val output = Data.Builder()
            .putString(KEY_RESULT_MESSAGE, outcome.message)
            .build()

        return if (outcome.success) {
            Result.success(output)
        } else {
            Result.failure(output)
        }
    }
}
