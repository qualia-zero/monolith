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
import com.monolith.core.model.TransferOperation
import com.monolith.core.model.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class TransferCompletionEvent(
    val destinationDir: String,
    val isMove: Boolean,
    val count: Int,
    val errorMessage: String? = null,
)

class FileTransferService : Service() {

    private data class TransferJob(
        val taskId: Long,
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
        val taskId = nextTaskId.getAndIncrement()
        val operation = if (isMove) TransferOperation.MOVE else TransferOperation.COPY
        val firstName = sources.firstOrNull()?.let {
            if (it.startsWith("gdrive://")) it.removePrefix("gdrive://") else File(it).name.ifBlank { it }
        }.orEmpty()

        _progress.value = TransferProgress(
            taskId = taskId,
            operation = operation,
            currentFileName = firstName,
            processedItems = 0,
            totalItems = sources.size.coerceAtLeast(1),
            bytesProcessed = 0L,
            totalBytes = 0L,
            bytesPerSecond = 0L,
            isFinished = false,
        )

        val initialNotification = buildProgressNotification(
            title = if (isMove) "Dateien verschieben…" else "Dateien kopieren…",
            text = "${sources.size} Element(e) vorbereitet",
            progress = 0,
            max = PROGRESS_NOTIFICATION_MAX,
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

        jobQueue.trySend(TransferJob(taskId, sources, destinationDir, isMove))
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
        val operation = if (job.isMove) TransferOperation.MOVE else TransferOperation.COPY
        val title = if (job.isMove) "Dateien verschieben" else "Dateien kopieren"

        var totalBytes = 0L
        var scannedItems = 0
        for (src in job.sources) {
            if (src.startsWith("gdrive://")) {
                val info = runCatching { driveProvider.stat(src) }.getOrNull()
                totalBytes += info?.sizeBytes ?: 0L
                scannedItems += 1
            } else {
                val (bytes, items) = scanLocalSource(File(src))
                totalBytes += bytes
                scannedItems += items
            }
        }
        val totalItems = scannedItems.coerceAtLeast(job.sources.size).coerceAtLeast(1)

        var currentFileName = resolveDisplayName(job.sources.first(), driveProvider)
        var bytesProcessed = 0L
        var processedItems = 0
        var bytesPerSecond = 0L
        val startTimeMs = System.currentTimeMillis()
        var lastNotificationUpdateMs = 0L
        var lastNotifiedFileName = ""

        fun publishProgress(forceNotification: Boolean = false) {
            _progress.value = TransferProgress(
                taskId = job.taskId,
                operation = operation,
                currentFileName = currentFileName,
                processedItems = processedItems,
                totalItems = totalItems,
                bytesProcessed = bytesProcessed,
                totalBytes = totalBytes,
                bytesPerSecond = bytesPerSecond,
                isFinished = false,
            )
            val now = System.currentTimeMillis()
            if (forceNotification ||
                now - lastNotificationUpdateMs >= NOTIFICATION_THROTTLE_MS ||
                currentFileName != lastNotifiedFileName
            ) {
                lastNotificationUpdateMs = now
                lastNotifiedFileName = currentFileName
                val scaledProgress = when {
                    totalBytes > 0L -> ((bytesProcessed.coerceIn(0L, totalBytes) * PROGRESS_NOTIFICATION_MAX) / totalBytes)
                        .toInt()
                        .coerceIn(0, PROGRESS_NOTIFICATION_MAX)
                    totalItems > 0 -> ((processedItems.coerceIn(0, totalItems) * PROGRESS_NOTIFICATION_MAX) / totalItems)
                        .coerceIn(0, PROGRESS_NOTIFICATION_MAX)
                    else -> 0
                }
                val indeterminate = totalBytes <= 0L && totalItems <= 1
                val displayIndex = (processedItems + 1).coerceAtMost(totalItems)
                notificationManager.notify(
                    PROGRESS_NOTIFICATION_ID,
                    buildProgressNotification(
                        title = title,
                        text = "$displayIndex/$totalItems · $currentFileName",
                        progress = scaledProgress,
                        max = PROGRESS_NOTIFICATION_MAX,
                        indeterminate = indeterminate,
                    ),
                )
            }
        }

        val onProgress: (VfsTransferUpdate) -> Unit = { update ->
            if (update.currentFileName.isNotBlank()) {
                currentFileName = update.currentFileName
            }
            bytesProcessed += update.bytesDelta
            processedItems += update.itemsDelta
            val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
            if (bytesProcessed > 0L) {
                bytesPerSecond = (bytesProcessed * 1000L) / elapsedMs
            }
            publishProgress()
        }

        publishProgress(forceNotification = true)

        val result = runCatching {
            job.sources.forEach { src ->
                val displayName = resolveDisplayName(src, driveProvider)
                if (displayName != currentFileName) {
                    currentFileName = displayName
                    publishProgress()
                }

                val srcIsDrive = src.startsWith("gdrive://")
                val dstIsDrive = job.destinationDir.startsWith("gdrive://")
                val singleList = listOf(src)

                when {
                    srcIsDrive && dstIsDrive -> {
                        if (job.isMove) driveProvider.move(singleList, job.destinationDir, onProgress).getOrThrow()
                        else driveProvider.copy(singleList, job.destinationDir, onProgress).getOrThrow()
                    }
                    !srcIsDrive && !dstIsDrive -> {
                        if (job.isMove) localProvider.move(singleList, job.destinationDir, onProgress).getOrThrow()
                        else localProvider.copy(singleList, job.destinationDir, onProgress).getOrThrow()
                    }
                    !srcIsDrive && dstIsDrive -> {
                        driveProvider.copy(singleList, job.destinationDir, onProgress).getOrThrow()
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
                        val safeName = File(info.name).name.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "download"
                        val dest = resolveUniqueLocalTarget(File(job.destinationDir), safeName)
                        driveProvider.downloadToFile(
                            path = src,
                            destFile = dest,
                            onProgress = onProgress,
                            expectedSize = info.sizeBytes,
                        ).getOrThrow()
                        if (job.isMove) {
                            driveProvider.delete(singleList).getOrThrow()
                        }
                    }
                }
            }
        }

        val error = result.exceptionOrNull()
        if (error is CancellationException) {
            _progress.value = TransferProgress(
                taskId = job.taskId,
                operation = operation,
                currentFileName = currentFileName,
                processedItems = processedItems,
                totalItems = totalItems,
                bytesProcessed = bytesProcessed,
                totalBytes = totalBytes,
                bytesPerSecond = bytesPerSecond,
                isFinished = true,
                errorMessage = null,
            )
            throw error
        }

        _progress.value = TransferProgress(
            taskId = job.taskId,
            operation = operation,
            currentFileName = currentFileName,
            processedItems = if (error == null) maxOf(processedItems, totalItems) else processedItems,
            totalItems = totalItems,
            bytesProcessed = if (error == null && totalBytes > 0L) maxOf(bytesProcessed, totalBytes) else bytesProcessed,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
            isFinished = true,
            errorMessage = error?.message,
        )

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

    private fun resolveUniqueLocalTarget(targetDir: File, name: String): File {
        val direct = File(targetDir, name)
        if (!direct.exists()) return direct
        val dotIndex = name.lastIndexOf('.')
        val (base, ext) = if (dotIndex > 0) {
            name.substring(0, dotIndex) to name.substring(dotIndex)
        } else {
            name to ""
        }
        var index = 1
        while (true) {
            val candidate = File(targetDir, "$base ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun scanLocalSource(
        file: File,
        visitedDirs: MutableSet<String> = HashSet(),
    ): Pair<Long, Int> {
        if (!file.exists()) return 0L to 0
        if (!file.isDirectory) return file.length() to 1
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
        if (!visitedDirs.add(canonical)) return 0L to 0
        val children = file.listFiles()
        if (children.isNullOrEmpty()) return 0L to 1
        var bytes = 0L
        var items = 0
        for (child in children) {
            val (childBytes, childItems) = scanLocalSource(child, visitedDirs)
            bytes += childBytes
            items += childItems
        }
        return bytes to items
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
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_OPEN_TRANSFER_PANEL, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_TRANSFER_PANEL,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_OPEN_TRANSFER_PANEL = "com.monolith.core.vfs.EXTRA_OPEN_TRANSFER_PANEL"

        private const val CHANNEL_ID = "monolith_file_transfer"
        private const val PROGRESS_NOTIFICATION_ID = 20041
        private const val RESULT_NOTIFICATION_ID = 20042
        private const val REQUEST_CODE_OPEN_TRANSFER_PANEL = 20043
        private const val PROGRESS_NOTIFICATION_MAX = 1000
        private const val NOTIFICATION_THROTTLE_MS = 200L

        private const val EXTRA_SOURCES = "extra_sources"
        private const val EXTRA_DESTINATION = "extra_destination"
        private const val EXTRA_IS_MOVE = "extra_is_move"

        private val nextTaskId = AtomicLong(1L)

        private val _events = MutableSharedFlow<TransferCompletionEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<TransferCompletionEvent> = _events.asSharedFlow()

        private val _progress = MutableStateFlow<TransferProgress?>(null)
        val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

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
