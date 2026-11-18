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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val usbDeviceName = attachedUsbMassStorageName(context)
        val hasUsbMassStorage = usbDeviceName != null || hasAttachedUsbMassStorage(context)

        return storageManager.storageVolumes.mapNotNull { volume ->
            if (volume.isPrimary) return@mapNotNull null
            val state = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                return@mapNotNull null
            }
            val dir = resolveVolumeDirectory(volume) ?: return@mapNotNull null
            if (!dir.exists() || !dir.isDirectory) return@mapNotNull null

            val description = volume.getDescription(context)?.trim().orEmpty()
            val kind = classifyVolumeKind(description, hasUsbMassStorage)
            val label = resolveVolumeLabel(
                description = description,
                uuid = volume.uuid,
                dirName = dir.name,
                kind = kind,
                usbDeviceName = usbDeviceName,
            )
            ExternalStorageVolume(
                id = volume.uuid ?: dir.absolutePath,
                name = label,
                rootPath = dir.absolutePath,
                kind = kind,
            )
        }
    }

    private fun resolveVolumeLabel(
        description: String,
        uuid: String?,
        dirName: String,
        kind: ExternalVolumeKind,
        usbDeviceName: String?,
    ): String {
        val isGenericUsbDesc = description.equals("USB-Laufwerk", ignoreCase = true) ||
            description.equals("USB drive", ignoreCase = true) ||
            description.equals("USB storage", ignoreCase = true) ||
            description.equals("Mass Storage", ignoreCase = true) ||
            description.matches(Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$"))

        if (kind == ExternalVolumeKind.USB) {
            if (description.isNotBlank() && !isGenericUsbDesc) return description
            if (!usbDeviceName.isNullOrBlank()) return usbDeviceName
            return "USB-Speicher"
        }

        return description.ifBlank {
            uuid?.takeIf { it.isNotBlank() } ?: dirName
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

    private fun attachedUsbMassStorageName(context: Context): String? {
        val usbManager = context.getSystemService(UsbManager::class.java) ?: return null
        val device = usbManager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { idx ->
                dev.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
            }
        } ?: return null

        val product = device.productName?.trim().orEmpty()
        val manufacturer = device.manufacturerName?.trim().orEmpty()
        return when {
            product.isNotBlank() && manufacturer.isNotBlank() &&
                !product.contains(manufacturer, ignoreCase = true) -> "$manufacturer $product"
            product.isNotBlank() -> product
            manufacturer.isNotBlank() -> "$manufacturer USB"
            else -> null
        }
    }
}

@Composable
fun rememberExternalStorageVolumes(context: Context): List<ExternalStorageVolume> {
    var volumes by remember { mutableStateOf<List<ExternalStorageVolume>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(context) {
        volumes = withContext(Dispatchers.IO) {
            ExternalStorageDetector.listMountedVolumes(context.applicationContext)
        }
    }

    DisposableEffect(context) {
        val appContext = context.applicationContext
        var refreshJob: Job? = null
        val refresh = {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                volumes = withContext(Dispatchers.IO) {
                    ExternalStorageDetector.listMountedVolumes(appContext)
                }
            }
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
            refreshJob?.cancel()
            runCatching { appContext.unregisterReceiver(receiver) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && storageManager != null && volumeCallback != null) {
                runCatching { storageManager.unregisterStorageVolumeCallback(volumeCallback) }
            }
        }
    }

    return volumes
}
