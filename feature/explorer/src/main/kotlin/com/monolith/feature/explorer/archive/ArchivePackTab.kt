package com.monolith.feature.explorer.archive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.ui.components.MonolithSwitch
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PackArchiveContent(
    sourcePaths: List<String>,
    totalSourceBytes: Long,
    baseName: String,
    outputDir: String,
    format: ArchiveFormat,
    compressionLevel: ArchiveCompressionLevel,
    encryptionEnabled: Boolean,
    encryptionMethod: ArchiveEncryptionMethod,
    password: String,
    passwordConfirm: String,
    encryptFilenames: Boolean,
    splitEnabled: Boolean,
    splitPreset: SplitVolumePreset,
    splitCustomSelected: Boolean,
    splitCustomMb: String,
    solidArchive: Boolean,
    onPickSources: () -> Unit,
    onRemoveSource: (String) -> Unit,
    onClearSources: () -> Unit,
    onBaseNameChange: (String) -> Unit,
    onPickOutputDir: () -> Unit,
    onFormatChange: (ArchiveFormat) -> Unit,
    onCompressionChange: (ArchiveCompressionLevel) -> Unit,
    onEncryptionEnabledChange: (Boolean) -> Unit,
    onEncryptionMethodChange: (ArchiveEncryptionMethod) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onEncryptFilenamesChange: (Boolean) -> Unit,
    onSplitEnabledChange: (Boolean) -> Unit,
    onSplitPresetChange: (SplitVolumePreset) -> Unit,
    onSelectCustomSplit: () -> Unit,
    onCustomSplitMbChange: (String) -> Unit,
    onSolidArchiveChange: (Boolean) -> Unit,
    onCreateArchive: () -> Unit,
) {
    var showPwd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Unified Source Selection Card
        SectionCard(title = "Dateien & Ordner") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (sourcePaths.isEmpty()) {
                        "Keine Quellen ausgewählt."
                    } else {
                        "${sourcePaths.size} ausgewählt • ${formatArchiveBytes(totalSourceBytes)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sourcePaths.isNotEmpty()) {
                    TextButton(onClick = onClearSources) {
                        Text("Leeren")
                    }
                }
            }
            OutlinedButton(
                onClick = onPickSources,
                shape = LocalMonolithButtonShape.current,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Dateien & Ordner hinzufügen")
            }
            if (sourcePaths.isNotEmpty()) {
                val visibleChips = sourcePaths.take(20)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    visibleChips.forEach { path ->
                        val f = File(path)
                        FilterChip(
                            selected = false,
                            shape = LocalMonolithButtonShape.current,
                            onClick = { onRemoveSource(path) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (f.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Entfernen",
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                            label = {
                                Text(
                                    text = f.name.ifBlank { path },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                    if (sourcePaths.size > visibleChips.size) {
                        BadgeChip(
                            text = "+${sourcePaths.size - visibleChips.size} weitere",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        // 2. Archive Name & Output Directory
        SectionCard(title = "Name & Speicherort") {
            OutlinedTextField(
                value = baseName,
                onValueChange = onBaseNameChange,
                label = { Text("Archivname") },
                suffix = { Text(".${format.extension}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CutCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = onPickOutputDir,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Zielordner",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = outputDir,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "Ändern",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // 3. Format & Compression Level
        SectionCard(title = "Format & Kompression") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ArchiveFormat.entries.filter { it.canCreate }.forEach { fmt ->
                    FilterChip(
                        selected = format == fmt,
                        shape = LocalMonolithButtonShape.current,
                        onClick = { onFormatChange(fmt) },
                        label = { Text(fmt.label) },
                    )
                }
            }
            Text(
                text = "Kompressionsstufe",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ArchiveCompressionLevel.entries.forEach { level ->
                    FilterChip(
                        selected = compressionLevel == level,
                        shape = LocalMonolithButtonShape.current,
                        onClick = { onCompressionChange(level) },
                        label = { Text(level.label) },
                    )
                }
            }
        }

        // 4. Encryption Card
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = if (format.supportsEncryption && encryptionEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Verschlüsselung",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (format.supportsEncryption) {
                            "Archiv mit Passwort schützen."
                        } else {
                            "Für ${format.label} nicht unterstützt."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MonolithSwitch(
                    checked = format.supportsEncryption && encryptionEnabled,
                    onCheckedChange = onEncryptionEnabledChange,
                    enabled = format.supportsEncryption,
                )
            }

            AnimatedVisibility(visible = format.supportsEncryption && encryptionEnabled) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (format == ArchiveFormat.ZIP) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ArchiveEncryptionMethod.AES_256,
                                ArchiveEncryptionMethod.ZIP_STANDARD,
                            ).forEach { method ->
                                FilterChip(
                                    selected = encryptionMethod == method,
                                    shape = LocalMonolithButtonShape.current,
                                    onClick = { onEncryptionMethodChange(method) },
                                    label = { Text(method.label) },
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Verfahren: AES-256 (7z-Standard)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text("Passwort") },
                        singleLine = true,
                        visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPwd = !showPwd }) {
                                Icon(
                                    imageVector = if (showPwd) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showPwd) "Verbergen" else "Anzeigen",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = onPasswordConfirmChange,
                        label = { Text("Passwort bestätigen") },
                        singleLine = true,
                        isError = passwordConfirm.isNotEmpty() && password != passwordConfirm,
                        supportingText = if (passwordConfirm.isNotEmpty() && password != passwordConfirm) {
                            { Text("Passwörter stimmen nicht überein.") }
                        } else {
                            null
                        },
                        visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (format.supportsFilenameEncryption) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Dateinamen verschlüsseln",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "Verbirgt Dateinamen und Ordnerstruktur bis zur Passworteingabe.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            MonolithSwitch(
                                checked = encryptFilenames,
                                onCheckedChange = onEncryptFilenamesChange,
                            )
                        }
                    }
                }
            }
        }

        // 5. Split-Archive Card
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Inventory2,
                        contentDescription = null,
                        tint = if (splitEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Column {
                        Text(
                            text = "In Teile aufteilen (Split-Archiv)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Großes Archiv in handliche Teilarchive aufteilen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                MonolithSwitch(
                    checked = splitEnabled,
                    onCheckedChange = onSplitEnabledChange,
                )
            }

            AnimatedVisibility(visible = splitEnabled) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SplitVolumePreset.entries.filter { it.sizeBytes != null }.forEach { preset ->
                            FilterChip(
                                selected = !splitCustomSelected && splitPreset == preset,
                                shape = LocalMonolithButtonShape.current,
                                onClick = { onSplitPresetChange(preset) },
                                label = { Text(preset.label) },
                            )
                        }
                        FilterChip(
                            selected = splitCustomSelected,
                            shape = LocalMonolithButtonShape.current,
                            onClick = onSelectCustomSplit,
                            label = { Text("Eigene Größe") },
                        )
                    }
                    if (splitCustomSelected) {
                        OutlinedTextField(
                            value = splitCustomMb,
                            onValueChange = onCustomSplitMbChange,
                            label = { Text("Teilgröße in MB") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = if (format == ArchiveFormat.ZIP) {
                            "Erzeugt zusammenhängende Teile: .z01, .z02, … + .zip."
                        } else {
                            "Erzeugt zusammenhängende Teile: .${format.extension}.001, .${format.extension}.002, …"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // 6. Solid Compression (7z only)
        AnimatedVisibility(visible = format.supportsSolid) {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Solid-Komprimierung (7z)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Dateien als zusammenhängenden Datenstrom für höhere Kompression packen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MonolithSwitch(
                        checked = solidArchive,
                        onCheckedChange = onSolidArchiveChange,
                    )
                }
            }
        }

        // 7. Primary Create Action
        Button(
            onClick = onCreateArchive,
            enabled = sourcePaths.isNotEmpty() && baseName.isNotBlank(),
            shape = LocalMonolithButtonShape.current,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Archive,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Archiv erstellen")
        }
    }
}
