package com.monolith.core.common

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Local-only diagnostics & crash logger (512 KiB rotating cap, zero external telemetry).
 */
object MonolithDiagnostics {
    private const val TAG = "MONOLITH_DIAG"
    private const val LOG_FILE_NAME = "monolith_diagnostics.log"
    private const val MAX_BYTES = 512L * 1024L

    private val lock = Any()
    @Volatile
    private var logFile: File? = null
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            logFile = File(context.applicationContext.filesDir, LOG_FILE_NAME)
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                logCrash(thread, throwable)
                previousHandler?.uncaughtException(thread, throwable)
            }
            initialized = true
            logEvent("DIAG_INIT")
        }
    }

    fun logEvent(name: String, details: String = "") {
        val line = if (details.isBlank()) "EVENT $name" else "EVENT $name $details"
        Log.i(TAG, line)
        appendLine(line)
    }

    fun logWarn(tag: String, message: String) {
        val line = "WARN [$tag] $message"
        Log.w(TAG, line)
        appendLine(line)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val trace = throwable?.let { "\n${stackTraceString(it)}" }.orEmpty()
        val line = "ERR [$tag] $message$trace"
        Log.e(TAG, line)
        appendLine(line)
    }

    private fun logCrash(thread: Thread, throwable: Throwable) {
        val line = "CRASH [${thread.name}]\n${stackTraceString(throwable)}"
        Log.e(TAG, line)
        appendLine(line)
    }

    private fun stackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    private fun appendLine(content: String) {
        val target = logFile ?: return
        synchronized(lock) {
            runCatching {
                val overwrite = target.exists() && target.length() > MAX_BYTES
                FileWriter(target, !overwrite).use { writer ->
                    writer.append("${System.currentTimeMillis()} $content\n")
                }
            }
        }
    }
}
