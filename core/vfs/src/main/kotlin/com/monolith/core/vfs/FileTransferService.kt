package com.monolith.core.vfs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.Collections
import java.util.Locale
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
        val isCloudDownload: Boolean = false,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobQueue = Channel<TransferJob>(Channel.UNLIMITED)
    private val pendingJobs = AtomicInteger(0)
    private var activeExecutionJob: Job? = null
    private val activeTempFiles = Collections.synchronizedSet(HashSet<File>())
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()

        serviceScope.launch {
            for (job in jobQueue) {
                val executionJob = launch {
                    executeJob(job)
                }
                activeExecutionJob = executionJob
                try {
                    executionJob.join()
                } finally {
                    if (activeExecutionJob === executionJob) {
                        activeExecutionJob = null
                    }
                }
                if (pendingJobs.decrementAndGet() <= 0) {
                    pendingJobs.set(0)
                    ServiceCompat.stopForeground(this@FileTransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_TRANSFER) {
            while (jobQueue.tryReceive().isSuccess) {
                // Drain pending transfer jobs
            }
            pendingJobs.set(0)
            activeExecutionJob?.cancel()
            activeExecutionJob = null
            synchronized(activeTempFiles) {
                activeTempFiles.forEach { runCatching { it.delete() } }
                activeTempFiles.clear()
            }
            notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            _progress.value = _progress.value?.copy(
                isFinished = true,
                errorMessage = "Abgebrochen",
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val sources = intent?.getStringArrayListExtra(EXTRA_SOURCES).orEmpty()
        val destinationDir = intent?.getStringExtra(EXTRA_DESTINATION).orEmpty()
        val isMove = intent?.getBooleanExtra(EXTRA_IS_MOVE, false) ?: false
        val isCloudDownload = intent?.getBooleanExtra(EXTRA_IS_CLOUD_DOWNLOAD, false) ?: false

        if (sources.isEmpty() || destinationDir.isBlank()) {
            if (pendingJobs.get() <= 0) stopSelf()
            return START_NOT_STICKY
        }

        pendingJobs.incrementAndGet()
        val taskId = nextTaskId.getAndIncrement()
        val operation = when {
            isCloudDownload -> TransferOperation.DOWNLOAD
            isMove -> TransferOperation.MOVE
            else -> TransferOperation.COPY
        }
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
            title = when {
                isCloudDownload -> "Dateien herunterladen…"
                isMove -> "Dateien verschieben…"
                else -> "Dateien kopieren…"
            },
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

        jobQueue.trySend(TransferJob(taskId, sources, destinationDir, isMove, isCloudDownload))
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
        var lastNotificationUpdateMs = 0L
        var lastNotifiedItems = -1

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
                notificationManager.notify(
                    PROGRESS_NOTIFICATION_ID,
                    buildProgressNotification(
                        title = title,
                        text = notificationText,
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
            if (job.isCloudDownload) {
                executeCloudDownload(
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
                errorMessage = "Abgebrochen",
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
            error != null -> when {
                job.isCloudDownload -> "Download fehlgeschlagen"
                job.isMove -> "Verschieben fehlgeschlagen"
                else -> "Kopieren fehlgeschlagen"
            }
            job.isCloudDownload -> "Download abgeschlossen."
            job.isMove -> "Verschieben abgeschlossen."
            else -> "Kopieren abgeschlossen."
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

    private suspend fun executeCloudDownload(
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

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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

            val tmpFile = File(cacheFolder, "$cachedName.tmp")
            activeTempFiles.add(tmpFile)
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
                activeTempFiles.remove(tmpFile)
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
            .setContentIntent(cachedLaunchPendingIntent)
            .build()
    }

    private fun buildResultNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(cachedLaunchPendingIntent)
            .build()
    }

    private val cachedLaunchPendingIntent: PendingIntent? by lazy {
        createLaunchPendingIntent()
    }

    private fun createLaunchPendingIntent(): PendingIntent? {
        val explicitIntent = Intent().apply {
            component = ComponentName(packageName, "com.monolith.app.MainActivity")
        }
        val launchIntent = (if (explicitIntent.resolveActivity(packageManager) != null) {
            explicitIntent
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        })?.apply {
            action = EXTRA_OPEN_TRANSFER_PANEL
            putExtra(EXTRA_OPEN_TRANSFER_PANEL, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        } ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_TRANSFER_PANEL,
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
        private const val ACTION_CANCEL_TRANSFER = "com.monolith.core.vfs.ACTION_CANCEL_TRANSFER"

        private const val CHANNEL_ID = "monolith_file_transfer"
        private const val PROGRESS_NOTIFICATION_ID = 20041
        private const val RESULT_NOTIFICATION_ID = 20042
        private const val REQUEST_CODE_OPEN_TRANSFER_PANEL = 20043
        private const val PROGRESS_NOTIFICATION_MAX = 1000
        private const val NOTIFICATION_THROTTLE_MS = 750L

        private const val EXTRA_SOURCES = "extra_sources"
        private const val EXTRA_DESTINATION = "extra_destination"
        private const val EXTRA_IS_MOVE = "extra_is_move"
        private const val EXTRA_IS_CLOUD_DOWNLOAD = "extra_is_cloud_download"

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

        fun cancel(context: Context) {
            val intent = Intent(context.applicationContext, FileTransferService::class.java).apply {
                action = ACTION_CANCEL_TRANSFER
            }
            runCatching { context.applicationContext.startService(intent) }
        }
    }
}
