package com.monolith.feature.explorer.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object DownloaderEngine {

    private val engineMutex = Mutex()

    private val _engineState = MutableStateFlow(YtDlpEngineState())
    val engineState: StateFlow<YtDlpEngineState> = _engineState.asStateFlow()

    @Volatile
    private var hasAutoCheckedThisSession: Boolean = false

    suspend fun ensureInitialized(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        engineMutex.withLock {
            if (_engineState.value.isInitialized) {
                return@withLock Result.success(Unit)
            }
            _engineState.update {
                it.copy(
                    isInitializing = true,
                    errorMessage = null,
                )
            }
            try {
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
                val ver = runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull()
                val verName = runCatching { YoutubeDL.getInstance().versionName(appContext) }.getOrNull()
                _engineState.update {
                    it.copy(
                        isInitialized = true,
                        isInitializing = false,
                        version = ver,
                        versionName = verName,
                        errorMessage = null,
                    )
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                _engineState.update { it.copy(isInitializing = false) }
                throw e
            } catch (t: Throwable) {
                _engineState.update {
                    it.copy(
                        isInitialized = false,
                        isInitializing = false,
                        errorMessage = t.message ?: "yt-dlp konnte nicht initialisiert werden.",
                    )
                }
                Result.failure(t)
            }
        }
    }

    suspend fun updateEngine(
        context: Context,
        channel: YtDlpChannel,
        forceCheck: Boolean = true,
    ): Result<YoutubeDL.UpdateStatus?> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val initResult = ensureInitialized(appContext)
        if (initResult.isFailure) {
            return@withContext Result.failure(
                initResult.exceptionOrNull() ?: IllegalStateException("Engine nicht initialisiert."),
            )
        }

        engineMutex.withLock {
            if (!forceCheck && hasAutoCheckedThisSession) {
                return@withLock Result.success(null)
            }
            hasAutoCheckedThisSession = true
            _engineState.update {
                it.copy(
                    isUpdating = true,
                    activeChannel = channel,
                    errorMessage = null,
                    lastUpdateMessage = null,
                )
            }
            try {
                val nativeChannel = when (channel) {
                    YtDlpChannel.NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
                    YtDlpChannel.STABLE -> YoutubeDL.UpdateChannel.STABLE
                    YtDlpChannel.MASTER -> YoutubeDL.UpdateChannel.MASTER
                }
                val status: YoutubeDL.UpdateStatus? =
                    YoutubeDL.getInstance().updateYoutubeDL(appContext, nativeChannel)
                val ver = runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull()
                val verName = runCatching { YoutubeDL.getInstance().versionName(appContext) }.getOrNull()
                val statusMessage = when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp (${channel.label}) aktualisiert."
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp (${channel.label}) ist bereits aktuell."
                    null -> "Update-Prüfung abgeschlossen."
                }
                _engineState.update {
                    it.copy(
                        isUpdating = false,
                        version = ver ?: it.version,
                        versionName = verName ?: it.versionName,
                        activeChannel = channel,
                        lastUpdateMessage = statusMessage,
                        errorMessage = null,
                    )
                }
                Result.success(status)
            } catch (e: CancellationException) {
                _engineState.update { it.copy(isUpdating = false) }
                throw e
            } catch (t: Throwable) {
                val errMsg = t.message?.takeIf { it.isNotBlank() }
                    ?: "Update fehlgeschlagen."
                _engineState.update {
                    it.copy(
                        isUpdating = false,
                        lastUpdateMessage = if (forceCheck) "Update fehlgeschlagen: $errMsg" else it.lastUpdateMessage,
                        errorMessage = if (forceCheck) errMsg else it.errorMessage,
                    )
                }
                Result.failure(t)
            }
        }
    }

    fun buildRequest(url: String, built: BuiltYtDlpCommand): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        for ((option, argument) in built.options) {
            if (argument != null) {
                request.addOption(option, argument)
            } else {
                request.addOption(option)
            }
        }
        if (built.extraTokens.isNotEmpty()) {
            request.addCommands(built.extraTokens)
        }
        return request
    }
}
