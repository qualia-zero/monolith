package com.monolith.feature.explorer.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MediaDownloadService : Service() {

    private data class EnqueuedRequest(
        val taskId: Long,
        val url: String,
        val mode: DownloadMode,
        val outputDirectory: String,
        val settings: DownloaderSettings,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executionSemaphore = Semaphore(1)
    private val activeProgressNotifications = ConcurrentHashMap<Long, Notification>()
    private val lastNotificationUpdateMs = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var foregroundAnchorTaskId: Long? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        isServiceAlive.set(true)
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val cancelTaskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (cancelTaskId > 0L) {
                    performCancel(cancelTaskId)
                }
                if (taskJobs.isEmpty() && activeProgressNotifications.isEmpty()) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_ENQUEUE -> {
                val taskIds = intent.getLongArrayExtra(EXTRA_TASK_IDS) ?: LongArray(0)
                val validRequests = taskIds.toList().mapNotNull { id ->
                    val req = pendingRequests.remove(id)
                    if (req == null || id in cancelledTaskIds) {
                        cancelledTaskIds.remove(id)
                        null
                    } else {
                        req
                    }
                }

                if (validRequests.isEmpty()) {
                    if (foregroundAnchorTaskId == null && taskJobs.isEmpty()) {
                        val placeholder = buildProgressNotification(
                            taskId = 0L,
                            title = "Downloader",
                            text = "Wird vorbereitet…",
                            progress = 0,
                            indeterminate = true,
                            allowCancel = false,
                        )
                        startForegroundWithNotification(NOTIFICATION_BASE_ID, placeholder)
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }

                val anchorId = validRequests.first().taskId
                if (foregroundAnchorTaskId == null) {
                    val anchorTitle = validRequests.first().let {
                        "${it.mode.label}-Download wird vorbereitet…"
                    }
                    val initialNotification = buildProgressNotification(
                        taskId = anchorId,
                        title = anchorTitle,
                        text = validRequests.first().url,
                        progress = 0,
                        indeterminate = true,
                    )
                    foregroundAnchorTaskId = anchorId
                    activeProgressNotifications[anchorId] = initialNotification
                    startForegroundWithNotification(progressNotificationId(anchorId), initialNotification)
                }

                for (req in validRequests) {
                    val coroutineJob = serviceScope.launch {
                        try {
                            executionSemaphore.withPermit {
                                executeDownloadJob(req)
                            }
                        } finally {
                            onTaskEnded(req.taskId)
                        }
                    }
                    taskJobs[req.taskId] = coroutineJob
                }
                return START_NOT_STICKY
            }

            else -> {
                if (taskJobs.isEmpty() && foregroundAnchorTaskId == null) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        isServiceAlive.set(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun executeDownloadJob(req: EnqueuedRequest) {
        if (req.taskId in cancelledTaskIds || !currentCoroutineContext().isActive) {
            updateTask(req.taskId) {
                it.copy(
                    status = DownloadTaskStatus.CANCELLED,
                    progressPercent = -1f,
                    etaSeconds = -1L,
                    statusDetail = "Abgebrochen.",
                )
            }
            return
        }

        try {
            val outDir = File(req.outputDirectory)
            if (!outDir.exists()) {
                outDir.mkdirs()
            }

            updateTask(req.taskId) {
                it.copy(
                    status = DownloadTaskStatus.INITIALIZING,
                    progressPercent = -1f,
                    etaSeconds = -1L,
                    statusDetail = "Engine wird initialisiert…",
                    errorMessage = null,
                )
            }
            postProgressNotification(
                req = req,
                title = "${req.mode.label} wird initialisiert…",
                text = displayLabelForTask(req.taskId, req.url),
                progress = 0,
                indeterminate = true,
                force = true,
            )

            val initResult = DownloaderEngine.ensureInitialized(applicationContext)
            initResult.getOrElse { err ->
                throw IllegalStateException(err.message ?: "yt-dlp konnte nicht initialisiert werden.", err)
            }

            if (req.settings.autoUpdateEngine) {
                runCatching {
                    DownloaderEngine.updateEngine(
                        context = applicationContext,
                        channel = req.settings.updateChannel,
                        forceCheck = false,
                    )
                }
            }

            checkCancelledOrInactive(req.taskId)

            var probedExt: String? = null
            if (DownloaderCommandBuilder.needsVideoFormatProbe(req.mode, req.settings)) {
                updateTask(req.taskId) {
                    it.copy(
                        status = DownloadTaskStatus.PROBING,
                        progressPercent = -1f,
                        statusDetail = "Bestes Format wird ermittelt…",
                    )
                }
                postProgressNotification(
                    req = req,
                    title = "Format wird geprüft…",
                    text = displayLabelForTask(req.taskId, req.url),
                    progress = 0,
                    indeterminate = true,
                    force = true,
                )

                val probeOptions = DownloaderCommandBuilder.buildVideoProbeOptions(req.settings)
                val probeBuilt = BuiltYtDlpCommand(
                    options = probeOptions,
                    extraTokens = DownloaderCommandBuilder.tokenizeExtraArgs(req.settings.extraArgs),
                )
                val probeRequest = DownloaderEngine.buildRequest(req.url, probeBuilt)
                val probeProcId = "monolith_probe_${req.taskId}"
                activeProcessIds[req.taskId] = probeProcId
                val probeResult = runCatching {
                    YoutubeDL.getInstance().execute(probeRequest, probeProcId) { _, _, _ ->
                        if (req.taskId in cancelledTaskIds) {
                            runCatching { YoutubeDL.getInstance().destroyProcessById(probeProcId) }
                        }
                    }
                }
                activeProcessIds.remove(req.taskId, probeProcId)

                probeResult.fold(
                    onSuccess = { probeResponse ->
                        probedExt = DownloaderCommandBuilder.parseProbedExtension(probeResponse.out)
                        if (probedExt != null) {
                            appendLogLine(req.taskId, "[probe] Ermitteltes Format: $probedExt")
                        }
                    },
                    onFailure = { probeErr ->
                        if (isCancellationThrowable(probeErr) || req.taskId in cancelledTaskIds) {
                            throw CancellationException("Download abgebrochen.", probeErr)
                        }
                        val msg = probeErr.message?.trim().orEmpty()
                        if (msg.isNotEmpty()) {
                            appendLogLine(req.taskId, "[probe] Hinweis: $msg")
                        }
                    },
                )
            }

            checkCancelledOrInactive(req.taskId)

            val builtCommand = DownloaderCommandBuilder.buildDownloadCommand(
                mode = req.mode,
                settings = req.settings,
                outputDirPath = req.outputDirectory,
                probedExt = probedExt,
            )
            val downloadRequest = DownloaderEngine.buildRequest(req.url, builtCommand)
            val dlProcId = "monolith_dl_${req.taskId}"
            activeProcessIds[req.taskId] = dlProcId

            updateTask(req.taskId) {
                it.copy(
                    status = DownloadTaskStatus.DOWNLOADING,
                    progressPercent = -1f,
                    etaSeconds = -1L,
                    statusDetail = "Download gestartet…",
                )
            }
            postProgressNotification(
                req = req,
                title = "${req.mode.label} wird heruntergeladen…",
                text = displayLabelForTask(req.taskId, req.url),
                progress = 0,
                indeterminate = true,
                force = true,
            )

            val response = YoutubeDL.getInstance().execute(downloadRequest, dlProcId) { progress, etaSec, line ->
                if (req.taskId in cancelledTaskIds) {
                    runCatching { YoutubeDL.getInstance().destroyProcessById(dlProcId) }
                }
                handleProgressLine(req, progress, etaSec, line)
            }
            activeProcessIds.remove(req.taskId, dlProcId)

            checkCancelledOrInactive(req.taskId)

            response.out.lineSequence().forEach { outLine ->
                val trimmed = outLine.trim()
                if (trimmed.isNotEmpty()) {
                    val detected = DownloaderCommandBuilder.parseOutputFilePathFromLine(trimmed)
                    if (detected != null) {
                        val parsedTitle = DownloaderCommandBuilder.parseTitleFromPathOrLine(detected)
                        updateTask(req.taskId) {
                            it.copy(
                                outputFilePath = detected,
                                title = parsedTitle ?: it.title,
                            )
                        }
                    }
                }
            }

            if (response.exitCode != 0) {
                val errDetail = extractMeaningfulError(response.err, response.out)
                    ?: "yt-dlp wurde mit Exit-Code ${response.exitCode} beendet."
                throw YoutubeDLException(errDetail)
            }

            val finalTask = _tasks.value.firstOrNull { it.id == req.taskId }
            val finalPath = finalTask?.outputFilePath
            if (!finalPath.isNullOrBlank() && File(finalPath).exists()) {
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(finalPath),
                    null,
                    null,
                )
            }

            val completedTitle = finalTask?.title
                ?: finalPath?.let { DownloaderCommandBuilder.parseTitleFromPathOrLine(it) }
                ?: req.url

            updateTask(req.taskId) {
                it.copy(
                    status = DownloadTaskStatus.COMPLETED,
                    title = completedTitle,
                    progressPercent = 100f,
                    etaSeconds = 0L,
                    statusDetail = "Abgeschlossen.",
                    errorMessage = null,
                )
            }

            notificationManager.notify(
                resultNotificationId(req.taskId),
                buildResultNotification(
                    title = "Download abgeschlossen",
                    text = completedTitle,
                    isError = false,
                ),
            )
        } catch (t: Throwable) {
            val wasCancelled = req.taskId in cancelledTaskIds ||
                isCancellationThrowable(t) ||
                !currentCoroutineContext().isActive
            if (wasCancelled) {
                updateTask(req.taskId) {
                    it.copy(
                        status = DownloadTaskStatus.CANCELLED,
                        progressPercent = -1f,
                        etaSeconds = -1L,
                        statusDetail = "Abgebrochen.",
                    )
                }
            } else {
                val existingLogs = _tasks.value.firstOrNull { it.id == req.taskId }?.logs.orEmpty()
                val errMsg = extractMeaningfulError(t.message, existingLogs.joinToString("\n"))
                    ?: "Download fehlgeschlagen."
                appendLogLine(req.taskId, "ERROR: $errMsg")
                updateTask(req.taskId) {
                    it.copy(
                        status = DownloadTaskStatus.FAILED,
                        progressPercent = -1f,
                        etaSeconds = -1L,
                        statusDetail = "Fehlgeschlagen.",
                        errorMessage = errMsg,
                    )
                }
                notificationManager.notify(
                    resultNotificationId(req.taskId),
                    buildResultNotification(
                        title = "Download fehlgeschlagen",
                        text = errMsg,
                        isError = true,
                    ),
                )
            }
        } finally {
            activeProcessIds.remove(req.taskId)
        }
    }

    private fun handleProgressLine(
        req: EnqueuedRequest,
        progress: Float,
        etaSeconds: Long,
        line: String,
    ) {
        val cleanLine = line.trim()
        val detectedPath = DownloaderCommandBuilder.parseOutputFilePathFromLine(cleanLine)
        val detectedTitle = DownloaderCommandBuilder.parseTitleFromPathOrLine(detectedPath ?: cleanLine)
        val detectedDetail = DownloaderCommandBuilder.parseStatusDetailFromLine(cleanLine)
        val isPostProcessing = DownloaderCommandBuilder.isPostProcessingLine(cleanLine)

        updateTask(req.taskId) { current ->
            if (!current.status.isActive) return@updateTask current
            val nextStatus = if (isPostProcessing) {
                DownloadTaskStatus.POST_PROCESSING
            } else {
                DownloadTaskStatus.DOWNLOADING
            }
            val nextProgress = when {
                isPostProcessing -> current.progressPercent
                progress >= 0f -> progress.coerceIn(0f, 100f)
                else -> current.progressPercent
            }
            val nextEta = when {
                isPostProcessing -> -1L
                etaSeconds >= 0L -> etaSeconds
                else -> current.etaSeconds
            }
            val nextLogs = if (cleanLine.isNotEmpty()) {
                (current.logs + cleanLine).takeLast(MAX_LOG_LINES_PER_TASK)
            } else {
                current.logs
            }
            val nextDetail = detectedDetail ?: when {
                nextStatus == DownloadTaskStatus.DOWNLOADING && nextProgress >= 0f -> {
                    val etaSuffix = if (nextEta > 0L) " · Restzeit ${formatEta(nextEta)}" else ""
                    "${nextProgress.toInt()}%$etaSuffix"
                }
                else -> current.statusDetail
            }
            current.copy(
                status = nextStatus,
                title = detectedTitle ?: current.title,
                outputFilePath = detectedPath ?: current.outputFilePath,
                progressPercent = nextProgress,
                etaSeconds = nextEta,
                statusDetail = nextDetail,
                logs = nextLogs,
            )
        }

        val currentTask = _tasks.value.firstOrNull { it.id == req.taskId } ?: return
        val notifTitle = currentTask.title ?: "${req.mode.label} wird heruntergeladen…"
        val queuedCount = (_tasks.value.count { it.status == DownloadTaskStatus.QUEUED })
        val queueSuffix = if (queuedCount > 0) " · +$queuedCount in der Warteschlange" else ""
        val notifText = (currentTask.statusDetail ?: currentTask.status.label) + queueSuffix
        val indeterminate = currentTask.status != DownloadTaskStatus.DOWNLOADING || currentTask.progressPercent < 0f
        val progressInt = if (indeterminate) 0 else (currentTask.progressPercent * 10f).toInt().coerceIn(0, PROGRESS_NOTIFICATION_MAX)

        postProgressNotification(
            req = req,
            title = notifTitle,
            text = notifText,
            progress = progressInt,
            indeterminate = indeterminate,
            force = detectedDetail != null || isPostProcessing,
        )
    }

    private fun postProgressNotification(
        req: EnqueuedRequest,
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val last = lastNotificationUpdateMs[req.taskId] ?: 0L
        if (!force && now - last < NOTIFICATION_THROTTLE_MS) return
        lastNotificationUpdateMs[req.taskId] = now

        val notification = buildProgressNotification(
            taskId = req.taskId,
            title = title,
            text = text,
            progress = progress,
            indeterminate = indeterminate,
        )
        activeProgressNotifications[req.taskId] = notification

        if (foregroundAnchorTaskId == null || foregroundAnchorTaskId == req.taskId) {
            foregroundAnchorTaskId = req.taskId
            startForegroundWithNotification(progressNotificationId(req.taskId), notification)
        } else {
            val previousAnchor = foregroundAnchorTaskId
            foregroundAnchorTaskId = req.taskId
            startForegroundWithNotification(progressNotificationId(req.taskId), notification)
            if (previousAnchor != null && previousAnchor != req.taskId) {
                notificationManager.cancel(progressNotificationId(previousAnchor))
            }
        }
    }

    private fun onTaskEnded(taskId: Long) {
        taskJobs.remove(taskId)
        activeProcessIds.remove(taskId)
        cancelledTaskIds.remove(taskId)
        lastNotificationUpdateMs.remove(taskId)
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

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Fortschritt und Status von Medien-Downloads."
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(
        taskId: Long,
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
        allowCancel: Boolean = true,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(PROGRESS_NOTIFICATION_MAX, progress, indeterminate)
            .setContentIntent(createLaunchPendingIntent())

        if (allowCancel && taskId > 0L) {
            val cancelIntent = Intent(this, MediaDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_CANCEL_BASE + (taskId % 4000L).toInt(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Abbrechen",
                cancelPendingIntent,
            )
        }
        return builder.build()
    }

    private fun buildResultNotification(
        title: String,
        text: String,
        isError: Boolean,
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done,
            )
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(createLaunchPendingIntent())
            .build()
    }

    private fun createLaunchPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_OPEN_DOWNLOADER, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_DOWNLOADER,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun checkCancelledOrInactive(taskId: Long) {
        if (taskId in cancelledTaskIds) {
            throw CancellationException("Download abgebrochen.")
        }
    }

    private fun isCancellationThrowable(t: Throwable): Boolean {
        return t is CancellationException ||
            t is YoutubeDL.CanceledException ||
            t is InterruptedException ||
            t.cause?.let { it is YoutubeDL.CanceledException || it is InterruptedException || it is CancellationException } == true
    }

    private fun displayLabelForTask(taskId: Long, fallbackUrl: String): String {
        return _tasks.value.firstOrNull { it.id == taskId }?.title ?: fallbackUrl
    }

    private fun appendLogLine(taskId: Long, line: String) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        updateTask(taskId) { current ->
            current.copy(logs = (current.logs + clean).takeLast(MAX_LOG_LINES_PER_TASK))
        }
    }

    private fun extractMeaningfulError(primary: String?, secondary: String?): String? {
        val lines = buildList {
            primary?.lineSequence()?.forEach { add(it.trim()) }
            secondary?.lineSequence()?.forEach { add(it.trim()) }
        }.filter { it.isNotEmpty() }

        val explicitError = lines.lastOrNull { it.startsWith("ERROR:", ignoreCase = true) }
        val raw = explicitError ?: lines.lastOrNull() ?: return null
        return raw.removePrefix("ERROR:").trim().ifEmpty { raw }
    }

    private fun formatEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val mins = (safe % 3600L) / 60L
        val secs = safe % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", mins, secs)
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADER = "com.monolith.extra.OPEN_DOWNLOADER"

        private const val ACTION_ENQUEUE = "com.monolith.feature.explorer.downloader.ACTION_ENQUEUE"
        private const val ACTION_CANCEL = "com.monolith.feature.explorer.downloader.ACTION_CANCEL"
        private const val EXTRA_TASK_IDS = "extra_task_ids"
        private const val EXTRA_TASK_ID = "extra_task_id"

        private const val CHANNEL_ID = "monolith_media_downloads"
        private const val CHANNEL_NAME = "Medien-Downloads"
        private const val NOTIFICATION_BASE_ID = 28100
        private const val REQUEST_CODE_OPEN_DOWNLOADER = 28500
        private const val REQUEST_CODE_CANCEL_BASE = 28600
        private const val PROGRESS_NOTIFICATION_MAX = 1000
        private const val NOTIFICATION_THROTTLE_MS = 450L
        private const val MAX_LOG_LINES_PER_TASK = 80

        private val _tasks = MutableStateFlow<List<DownloadTaskItem>>(emptyList())
        val tasks: StateFlow<List<DownloadTaskItem>> = _tasks.asStateFlow()

        private val nextTaskId = AtomicLong(1L)
        private val isServiceAlive = AtomicBoolean(false)
        private val pendingRequests = ConcurrentHashMap<Long, EnqueuedRequest>()
        private val taskJobs = ConcurrentHashMap<Long, Job>()
        private val activeProcessIds = ConcurrentHashMap<Long, String>()
        private val cancelledTaskIds = ConcurrentHashMap.newKeySet<Long>()

        fun enqueue(
            context: Context,
            urls: List<String>,
            mode: DownloadMode,
            settings: DownloaderSettings,
        ) {
            val cleanUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (cleanUrls.isEmpty()) return

            val appContext = context.applicationContext
            val outputDir = DownloaderSettingsStore.resolveOutputDirectory(settings)
            val createdTasks = mutableListOf<DownloadTaskItem>()
            val taskIds = LongArray(cleanUrls.size)

            cleanUrls.forEachIndexed { index, url ->
                val taskId = nextTaskId.getAndIncrement()
                taskIds[index] = taskId
                createdTasks.add(
                    DownloadTaskItem(
                        id = taskId,
                        url = url,
                        mode = mode,
                        outputDirectory = outputDir,
                        status = DownloadTaskStatus.QUEUED,
                        statusDetail = "In Warteschlange…",
                    ),
                )
                pendingRequests[taskId] = EnqueuedRequest(
                    taskId = taskId,
                    url = url,
                    mode = mode,
                    outputDirectory = outputDir,
                    settings = settings,
                )
            }

            _tasks.update { existing -> createdTasks + existing }

            val intent = Intent(appContext, MediaDownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_TASK_IDS, taskIds)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun cancel(context: Context, taskId: Long) {
            performCancel(taskId)
            if (isServiceAlive.get()) {
                val appContext = context.applicationContext
                val intent = Intent(appContext, MediaDownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_TASK_ID, taskId)
                }
                runCatching { appContext.startService(intent) }
            }
        }

        fun removeTask(taskId: Long) {
            val existing = _tasks.value.firstOrNull { it.id == taskId } ?: return
            if (existing.status.isActive) {
                performCancel(taskId)
            }
            _tasks.update { list -> list.filterNot { it.id == taskId } }
        }

        fun clearFinished() {
            _tasks.update { list -> list.filter { it.status.isActive } }
        }

        private fun performCancel(taskId: Long) {
            cancelledTaskIds.add(taskId)
            pendingRequests.remove(taskId)
            activeProcessIds[taskId]?.let { procId ->
                runCatching { YoutubeDL.getInstance().destroyProcessById(procId) }
            }
            taskJobs[taskId]?.cancel()
            updateTask(taskId) { current ->
                if (!current.status.isActive) {
                    current
                } else {
                    current.copy(
                        status = DownloadTaskStatus.CANCELLED,
                        progressPercent = -1f,
                        etaSeconds = -1L,
                        statusDetail = "Abgebrochen.",
                    )
                }
            }
        }

        private fun updateTask(taskId: Long, transform: (DownloadTaskItem) -> DownloadTaskItem) {
            _tasks.update { list ->
                list.map { item -> if (item.id == taskId) transform(item) else item }
            }
        }

        private fun progressNotificationId(taskId: Long): Int =
            NOTIFICATION_BASE_ID + ((taskId % 4000L).toInt() * 2)

        private fun resultNotificationId(taskId: Long): Int =
            progressNotificationId(taskId) + 1
    }
}
