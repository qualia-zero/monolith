package com.monolith.core.vfs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class TransferCompletionEvent(
    val destinationDir: String,
    val isMove: Boolean,
    val count: Int,
    val errorMessage: String? = null,
)

class FileTransferService : Service() {

    private data class TransferJob(
        val sources: List<String>,
        val destinationDir: String,
        val isMove: Boolean,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobQueue = Channel<TransferJob>(Channel.UNLIMITED)
    private val pendingJobs = AtomicInteger(0)
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()

        serviceScope.launch {
            for (job in jobQueue) {
                executeJob(job)
                if (pendingJobs.decrementAndGet() <= 0) {
                    ServiceCompat.stopForeground(this@FileTransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sources = intent?.getStringArrayListExtra(EXTRA_SOURCES).orEmpty()
        val destinationDir = intent?.getStringExtra(EXTRA_DESTINATION).orEmpty()
        val isMove = intent?.getBooleanExtra(EXTRA_IS_MOVE, false) ?: false

        if (sources.isEmpty() || destinationDir.isBlank()) {
            if (pendingJobs.get() <= 0) stopSelf()
            return START_NOT_STICKY
        }

        pendingJobs.incrementAndGet()

        val initialNotification = buildProgressNotification(
            title = if (isMove) "Dateien verschieben…" else "Dateien kopieren…",
            text = "${sources.size} Element(e) vorbereitet",
            progress = 0,
            max = sources.size.coerceAtLeast(1),
            indeterminate = true,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                PROGRESS_NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, initialNotification)
        }

        jobQueue.trySend(TransferJob(sources, destinationDir, isMove))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun executeJob(job: TransferJob) {
        val localProvider = LocalVfsProvider()
        val driveProvider = GoogleDriveVfsProvider(applicationContext)
        val total = job.sources.size.coerceAtLeast(1)
        val title = if (job.isMove) "Dateien verschieben" else "Dateien kopieren"

        val result = runCatching {
            job.sources.forEachIndexed { index, src ->
                val displayName = resolveDisplayName(src, driveProvider)
                notificationManager.notify(
                    PROGRESS_NOTIFICATION_ID,
                    buildProgressNotification(
                        title = title,
                        text = "${index + 1}/$total · $displayName",
                        progress = index,
                        max = total,
                        indeterminate = total == 1,
                    ),
                )

                val srcIsDrive = src.startsWith("gdrive://")
                val dstIsDrive = job.destinationDir.startsWith("gdrive://")
                val singleList = listOf(src)

                when {
                    srcIsDrive && dstIsDrive -> {
                        if (job.isMove) driveProvider.move(singleList, job.destinationDir).getOrThrow()
                        else driveProvider.copy(singleList, job.destinationDir).getOrThrow()
                    }
                    !srcIsDrive && !dstIsDrive -> {
                        if (job.isMove) localProvider.move(singleList, job.destinationDir).getOrThrow()
                        else localProvider.copy(singleList, job.destinationDir).getOrThrow()
                    }
                    !srcIsDrive && dstIsDrive -> {
                        driveProvider.copy(singleList, job.destinationDir).getOrThrow()
                        if (job.isMove) {
                            localProvider.delete(singleList).getOrThrow()
                        }
                    }
                    else -> {
                        val info = driveProvider.stat(src)
                            ?: throw IllegalStateException("Quelle nicht gefunden")
                        if (info.isDirectory) {
                            throw IllegalStateException("Ordner-Download von Drive wird noch nicht unterstützt")
                        }
                        val dest = File(job.destinationDir, info.name)
                        driveProvider.downloadToFile(src, dest).getOrThrow()
                        if (job.isMove) {
                            driveProvider.delete(singleList).getOrThrow()
                        }
                    }
                }
            }
        }

        val error = result.exceptionOrNull()
        val resultTitle = when {
            error != null -> if (job.isMove) "Verschieben fehlgeschlagen" else "Kopieren fehlgeschlagen"
            job.isMove -> "Verschieben abgeschlossen"
            else -> "Kopieren abgeschlossen"
        }
        val resultText = error?.message ?: "${job.sources.size} Element(e) verarbeitet"
        notificationManager.notify(
            RESULT_NOTIFICATION_ID,
            buildResultNotification(resultTitle, resultText),
        )

        _events.tryEmit(
            TransferCompletionEvent(
                destinationDir = job.destinationDir,
                isMove = job.isMove,
                count = job.sources.size,
                errorMessage = error?.message,
            ),
        )
    }

    private suspend fun resolveDisplayName(path: String, driveProvider: GoogleDriveVfsProvider): String {
        if (!path.startsWith("gdrive://")) {
            return File(path).name.ifBlank { path }
        }
        return runCatching { driveProvider.stat(path)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: path.removePrefix("gdrive://")
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dateiübertragungen",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Fortschritt beim Kopieren und Verschieben von Dateien"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(
        title: String,
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, indeterminate)
            .setContentIntent(createLaunchPendingIntent())
            .build()
    }

    private fun buildResultNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(createLaunchPendingIntent())
            .build()
    }

    private fun createLaunchPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "monolith_file_transfer"
        private const val PROGRESS_NOTIFICATION_ID = 20041
        private const val RESULT_NOTIFICATION_ID = 20042

        private const val EXTRA_SOURCES = "extra_sources"
        private const val EXTRA_DESTINATION = "extra_destination"
        private const val EXTRA_IS_MOVE = "extra_is_move"

        private val _events = MutableSharedFlow<TransferCompletionEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<TransferCompletionEvent> = _events.asSharedFlow()

        fun enqueue(
            context: Context,
            sources: List<String>,
            destinationDir: String,
            isMove: Boolean,
        ) {
            if (sources.isEmpty() || destinationDir.isBlank()) return
            val intent = Intent(context, FileTransferService::class.java).apply {
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources))
                putExtra(EXTRA_DESTINATION, destinationDir)
                putExtra(EXTRA_IS_MOVE, isMove)
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }
    }
}
