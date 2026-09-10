package com.sheldondesousa.uncork.model

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface ModelDownloadEvent {
    data class Progress(val fraction: Float) : ModelDownloadEvent
    data object Verifying : ModelDownloadEvent
    data object Ready : ModelDownloadEvent
}

class ModelDownloadException(message: String) : Exception(message)

class ModelFileManager(context: Context) {
    private val modelDirectory = File(context.filesDir, "models")
    val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val partialFile = File(modelDirectory, "$MODEL_FILE_NAME.part")
    private val verificationFile = File(modelDirectory, "$MODEL_FILE_NAME.sha256")

    fun isModelReady(): Boolean =
        modelFile.isFile &&
            modelFile.length() == EXPECTED_SIZE_BYTES &&
            verificationFile.readTextOrNull() == EXPECTED_SHA256

    fun download(accessToken: String): Flow<ModelDownloadEvent> = flow {
        if (accessToken.isBlank()) {
            throw ModelDownloadException("Enter a Hugging Face access token to download the model.")
        }

        modelDirectory.mkdirs()
        ensureStorageAvailable()

        if (isModelReady()) {
            emit(ModelDownloadEvent.Ready)
            return@flow
        }

        if (modelFile.exists()) modelFile.delete()
        verificationFile.delete()

        val existingBytes = partialFile.length().coerceAtMost(EXPECTED_SIZE_BYTES)
        emit(ModelDownloadEvent.Progress(existingBytes.toFloat() / EXPECTED_SIZE_BYTES))
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
            setRequestProperty("Accept", "application/octet-stream")
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                throw ModelDownloadException(
                    "Hugging Face rejected that token. Confirm it has read access and try again.",
                )
            }
            if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw ModelDownloadException("Model download failed with HTTP $responseCode.")
            }

            val shouldAppend = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            val startingBytes = if (shouldAppend) existingBytes else 0L
            if (!shouldAppend && partialFile.exists()) partialFile.delete()

            connection.inputStream.buffered(DOWNLOAD_BUFFER_BYTES).use { input ->
                FileOutputStream(partialFile, shouldAppend).buffered(DOWNLOAD_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var downloadedBytes = startingBytes
                    var lastReportedPercent = -1

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count

                        val percent = ((downloadedBytes * 100L) / EXPECTED_SIZE_BYTES).toInt()
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            emit(
                                ModelDownloadEvent.Progress(
                                    (downloadedBytes.toFloat() / EXPECTED_SIZE_BYTES).coerceIn(0f, 1f),
                                ),
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (partialFile.length() != EXPECTED_SIZE_BYTES) {
            throw ModelDownloadException(
                "The download is incomplete. It will resume from ${partialFile.length()} bytes when you retry.",
            )
        }

        emit(ModelDownloadEvent.Verifying)
        val actualSha256 = partialFile.sha256()
        if (!actualSha256.equals(EXPECTED_SHA256, ignoreCase = true)) {
            partialFile.delete()
            throw ModelDownloadException("The downloaded model failed its integrity check. Please retry.")
        }

        if (!partialFile.renameTo(modelFile)) {
            throw ModelDownloadException("The verified model could not be installed in app storage.")
        }
        verificationFile.writeText(EXPECTED_SHA256)
        emit(ModelDownloadEvent.Ready)
    }.flowOn(Dispatchers.IO)

    private fun ensureStorageAvailable() {
        val bytesRemaining = EXPECTED_SIZE_BYTES - partialFile.length()
        val availableBytes = StatFs(modelDirectory.parentFile?.absolutePath).availableBytes
        if (availableBytes < bytesRemaining + STORAGE_SAFETY_MARGIN_BYTES) {
            throw ModelDownloadException(
                "Not enough device storage. Free at least ${formatGigabytes(bytesRemaining + STORAGE_SAFETY_MARGIN_BYTES)} GB and retry.",
            )
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered(HASH_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    private fun formatGigabytes(bytes: Long): String = "%.1f".format(bytes / 1_000_000_000.0)

    companion object {
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val EXPECTED_SIZE_BYTES = 2_583_085_056L
        const val EXPECTED_SHA256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"

        private const val MODEL_REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$MODEL_REVISION/$MODEL_FILE_NAME"
        private const val DOWNLOAD_BUFFER_BYTES = 1024 * 1024
        private const val HASH_BUFFER_BYTES = 8 * 1024 * 1024
        private const val STORAGE_SAFETY_MARGIN_BYTES = 300_000_000L
    }
}
