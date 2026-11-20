package com.monolith.feature.explorer.archive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.ui.components.MonolithDialogOption
import com.monolith.core.ui.components.MonolithSwitch
import com.monolith.core.ui.theme.LocalMonolithButtonShape
import java.io.File

@Composable
internal fun ExtractArchiveContent(
    archivePath: String,
    volumeGroup: ArchiveVolumeGroup?,
    targetDir: String,
    password: String,
    createSubfolder: Boolean,
    conflictPolicy: ExtractConflictPolicy,
    deleteAfterExtract: Boolean,
    onPickArchive: () -> Unit,
    onPickTargetDir: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onCreateSubfolderChange: (Boolean) -> Unit,
    onConflictPolicyChange: (ExtractConflictPolicy) -> Unit,
    onDeleteAfterExtractChange: (Boolean) -> Unit,
    onExtractNow: () -> Unit,
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
        // 1. Source Archive
        SectionCard(title = "Quellarchiv") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CutCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = onPickArchive,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = volumeGroup?.displayName
                                ?: archivePath.takeIf { it.isNotBlank() }?.let { File(it).name }
                                ?: "Kein Archiv ausgewählt",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (volumeGroup != null) {
                                "${volumeGroup.format.label} • ${formatArchiveBytes(volumeGroup.totalSizeBytes)}"
                            } else {
                                "Tippen, um ZIP, 7z, RAR oder TAR zu wählen"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Wählen",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (volumeGroup != null && (volumeGroup.isMultiVolume || volumeGroup.isMissingParts)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CutCornerShape(8.dp),
                    color = if (volumeGroup.isMissingParts) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (volumeGroup.isMissingParts) {
                                Icons.Outlined.WarningAmber
                            } else {
                                Icons.Outlined.Inventory2
                            },
                            contentDescription = null,
                            tint = if (volumeGroup.isMissingParts) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (volumeGroup.isMissingParts) {
                                "Warnung: Teile dieses Split-Archivs fehlen!"
                            } else {
                                "${volumeGroup.parts.size} verbundene Teilarchive erkannt (${formatArchiveBytes(volumeGroup.totalSizeBytes)})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // 2. Target Folder
        SectionCard(title = "Zielordner") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CutCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = onPickTargetDir,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
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
                            text = File(targetDir).name.ifBlank { targetDir },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = targetDir,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // 3. Optional Password
        SectionCard(title = "Passwort (optional)") {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Passwort, falls geschützt") },
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
        }

        // 4. Options & Conflict Policy
        SectionCard(title = "Optionen") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "In eigenen Unterordner entpacken",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Erstellt automatisch einen Ordner mit dem Archivnamen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MonolithSwitch(
                    checked = createSubfolder,
                    onCheckedChange = onCreateSubfolderChange,
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "Bei vorhandenen Dateien",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExtractConflictPolicy.entries.forEach { policy ->
                MonolithDialogOption(
                    label = policy.label,
                    selected = conflictPolicy == policy,
                    onClick = { onConflictPolicyChange(policy) },
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Archiv nach Entpacken löschen",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (volumeGroup != null && volumeGroup.isMultiVolume) {
                            "Löscht alle ${volumeGroup.parts.size} Teilarchive nach Erfolg"
                        } else {
                            "Entfernt die Archivdatei nach erfolgreichem Entpacken"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (deleteAfterExtract) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                MonolithSwitch(
                    checked = deleteAfterExtract,
                    onCheckedChange = onDeleteAfterExtractChange,
                )
            }
        }

        Button(
            onClick = onExtractNow,
            enabled = archivePath.isNotBlank() && volumeGroup != null && !volumeGroup.isMissingParts,
            shape = LocalMonolithButtonShape.current,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Unarchive,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Jetzt entpacken")
        }
    }
}
