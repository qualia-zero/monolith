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
import com.monolith.core.model.VfsNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
        val isCloudDownload: Boolean = false,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executionSemaphore = Semaphore(MAX_CONCURRENT_JOBS)
    private val taskJobs = ConcurrentHashMap<Long, Job>()
    private val taskTempFiles = ConcurrentHashMap<Long, MutableSet<File>>()
    private val activeProgressNotifications = ConcurrentHashMap<Long, Notification>()
    private val launchPendingIntents = ConcurrentHashMap<Long, PendingIntent>()
    @Volatile
    private var foregroundAnchorTaskId: Long? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_TRANSFER) {
            val cancelTaskId = intent.getLongExtra(EXTRA_CANCEL_TASK_ID, -1L)
            if (cancelTaskId > 0L) {
                taskJobs[cancelTaskId]?.cancel()
                val existing = _tasks.value[cancelTaskId] ?: _progress.value?.takeIf { it.taskId == cancelTaskId }
                existing?.let {
                    updateTaskProgress(it.copy(isFinished = true, errorMessage = "Abgebrochen."))
                }
                onTaskEnded(cancelTaskId)
            } else {
                val allIds = taskJobs.keys.toList()
                for (id in allIds) {
                    taskJobs[id]?.cancel()
                    _tasks.value[id]?.let {
                        updateTaskProgress(it.copy(isFinished = true, errorMessage = "Abgebrochen."))
                    }
                    onTaskEnded(id)
                }
                if (taskJobs.isEmpty()) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        val externalTaskId = intent?.getLongExtra(EXTRA_EXTERNAL_TASK_ID, -1L) ?: -1L
        if (externalTaskId > 0L) {
            val runner = pendingExternalRunners.remove(externalTaskId)
            val opName = intent?.getStringExtra(EXTRA_EXTERNAL_OPERATION).orEmpty()
            val operation = runCatching { TransferOperation.valueOf(opName) }.getOrDefault(TransferOperation.EXTRACT)
            val initialItem = intent?.getStringExtra(EXTRA_EXTERNAL_ITEM).orEmpty()
            val totalBytes = intent?.getLongExtra(EXTRA_EXTERNAL_TOTAL_BYTES, 1L) ?: 1L
            if (runner != null) {
                startExternalArchiveJob(
                    taskId = externalTaskId,
                    operation = operation,
                    initialItem = initialItem,
                    totalBytes = totalBytes,
                    runner = runner,
                )
            } else if (taskJobs.isEmpty()) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val sources = intent?.getStringArrayListExtra(EXTRA_SOURCES).orEmpty()
        val destinationDir = intent?.getStringExtra(EXTRA_DESTINATION).orEmpty()
        val isMove = intent?.getBooleanExtra(EXTRA_IS_MOVE, false) ?: false
        val isCloudDownload = intent?.getBooleanExtra(EXTRA_IS_CLOUD_DOWNLOAD, false) ?: false

        if (sources.isEmpty() || destinationDir.isBlank()) {
            if (taskJobs.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }

        val taskId = nextTaskId.getAndIncrement()
        _latestCreatedTaskId.value = taskId
        val operation = when {
            isCloudDownload -> TransferOperation.DOWNLOAD
            isMove -> TransferOperation.MOVE
            else -> TransferOperation.COPY
        }
        val firstName = sources.firstOrNull()?.let {
            when {
                it.startsWith("gdrive://") -> it.removePrefix("gdrive://")
                it.startsWith("smb://") -> it.trimEnd('/').substringAfterLast('/').ifBlank { it }
                else -> File(it).name.ifBlank { it }
            }
        }.orEmpty()
        val isInitiallyQueued = taskJobs.size >= MAX_CONCURRENT_JOBS
        val itemCount = sources.size.coerceAtLeast(1)

        updateTaskProgress(
            TransferProgress(
                taskId = taskId,
                operation = operation,
                currentFileName = if (isInitiallyQueued) "In Warteschlange…" else firstName,
                processedItems = 0,
                totalItems = itemCount,
                bytesProcessed = 0L,
                totalBytes = 0L,
                bytesPerSecond = 0L,
                isFinished = false,
            ),
        )

        val itemLabel = if (itemCount == 1) "Element" else "Elemente"
        val initialNotification = buildProgressNotification(
            taskId = taskId,
            title = when {
                isCloudDownload -> "Dateien herunterladen…"
                isMove -> "Dateien verschieben…"
                else -> "Dateien kopieren…"
            },
            text = if (isInitiallyQueued) "In Warteschlange…" else "Vorbereitung…",
            subText = "0/$itemCount $itemLabel",
            progress = 0,
            max = PROGRESS_NOTIFICATION_MAX,
            indeterminate = true,
        )
        activeProgressNotifications[taskId] = initialNotification
        if (foregroundAnchorTaskId == null) {
            foregroundAnchorTaskId = taskId
            startForegroundWithNotification(progressNotificationId(taskId), initialNotification)
        } else {
            notificationManager.notify(progressNotificationId(taskId), initialNotification)
        }

        val job = TransferJob(taskId, sources, destinationDir, isMove, isCloudDownload)
        val coroutineJob = serviceScope.launch {
            try {
                executionSemaphore.withPermit {
                    executeJob(job)
                }
            } finally {
                onTaskEnded(taskId)
            }
        }
        taskJobs[taskId] = coroutineJob
        return START_NOT_STICKY
    }

    private fun startExternalArchiveJob(
        taskId: Long,
        operation: TransferOperation,
        initialItem: String,
        totalBytes: Long,
        runner: suspend (
            updateProgress: (currentItem: String, processedBytes: Long, totalBytes: Long) -> Unit,
        ) -> Result<String>,
    ) {
        val isInitiallyQueued = taskJobs.size >= MAX_CONCURRENT_JOBS
        val runningTitle = when (operation) {
            TransferOperation.COMPRESS -> "Archiv packen…"
            else -> "Archiv entpacken…"
        }
        updateTaskProgress(
            TransferProgress(
                taskId = taskId,
                operation = operation,
                currentFileName = if (isInitiallyQueued) "In Warteschlange…" else initialItem,
                processedItems = 0,
                totalItems = 1,
                bytesProcessed = 0L,
                totalBytes = totalBytes.coerceAtLeast(1L),
                bytesPerSecond = 0L,
                isFinished = false,
            ),
        )
        val initialNotification = buildProgressNotification(
            taskId = taskId,
            title = runningTitle,
            text = if (isInitiallyQueued) "In Warteschlange…" else initialItem,
            progress = 0,
            max = PROGRESS_NOTIFICATION_MAX,
            indeterminate = true,
        )
        activeProgressNotifications[taskId] = initialNotification
        if (foregroundAnchorTaskId == null) {
            foregroundAnchorTaskId = taskId
            startForegroundWithNotification(progressNotificationId(taskId), initialNotification)
        } else {
            notificationManager.notify(progressNotificationId(taskId), initialNotification)
        }

        val coroutineJob = serviceScope.launch {
            var lastStateEmitMs = 0L
            var lastNotifyMs = 0L
            val startedAtMs = System.currentTimeMillis()
            try {
                executionSemaphore.withPermit {
                    val result = runner { currentItem, processed, total ->
                        val now = System.currentTimeMillis()
                        val safeTotal = total.coerceAtLeast(1L)
                        val safeProcessed = processed.coerceIn(0L, safeTotal)
                        val elapsedMs = (now - startedAtMs).coerceAtLeast(1L)
                        val bps = (safeProcessed * 1000L) / elapsedMs
                        if (now - lastStateEmitMs >= STATE_THROTTLE_MS || safeProcessed >= safeTotal) {
                            lastStateEmitMs = now
                            updateTaskProgress(
                                TransferProgress(
                                    taskId = taskId,
                                    operation = operation,
                                    currentFileName = currentItem,
                                    processedItems = 0,
                                    totalItems = 1,
                                    bytesProcessed = safeProcessed,
                                    totalBytes = safeTotal,
                                    bytesPerSecond = bps,
                                    isFinished = false,
                                ),
                            )
                        }
                        if (now - lastNotifyMs >= NOTIFICATION_THROTTLE_MS) {
                            lastNotifyMs = now
                            val pct = ((safeProcessed.toDouble() / safeTotal.toDouble()) * PROGRESS_NOTIFICATION_MAX)
                                .toInt()
                                .coerceIn(0, PROGRESS_NOTIFICATION_MAX)
                            val notif = buildProgressNotification(
                                taskId = taskId,
                                title = runningTitle,
                                text = currentItem,
                                progress = pct,
                                max = PROGRESS_NOTIFICATION_MAX,
                                indeterminate = safeTotal <= 1L,
                            )
                            activeProgressNotifications[taskId] = notif
                            notificationManager.notify(progressNotificationId(taskId), notif)
                        }
                    }
                    result.fold(
                        onSuccess = { summaryMsg ->
                            updateTaskProgress(
                                TransferProgress(
                                    taskId = taskId,
                                    operation = operation,
                                    currentFileName = summaryMsg,
                                    processedItems = 1,
                                    totalItems = 1,
                                    bytesProcessed = totalBytes.coerceAtLeast(1L),
                                    totalBytes = totalBytes.coerceAtLeast(1L),
                                    bytesPerSecond = 0L,
                                    isFinished = true,
                                ),
                            )
                            val doneTitle = if (operation == TransferOperation.COMPRESS) {
                                "Packen abgeschlossen."
                            } else {
                                "Entpacken abgeschlossen."
                            }
                            notificationManager.notify(
                                resultNotificationId(taskId),
                                buildResultNotification(taskId, doneTitle, summaryMsg),
                            )
                        },
                        onFailure = { err ->
                            val msg = err.message ?: "Vorgang fehlgeschlagen."
                            updateTaskProgress(
                                TransferProgress(
                                    taskId = taskId,
                                    operation = operation,
                                    currentFileName = initialItem,
                                    processedItems = 0,
                                    totalItems = 1,
                                    bytesProcessed = 0L,
                                    totalBytes = totalBytes.coerceAtLeast(1L),
                                    bytesPerSecond = 0L,
                                    isFinished = true,
                                    errorMessage = msg,
                                ),
                            )
                            val failTitle = if (operation == TransferOperation.COMPRESS) {
                                "Packen fehlgeschlagen."
                            } else {
                                "Entpacken fehlgeschlagen."
                            }
                            notificationManager.notify(
                                resultNotificationId(taskId),
                                buildResultNotification(taskId, failTitle, msg),
                            )
                        },
                    )
                }
            } catch (ce: CancellationException) {
                updateTaskProgress(
                    TransferProgress(
                        taskId = taskId,
                        operation = operation,
                        currentFileName = initialItem,
                        processedItems = 0,
                        totalItems = 1,
                        bytesProcessed = 0L,
                        totalBytes = totalBytes.coerceAtLeast(1L),
                        bytesPerSecond = 0L,
                        isFinished = true,
                        isCancelled = true,
                        errorMessage = "Abgebrochen.",
                    ),
                )
                throw ce
            } finally {
                onTaskEnded(taskId)
            }
        }
        taskJobs[taskId] = coroutineJob
    }

    private fun startForegroundWithNotification(notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun onTaskEnded(taskId: Long) {
        taskJobs.remove(taskId)
        taskTempFiles.remove(taskId)?.let { files ->
            synchronized(files) {
                files.forEach { runCatching { it.delete() } }
                files.clear()
            }
        }
        activeProgressNotifications.remove(taskId)
        val nextEntry = activeProgressNotifications.entries.firstOrNull()
        if (nextEntry != null) {
            if (foregroundAnchorTaskId == taskId || foregroundAnchorTaskId == null) {
                foregroundAnchorTaskId = nextEntry.key
                startForegroundWithNotification(progressNotificationId(nextEntry.key), nextEntry.value)
            }
            notificationManager.cancel(progressNotificationId(taskId))
        } else if (taskJobs.isEmpty()) {
            foregroundAnchorTaskId = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(progressNotificationId(taskId))
            stopSelf()
        } else {
            notificationManager.cancel(progressNotificationId(taskId))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun executeJob(job: TransferJob) {
        val localProvider = LocalVfsProvider()
        val driveProvider = GoogleDriveVfsProvider(applicationContext)
        val smbProvider = SmbVfsProvider(applicationContext)
        val operation = when {
            job.isCloudDownload -> TransferOperation.DOWNLOAD
            job.isMove -> TransferOperation.MOVE
            else -> TransferOperation.COPY
        }
        val title = when {
            job.isCloudDownload -> "Dateien herunterladen…"
            job.isMove -> "Dateien verschieben…"
            else -> "Dateien kopieren…"
        }

        var totalBytes = 0L
        var scannedItems = 0
        if (!job.isCloudDownload) {
            for (src in job.sources) {
                when {
                    src.startsWith("gdrive://") -> {
                        val info = runCatching { driveProvider.stat(src) }.getOrNull()
                        totalBytes += info?.sizeBytes ?: 0L
                        scannedItems += 1
                    }
                    src.startsWith("smb://") -> {
                        val info = runCatching { smbProvider.stat(src) }.getOrNull()
                        totalBytes += info?.sizeBytes ?: 0L
                        scannedItems += 1
                    }
                    else -> {
                        val (bytes, items) = scanLocalSource(File(src))
                        totalBytes += bytes
                        scannedItems += items
                    }
                }
            }
        }
        var totalItems = scannedItems.coerceAtLeast(job.sources.size).coerceAtLeast(1)

        var currentFileName = resolveDisplayName(job.sources.first(), driveProvider, smbProvider)
        var bytesProcessed = 0L
        var processedItems = 0
        var bytesPerSecond = 0L
        val startTimeMs = System.currentTimeMillis()
        var lastStateUpdateMs = 0L
        var lastNotificationUpdateMs = 0L
        var lastNotifiedItems = -1

        fun publishProgress(forceNotification: Boolean = false) {
            val now = System.currentTimeMillis()
            if (forceNotification ||
                now - lastStateUpdateMs >= STATE_THROTTLE_MS ||
                processedItems != lastNotifiedItems
            ) {
                lastStateUpdateMs = now
                updateTaskProgress(
                    TransferProgress(
                        taskId = job.taskId,
                        operation = operation,
                        currentFileName = currentFileName,
                        processedItems = processedItems,
                        totalItems = totalItems,
                        bytesProcessed = bytesProcessed,
                        totalBytes = totalBytes,
                        bytesPerSecond = bytesPerSecond,
                        isFinished = false,
                    ),
                )
            }
            if (forceNotification ||
                now - lastNotificationUpdateMs >= NOTIFICATION_THROTTLE_MS ||
                processedItems != lastNotifiedItems
            ) {
                lastNotificationUpdateMs = now
                lastNotifiedItems = processedItems
                val scaledProgress = when {
                    totalBytes > 0L -> ((bytesProcessed.coerceIn(0L, totalBytes) * PROGRESS_NOTIFICATION_MAX) / totalBytes)
                        .toInt()
                        .coerceIn(0, PROGRESS_NOTIFICATION_MAX)
                    totalItems > 0 -> ((processedItems.coerceIn(0, totalItems) * PROGRESS_NOTIFICATION_MAX) / totalItems)
                        .coerceIn(0, PROGRESS_NOTIFICATION_MAX)
                    else -> 0
                }
                val indeterminate = totalBytes <= 0L && totalItems <= 1
                val itemLabel = if (totalItems == 1) "Element" else "Elemente"
                val subText = "$processedItems/$totalItems $itemLabel"
                val notificationText = when {
                    totalBytes > 0L && bytesPerSecond > 0L ->
                        "${formatBytes(bytesProcessed)} / ${formatBytes(totalBytes)} · ${formatBytes(bytesPerSecond)}/s"
                    totalBytes > 0L ->
                        "${formatBytes(bytesProcessed)} / ${formatBytes(totalBytes)}"
                    else -> subText
                }
                val notif = buildProgressNotification(
                    taskId = job.taskId,
                    title = title,
                    text = notificationText,
                    subText = if (totalBytes > 0L) subText else null,
                    progress = scaledProgress,
                    max = PROGRESS_NOTIFICATION_MAX,
                    indeterminate = indeterminate,
                )
                activeProgressNotifications[job.taskId] = notif
                notificationManager.notify(progressNotificationId(job.taskId), notif)
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
            if (job.isCloudDownload) {
                executeCloudDownload(
                    taskId = job.taskId,
                    sources = job.sources,
                    driveProvider = driveProvider,
                    smbProvider = smbProvider,
                    onTotalsReady = { bytes, items ->
                        totalBytes = bytes
                        totalItems = items
                        publishProgress(forceNotification = true)
                    },
                    onProgress = onProgress,
                )
            } else {
                job.sources.forEach { src ->
                    val displayName = resolveDisplayName(src, driveProvider, smbProvider)
                    if (displayName != currentFileName) {
                        currentFileName = displayName
                        publishProgress()
                    }

                    val srcIsDrive = src.startsWith("gdrive://")
                    val srcIsSmb = src.startsWith("smb://")
                    val dstIsDrive = job.destinationDir.startsWith("gdrive://")
                    val dstIsSmb = job.destinationDir.startsWith("smb://")
                    val singleList = listOf(src)

                    when {
                        srcIsDrive && dstIsDrive -> {
                            if (job.isMove) driveProvider.move(singleList, job.destinationDir, onProgress).getOrThrow()
                            else driveProvider.copy(singleList, job.destinationDir, onProgress).getOrThrow()
                        }
                        srcIsSmb && dstIsSmb -> {
                            if (job.isMove) smbProvider.move(singleList, job.destinationDir, onProgress).getOrThrow()
                            else smbProvider.copy(singleList, job.destinationDir, onProgress).getOrThrow()
                        }
                        !srcIsDrive && !srcIsSmb && !dstIsDrive && !dstIsSmb -> {
                            if (job.isMove) localProvider.move(singleList, job.destinationDir, onProgress).getOrThrow()
                            else localProvider.copy(singleList, job.destinationDir, onProgress).getOrThrow()
                        }
                        !srcIsDrive && !srcIsSmb && dstIsDrive -> {
                            driveProvider.copy(singleList, job.destinationDir, onProgress).getOrThrow()
                            if (job.isMove) {
                                localProvider.delete(singleList).getOrThrow()
                            }
                        }
                        !srcIsDrive && !srcIsSmb && dstIsSmb -> {
                            smbProvider.copy(singleList, job.destinationDir, onProgress).getOrThrow()
                            if (job.isMove) {
                                localProvider.delete(singleList).getOrThrow()
                            }
                        }
                        srcIsDrive && !dstIsDrive && !dstIsSmb -> {
                            val info = driveProvider.stat(src)
                                ?: throw IllegalStateException("Quelle nicht gefunden.")
                            if (info.isDirectory) {
                                throw IllegalStateException("Ordner-Download von Drive wird noch nicht unterstützt.")
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
                        srcIsSmb && !dstIsDrive && !dstIsSmb -> {
                            downloadSmbNodeToLocal(src, File(job.destinationDir), smbProvider, onProgress)
                            if (job.isMove) {
                                smbProvider.delete(singleList).getOrThrow()
                            }
                        }
                        srcIsDrive && dstIsSmb -> {
                            val info = driveProvider.stat(src)
                                ?: throw IllegalStateException("Quelle nicht gefunden.")
                            if (info.isDirectory) {
                                throw IllegalStateException("Ordner-Transfer zwischen Drive und SMB wird noch nicht unterstützt.")
                            }
                            val safeName = File(info.name).name.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "transfer"
                            val tmpDir = File(applicationContext.cacheDir, "transfer_bridge_${job.taskId}").apply { mkdirs() }
                            val tmpFile = File(tmpDir, safeName)
                            try {
                                driveProvider.downloadToFile(src, tmpFile, onProgress, info.sizeBytes).getOrThrow()
                                smbProvider.uploadFile(tmpFile, job.destinationDir, onProgress).getOrThrow()
                                if (job.isMove) driveProvider.delete(singleList).getOrThrow()
                            } finally {
                                tmpDir.deleteRecursively()
                            }
                        }
                        srcIsSmb && dstIsDrive -> {
                            val info = smbProvider.stat(src)
                                ?: throw IllegalStateException("Quelle nicht gefunden.")
                            if (info.isDirectory) {
                                throw IllegalStateException("Ordner-Transfer zwischen SMB und Drive wird noch nicht unterstützt.")
                            }
                            val safeName = File(info.name).name.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "transfer"
                            val tmpDir = File(applicationContext.cacheDir, "transfer_bridge_${job.taskId}").apply { mkdirs() }
                            val tmpFile = File(tmpDir, safeName)
                            try {
                                smbProvider.downloadToFile(src, tmpFile, onProgress, info.sizeBytes).getOrThrow()
                                driveProvider.uploadFile(tmpFile, job.destinationDir, onProgress).getOrThrow()
                                if (job.isMove) smbProvider.delete(singleList).getOrThrow()
                            } finally {
                                tmpDir.deleteRecursively()
                            }
                        }
                    }
                }
                val touchedDrive = job.destinationDir.startsWith("gdrive://") ||
                    job.sources.any { it.startsWith("gdrive://") }
                val touchedSmb = job.destinationDir.startsWith("smb://") ||
                    job.sources.any { it.startsWith("smb://") }
                if (touchedDrive) driveProvider.invalidateCache(null)
                if (touchedSmb) smbProvider.invalidateCache(null)
            }
        }

        val error = result.exceptionOrNull()
        if (error is CancellationException) {
            updateTaskProgress(
                TransferProgress(
                    taskId = job.taskId,
                    operation = operation,
                    currentFileName = currentFileName,
                    processedItems = processedItems,
                    totalItems = totalItems,
                    bytesProcessed = bytesProcessed,
                    totalBytes = totalBytes,
                    bytesPerSecond = bytesPerSecond,
                    isFinished = true,
                    errorMessage = "Abgebrochen.",
                ),
            )
            throw error
        }

        updateTaskProgress(
            TransferProgress(
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
            ),
        )

        val resultTitle = when {
            error != null -> when {
                job.isCloudDownload -> "Download fehlgeschlagen."
                job.isMove -> "Verschieben fehlgeschlagen."
                else -> "Kopieren fehlgeschlagen."
            }
            job.isCloudDownload -> "Download abgeschlossen."
            job.isMove -> "Verschieben abgeschlossen."
            else -> "Kopieren abgeschlossen."
        }
        val resultText = error?.message ?: if (job.sources.size == 1) {
            "1 Element verarbeitet."
        } else {
            "${job.sources.size} Elemente verarbeitet."
        }
        notificationManager.notify(
            resultNotificationId(job.taskId),
            buildResultNotification(job.taskId, resultTitle, resultText),
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

    private suspend fun downloadSmbNodeToLocal(
        sourcePath: String,
        targetDir: File,
        smbProvider: SmbVfsProvider,
        onProgress: (VfsTransferUpdate) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val info = smbProvider.stat(sourcePath)
            ?: throw IllegalStateException("SMB-Quelle nicht gefunden: $sourcePath")
        val safeName = File(info.name).name.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "download"
        if (info.isDirectory) {
            val destDir = resolveUniqueLocalTarget(targetDir, safeName)
            if (!destDir.exists() && !destDir.mkdirs()) {
                throw IllegalStateException("Zielordner konnte nicht erstellt werden: ${destDir.absolutePath}")
            }
            val children = smbProvider.list(sourcePath, showHidden = true)
            if (children.isEmpty()) {
                onProgress(VfsTransferUpdate(currentFileName = safeName, itemsDelta = 1))
                return
            }
            for (child in children) {
                downloadSmbNodeToLocal(child.path, destDir, smbProvider, onProgress)
            }
        } else {
            val dest = resolveUniqueLocalTarget(targetDir, safeName)
            smbProvider.downloadToFile(
                path = sourcePath,
                destFile = dest,
                onProgress = onProgress,
                expectedSize = info.sizeBytes,
            ).getOrThrow()
        }
    }

    private suspend fun executeCloudDownload(
        taskId: Long,
        sources: List<String>,
        driveProvider: GoogleDriveVfsProvider,
        smbProvider: SmbVfsProvider,
        onTotalsReady: (bytes: Long, items: Int) -> Unit,
        onProgress: (VfsTransferUpdate) -> Unit,
    ) {
        val cacheFolder = File(applicationContext.cacheDir, "shared_drive").apply { mkdirs() }
        val filesToDownload = mutableListOf<VfsNode>()
        val seenKeys = HashSet<String>()
        val folderFiles = LinkedHashMap<String, MutableList<String>>()
        val visitedFolders = HashSet<String>()

        fun providerFor(path: String): VfsProvider =
            if (path.startsWith("smb://")) smbProvider else driveProvider

        suspend fun collectFolder(folderNode: VfsNode) {
            val folderMarker = CloudCacheKey.folderMarkerName(folderNode)
            if (!visitedFolders.add(folderMarker)) return
            val cachedNames = folderFiles.getOrPut(folderMarker) { mutableListOf() }
            val children = providerFor(folderNode.path).list(folderNode.path, showHidden = true)
            for (child in children) {
                if (child.isDirectory) {
                    collectFolder(child)
                } else {
                    val cachedName = CloudCacheKey.cachedFileName(child)
                    cachedNames.add(cachedName)
                    if (seenKeys.add(cachedName)) {
                        filesToDownload.add(child)
                    }
                }
            }
        }

        for (src in sources) {
            val node = providerFor(src).stat(src)
                ?: throw IllegalStateException("Quelle nicht gefunden.")
            if (node.isDirectory) {
                collectFolder(node)
            } else {
                val cachedName = CloudCacheKey.cachedFileName(node)
                if (seenKeys.add(cachedName)) {
                    filesToDownload.add(node)
                }
            }
        }

        val totalBytes = filesToDownload.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val totalItems = filesToDownload.size.coerceAtLeast(1)
        onTotalsReady(totalBytes, totalItems)

        val buffer = ByteArray(64 * 1024)
        for (fileNode in filesToDownload) {
            val displayName = fileNode.name.ifBlank { fileNode.id }
            onProgress(VfsTransferUpdate(currentFileName = displayName))

            val cachedName = CloudCacheKey.cachedFileName(fileNode)
            val targetFile = File(cacheFolder, cachedName)

            if (targetFile.exists() && fileNode.sizeBytes >= 0L && targetFile.length() == fileNode.sizeBytes) {
                onProgress(
                    VfsTransferUpdate(
                        currentFileName = displayName,
                        bytesDelta = fileNode.sizeBytes,
                        itemsDelta = 1,
                    ),
                )
                continue
            }

            val taskTemps = taskTempFiles.getOrPut(taskId) { Collections.synchronizedSet(HashSet()) }
            val tmpFile = File(cacheFolder, "$cachedName.tmp")
            taskTemps.add(tmpFile)
            var renamed = false
            try {
                providerFor(fileNode.path).openInputStream(fileNode.path).use { input ->
                    tmpFile.outputStream().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                onProgress(VfsTransferUpdate(currentFileName = displayName, bytesDelta = read.toLong()))
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                renamed = true
            } finally {
                taskTemps.remove(tmpFile)
                if (!renamed) {
                    tmpFile.delete()
                }
            }

            onProgress(VfsTransferUpdate(currentFileName = displayName, itemsDelta = 1))
        }

        for ((markerName, cachedNames) in folderFiles) {
            val markerFile = File(cacheFolder, markerName)
            val jsonArray = JSONArray()
            cachedNames.forEach { jsonArray.put(it) }
            markerFile.writeText(jsonArray.toString())
        }
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

    private suspend fun resolveDisplayName(
        path: String,
        driveProvider: GoogleDriveVfsProvider,
        smbProvider: SmbVfsProvider,
    ): String {
        return when {
            path.startsWith("gdrive://") ->
                runCatching { driveProvider.stat(path)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: path.removePrefix("gdrive://")
            path.startsWith("smb://") ->
                runCatching { smbProvider.stat(path)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: path.trimEnd('/').substringAfterLast('/').ifBlank { path }
            else -> File(path).name.ifBlank { path }
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dateiübertragungen",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Fortschritt bei Dateiübertragungen, Cloud-Downloads und Archiven."
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun progressNotificationId(taskId: Long): Int =
        PROGRESS_NOTIFICATION_BASE_ID + ((taskId % 4000L).toInt() * 2)

    private fun resultNotificationId(taskId: Long): Int =
        progressNotificationId(taskId) + 1

    private fun buildProgressNotification(
        taskId: Long,
        title: String,
        text: String,
        subText: String? = null,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .apply { if (!subText.isNullOrBlank()) setSubText(subText) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(max, progress, indeterminate)
            .setContentIntent(getLaunchPendingIntent(taskId))
            .build()
    }

    private fun buildResultNotification(taskId: Long, title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(getLaunchPendingIntent(taskId))
            .build()
    }

    private fun getLaunchPendingIntent(taskId: Long): PendingIntent? =
        launchPendingIntents[taskId] ?: createLaunchPendingIntent(taskId)?.also {
            launchPendingIntents[taskId] = it
        }

    private fun createLaunchPendingIntent(taskId: Long): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_OPEN_TRANSFER_PANEL, true)
            putExtra(EXTRA_TRANSFER_TASK_ID, taskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_TRANSFER_PANEL + (taskId % 4000L).toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }

    companion object {
        const val EXTRA_OPEN_TRANSFER_PANEL = "com.monolith.core.vfs.EXTRA_OPEN_TRANSFER_PANEL"
        const val EXTRA_TRANSFER_TASK_ID = "com.monolith.core.vfs.EXTRA_TRANSFER_TASK_ID"
        private const val ACTION_CANCEL_TRANSFER = "com.monolith.core.vfs.ACTION_CANCEL_TRANSFER"
        private const val EXTRA_CANCEL_TASK_ID = "extra_cancel_task_id"

        private const val CHANNEL_ID = "monolith_file_transfer"
        private const val PROGRESS_NOTIFICATION_BASE_ID = 20040
        private const val REQUEST_CODE_OPEN_TRANSFER_PANEL = 24000
        private const val PROGRESS_NOTIFICATION_MAX = 1000
        private const val NOTIFICATION_THROTTLE_MS = 500L
        private const val STATE_THROTTLE_MS = 80L
        private const val MAX_CONCURRENT_JOBS = 2

        private const val EXTRA_SOURCES = "extra_sources"
        private const val EXTRA_DESTINATION = "extra_destination"
        private const val EXTRA_IS_MOVE = "extra_is_move"
        private const val EXTRA_IS_CLOUD_DOWNLOAD = "extra_is_cloud_download"
        private const val EXTRA_EXTERNAL_TASK_ID = "extra_external_task_id"
        private const val EXTRA_EXTERNAL_OPERATION = "extra_external_operation"
        private const val EXTRA_EXTERNAL_ITEM = "extra_external_item"
        private const val EXTRA_EXTERNAL_TOTAL_BYTES = "extra_external_total_bytes"

        private val pendingExternalRunners = ConcurrentHashMap<Long, suspend (
            updateProgress: (currentItem: String, processedBytes: Long, totalBytes: Long) -> Unit,
        ) -> Result<String>>()

        private val nextTaskId = AtomicLong(1L)

        private val _events = MutableSharedFlow<TransferCompletionEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<TransferCompletionEvent> = _events.asSharedFlow()

        private val _progress = MutableStateFlow<TransferProgress?>(null)
        val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

        private val _tasks = MutableStateFlow<Map<Long, TransferProgress>>(emptyMap())
        val tasks: StateFlow<Map<Long, TransferProgress>> = _tasks.asStateFlow()

        private val _latestCreatedTaskId = MutableStateFlow(0L)
        val latestCreatedTaskId: StateFlow<Long> = _latestCreatedTaskId.asStateFlow()

        private fun updateTaskProgress(tp: TransferProgress) {
            _tasks.update { it + (tp.taskId to tp) }
            _progress.value = tp
        }

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

        fun enqueueCloudDownload(
            context: Context,
            sources: List<String>,
        ) {
            if (sources.isEmpty()) return
            val destinationDir = File(context.applicationContext.cacheDir, "shared_drive").absolutePath
            val intent = Intent(context, FileTransferService::class.java).apply {
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources))
                putExtra(EXTRA_DESTINATION, destinationDir)
                putExtra(EXTRA_IS_CLOUD_DOWNLOAD, true)
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun enqueueArchiveTask(
            context: Context,
            operation: TransferOperation,
            initialItem: String,
            totalBytes: Long,
            runner: suspend (
                updateProgress: (currentItem: String, processedBytes: Long, totalBytes: Long) -> Unit,
            ) -> Result<String>,
        ): Long {
            val taskId = nextTaskId.getAndIncrement()
            _latestCreatedTaskId.value = taskId
            pendingExternalRunners[taskId] = runner
            val intent = Intent(context.applicationContext, FileTransferService::class.java).apply {
                putExtra(EXTRA_EXTERNAL_TASK_ID, taskId)
                putExtra(EXTRA_EXTERNAL_OPERATION, operation.name)
                putExtra(EXTRA_EXTERNAL_ITEM, initialItem)
                putExtra(EXTRA_EXTERNAL_TOTAL_BYTES, totalBytes.coerceAtLeast(1L))
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
            return taskId
        }

        fun cancel(context: Context, taskId: Long? = null) {
            val intent = Intent(context.applicationContext, FileTransferService::class.java).apply {
                action = ACTION_CANCEL_TRANSFER
                if (taskId != null) {
                    putExtra(EXTRA_CANCEL_TASK_ID, taskId)
                }
            }
            runCatching { context.applicationContext.startService(intent) }
        }
    }
}
