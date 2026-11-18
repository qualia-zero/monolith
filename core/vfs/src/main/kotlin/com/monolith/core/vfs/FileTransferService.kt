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
            if (it.startsWith("gdrive://")) it.removePrefix("gdrive://") else File(it).name.ifBlank { it }
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

        val initialNotification = buildProgressNotification(
            taskId = taskId,
            title = when {
                isCloudDownload -> "Dateien herunterladen…"
                isMove -> "Dateien verschieben…"
                else -> "Dateien kopieren…"
            },
            text = if (isInitiallyQueued) "In Warteschlange… · 0/$itemCount Elemente" else "0/$itemCount Elemente",
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
        }
        var totalItems = scannedItems.coerceAtLeast(job.sources.size).coerceAtLeast(1)

        var currentFileName = resolveDisplayName(job.sources.first(), driveProvider)
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
                val notificationText = buildString {
                    append("$processedItems/$totalItems Elemente")
                    if (totalBytes > 0L) {
                        append(" · ${formatBytes(bytesProcessed)} / ${formatBytes(totalBytes)}")
                    }
                    if (bytesPerSecond > 0L) {
                        append(" · ${formatBytes(bytesPerSecond)}/s")
                    }
                }
                val notif = buildProgressNotification(
                    taskId = job.taskId,
                    title = title,
                    text = notificationText,
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
                    onTotalsReady = { bytes, items ->
                        totalBytes = bytes
                        totalItems = items
                        publishProgress(forceNotification = true)
                    },
                    onProgress = onProgress,
                )
            } else {
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
        val resultText = error?.message ?: "${job.sources.size} Element(e) verarbeitet."
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

    private suspend fun executeCloudDownload(
        taskId: Long,
        sources: List<String>,
        driveProvider: GoogleDriveVfsProvider,
        onTotalsReady: (bytes: Long, items: Int) -> Unit,
        onProgress: (VfsTransferUpdate) -> Unit,
    ) {
        val cacheFolder = File(applicationContext.cacheDir, "shared_drive").apply { mkdirs() }
        val filesToDownload = mutableListOf<VfsNode>()
        val seenFileIds = HashSet<String>()
        val folderFiles = LinkedHashMap<String, MutableList<String>>()
        val visitedFolders = HashSet<String>()

        suspend fun collectFolder(folderNode: VfsNode) {
            if (!visitedFolders.add(folderNode.id)) return
            val cachedNames = folderFiles.getOrPut(folderNode.id) { mutableListOf() }
            val children = driveProvider.list(folderNode.path, showHidden = true)
            for (child in children) {
                if (child.isDirectory) {
                    collectFolder(child)
                } else {
                    val cachedName = "${child.id}_${child.name.replace('/', '_')}"
                    cachedNames.add(cachedName)
                    if (seenFileIds.add(child.id)) {
                        filesToDownload.add(child)
                    }
                }
            }
        }

        for (src in sources) {
            val node = driveProvider.stat(src)
                ?: throw IllegalStateException("Quelle nicht gefunden")
            if (node.isDirectory) {
                collectFolder(node)
            } else if (seenFileIds.add(node.id)) {
                filesToDownload.add(node)
            }
        }

        val totalBytes = filesToDownload.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val totalItems = filesToDownload.size.coerceAtLeast(1)
        onTotalsReady(totalBytes, totalItems)

        val buffer = ByteArray(64 * 1024)
        for (fileNode in filesToDownload) {
            val displayName = fileNode.name.ifBlank { fileNode.id }
            onProgress(VfsTransferUpdate(currentFileName = displayName))

            val cachedName = "${fileNode.id}_${fileNode.name.replace('/', '_')}"
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
                driveProvider.openInputStream(fileNode.path).use { input ->
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

        for ((folderId, cachedNames) in folderFiles) {
            val markerFile = File(cacheFolder, "folder_$folderId.json")
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

    private fun progressNotificationId(taskId: Long): Int =
        PROGRESS_NOTIFICATION_BASE_ID + ((taskId % 4000L).toInt() * 2)

    private fun resultNotificationId(taskId: Long): Int =
        progressNotificationId(taskId) + 1

    private fun buildProgressNotification(
        taskId: Long,
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
