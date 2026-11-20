package com.monolith.feature.explorer.archive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import java.io.File

enum class ArchiveToolMode(val label: String) {
    MANAGE("Verwalten"),
    PACK("Packen"),
    EXTRACT("Entpacken"),
}

data class ArchiveLaunchRequest(
    val nonce: Long = 0L,
    val mode: ArchiveToolMode? = null,
    val archivePath: String? = null,
    val packSourcePaths: List<String> = emptyList(),
)

@Stable
class ArchiveManagerState(
    val defaultRootDir: String,
) {
    var activeMode by mutableStateOf(ArchiveToolMode.MANAGE)
    var lastConsumedLaunchNonce by mutableLongStateOf(0L)

    val rememberedPasswords = mutableStateMapOf<String, String>()

    // Manage mode state
    var discoveredArchives by mutableStateOf<List<ArchiveVolumeGroup>>(emptyList())
    var reachedScanLimit by mutableStateOf(false)
    var isScanningArchives by mutableStateOf(false)
    var scanTrigger by mutableIntStateOf(0)
    var archiveSearchQuery by mutableStateOf("")
    var openedSummary by mutableStateOf<ArchiveSummary?>(null)
    var isInspecting by mutableStateOf(false)
    var archiveInternalPath by mutableStateOf("")
    var selectedEntryPaths by mutableStateOf<Set<String>>(emptySet())

    // Active operation state (kept alive across tab switches)
    var activeProgress by mutableStateOf<ArchiveOperationProgress?>(null)
    var activeJob by mutableStateOf<Job?>(null)

    // Pack mode state
    var packSourcePaths by mutableStateOf<List<String>>(emptyList())
    var packTotalSourceBytes by mutableLongStateOf(0L)
    var packBaseName by mutableStateOf("Archiv")
    var packBaseNameEdited by mutableStateOf(false)
    var packOutputDir by mutableStateOf(defaultRootDir)
    var packFormat by mutableStateOf(ArchiveFormat.ZIP)
    var packCompression by mutableStateOf(ArchiveCompressionLevel.NORMAL)
    var packEncryptionEnabled by mutableStateOf(false)
    var packEncryptionMethod by mutableStateOf(ArchiveEncryptionMethod.AES_256)
    var packPassword by mutableStateOf("")
    var packPasswordConfirm by mutableStateOf("")
    var packSplitEnabled by mutableStateOf(false)
    var packSplitPreset by mutableStateOf(SplitVolumePreset.MB_50)
    var packSplitCustomSelected by mutableStateOf(false)
    var packSplitCustomMb by mutableStateOf("25")
    var packSolidArchive by mutableStateOf(true)

    // Extract mode state
    var extractArchivePath by mutableStateOf("")
    var extractArchiveGroup by mutableStateOf<ArchiveVolumeGroup?>(null)
    var extractTargetDir by mutableStateOf(defaultRootDir)
    var extractPassword by mutableStateOf("")
    var extractCreateSubfolder by mutableStateOf(true)
    var extractConflictPolicy by mutableStateOf(ExtractConflictPolicy.RENAME)
    var extractDeleteAfter by mutableStateOf(false)

    fun passwordKeyFor(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    fun resolvePasswordFor(group: ArchiveVolumeGroup): String? =
        rememberedPasswords[passwordKeyFor(group.primaryFile)]

    fun deriveDefaultPackName(paths: List<String>): String {
        if (paths.isEmpty()) return "Archiv"
        if (paths.size == 1) {
            val file = File(paths.first())
            return (if (file.isDirectory) file.name else file.nameWithoutExtension)
                .ifBlank { file.name }
                .ifBlank { "Archiv" }
        }
        val firstParent = File(paths.first()).parentFile?.name
        return firstParent?.takeIf { it.isNotBlank() } ?: "Archiv"
    }

    fun applyPackSources(paths: List<String>) {
        packSourcePaths = paths.distinct()
        if (!packBaseNameEdited) {
            packBaseName = deriveDefaultPackName(packSourcePaths)
        }
        packSourcePaths.firstOrNull()?.let { first ->
            File(first).parent?.let { parent -> packOutputDir = parent }
        }
    }

    fun prepareExtractFor(archiveFile: File) {
        extractArchivePath = archiveFile.absolutePath
        extractTargetDir = archiveFile.parent ?: defaultRootDir
        val group = ArchiveEngine.resolveVolumeGroup(archiveFile)
        extractArchiveGroup = group
        resolvePasswordFor(group)?.let { saved ->
            if (extractPassword.isEmpty()) extractPassword = saved
        }
    }
}

@Composable
fun rememberArchiveManagerState(
    defaultRootDir: String,
): ArchiveManagerState = remember(defaultRootDir) {
    ArchiveManagerState(defaultRootDir = defaultRootDir)
}
