package com.monolith.feature.explorer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

enum class ExternalVolumeKind {
    SD_CARD,
    USB,
}

data class ExternalStorageVolume(
    val id: String,
    val name: String,
    val rootPath: String,
    val kind: ExternalVolumeKind,
)

object ExternalStorageDetector {

    fun listMountedVolumes(context: Context): List<ExternalStorageVolume> {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        val hasUsbMassStorage = hasAttachedUsbMassStorage(context)

        return storageManager.storageVolumes.mapNotNull { volume ->
            if (volume.isPrimary) return@mapNotNull null
            val state = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                return@mapNotNull null
            }
            val dir = resolveVolumeDirectory(volume) ?: return@mapNotNull null
            if (!dir.exists() || !dir.isDirectory) return@mapNotNull null

            val description = volume.getDescription(context)?.trim().orEmpty()
            val label = description.ifBlank {
                volume.uuid?.takeIf { it.isNotBlank() } ?: dir.name
            }
            val kind = classifyVolumeKind(description, hasUsbMassStorage)
            ExternalStorageVolume(
                id = volume.uuid ?: dir.absolutePath,
                name = label,
                rootPath = dir.absolutePath,
                kind = kind,
            )
        }
    }

    private fun resolveVolumeDirectory(volume: StorageVolume): java.io.File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.let { return it }
        }
        val uuid = volume.uuid?.takeIf { it.isNotBlank() } ?: return null
        return java.io.File("/storage/$uuid")
    }

    private fun classifyVolumeKind(description: String, hasUsbMassStorage: Boolean): ExternalVolumeKind {
        val lower = description.lowercase()
        return when {
            lower.contains("usb") ||
                lower.contains("otg") ||
                lower.contains("flash") ||
                lower.contains("stick") ||
                lower.contains("ssd") ||
                lower.contains("hdd") -> ExternalVolumeKind.USB
            lower.contains("sd") -> ExternalVolumeKind.SD_CARD
            hasUsbMassStorage -> ExternalVolumeKind.USB
            else -> ExternalVolumeKind.SD_CARD
        }
    }

    private fun hasAttachedUsbMassStorage(context: Context): Boolean {
        val usbManager = context.getSystemService(UsbManager::class.java) ?: return false
        return usbManager.deviceList.values.any { device ->
            (0 until device.interfaceCount).any { idx ->
                device.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
            }
        }
    }
}

@Composable
fun rememberExternalStorageVolumes(context: Context): List<ExternalStorageVolume> {
    var volumes by remember { mutableStateOf(ExternalStorageDetector.listMountedVolumes(context)) }

    DisposableEffect(context) {
        val appContext = context.applicationContext
        val refresh = {
            volumes = ExternalStorageDetector.listMountedVolumes(appContext)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refresh()
            }
        }

        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(appContext, receiver, mediaFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(appContext, receiver, usbFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val storageManager = appContext.getSystemService(StorageManager::class.java)
        val volumeCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && storageManager != null) {
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    refresh()
                }
            }.also { cb ->
                storageManager.registerStorageVolumeCallback(ContextCompat.getMainExecutor(appContext), cb)
            }
        } else {
            null
        }

        onDispose {
            runCatching { appContext.unregisterReceiver(receiver) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && storageManager != null && volumeCallback != null) {
                runCatching { storageManager.unregisterStorageVolumeCallback(volumeCallback) }
            }
        }
    }

    return volumes
}
