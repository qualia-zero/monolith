package com.monolith.feature.explorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monolith.core.ui.components.MonolithDialog
import com.monolith.core.ui.components.MonolithSwitch
import com.monolith.core.vfs.DiscoveredSmbHost
import com.monolith.core.vfs.SmbNetworkDiscovery
import com.monolith.core.vfs.SmbServerConfig
import com.monolith.core.vfs.SmbVfsProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AddStorageTypeDialog(
    onSelectGoogleDrive: () -> Unit,
    onSelectSmb: () -> Unit,
    onDismiss: () -> Unit,
) {
    MonolithDialog(
        onDismissRequest = onDismiss,
        title = "Speicher hinzufügen",
        dismissLabel = "Abbrechen",
        onDismissClick = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StorageTypeCard(
                icon = Icons.Outlined.CloudQueue,
                title = "Google Drive",
                subtitle = "Mit Google-Konto anmelden.",
                onClick = onSelectGoogleDrive,
            )
            StorageTypeCard(
                icon = Icons.Outlined.Dns,
                title = "SMB-Netzwerk",
                subtitle = "Automatisch im LAN suchen oder manuell verbinden.",
                onClick = onSelectSmb,
            )
        }
    }
}

@Composable
private fun StorageTypeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CutCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SmbConnectionDialog(
    editingConfig: SmbServerConfig? = null,
    existingServers: List<SmbServerConfig>,
    onSaveServer: (SmbServerConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val smbProvider = remember(context) { SmbVfsProvider(context.applicationContext) }

    var activeEditId by remember(editingConfig) { mutableStateOf(editingConfig?.id) }
    var host by remember(editingConfig) { mutableStateOf(editingConfig?.host.orEmpty()) }
    var portText by remember(editingConfig) { mutableStateOf((editingConfig?.port ?: 445).toString()) }
    var share by remember(editingConfig) { mutableStateOf(editingConfig?.share.orEmpty()) }
    var displayName by remember(editingConfig) { mutableStateOf(editingConfig?.displayName.orEmpty()) }
    var isGuest by remember(editingConfig) { mutableStateOf(editingConfig?.isGuest ?: true) }
    var username by remember(editingConfig) { mutableStateOf(editingConfig?.username.orEmpty()) }
    var password by remember(editingConfig) { mutableStateOf(editingConfig?.password.orEmpty()) }
    var domain by remember(editingConfig) { mutableStateOf(editingConfig?.domain.orEmpty()) }

    var discoveredHosts by remember { mutableStateOf<List<DiscoveredSmbHost>>(emptyList()) }
    var isScanning by remember { mutableStateOf(editingConfig == null) }
    var scanNonce by remember { mutableIntStateOf(0) }
    var isTesting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (editingConfig == null) {
        LaunchedEffect(scanNonce) {
            isScanning = true
            try {
                withTimeoutOrNull(5_000L) {
                    SmbNetworkDiscovery.discover(context).collect { hosts ->
                        discoveredHosts = hosts
                    }
                }
            } finally {
                isScanning = false
            }
        }
    }

    val hostTrimmed = host.trim()
    val canSubmit = hostTrimmed.isNotEmpty() && !isTesting

    MonolithDialog(
        onDismissRequest = { if (!isTesting) onDismiss() },
        title = if (editingConfig != null) "SMB bearbeiten" else "SMB-Netzwerk",
        wide = true,
        dismissLabel = "Abbrechen",
        dismissEnabled = !isTesting,
        onDismissClick = onDismiss,
        confirmLabel = when {
            isTesting -> "Prüfe…"
            editingConfig != null -> "Speichern"
            else -> "Verbinden"
        },
        confirmEnabled = canSubmit,
        onConfirmClick = {
            val parsedPort = portText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 445
            val candidate = SmbServerConfig(
                id = activeEditId ?: java.util.UUID.randomUUID().toString(),
                displayName = displayName.trim().ifEmpty { hostTrimmed },
                host = hostTrimmed,
                port = parsedPort,
                share = share.trim('/'),
                username = if (isGuest) "" else username.trim(),
                password = if (isGuest) "" else password,
                domain = if (isGuest) "" else domain.trim(),
                isGuest = isGuest,
            )
            errorMessage = null
            isTesting = true
            scope.launch {
                val res = smbProvider.testConnection(candidate)
                isTesting = false
                res.onSuccess {
                    onSaveServer(candidate)
                }.onFailure { err ->
                    errorMessage = err.message ?: "Verbindung fehlgeschlagen."
                }
            }
        },
    ) {
        if (editingConfig == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Im Netzwerk gefunden",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    IconButton(
                        onClick = { scanNonce++ },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Erneut suchen.",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (discoveredHosts.isEmpty()) {
                Text(
                    text = if (isScanning) {
                        "Suche nach SMB-Geräten im lokalen Netzwerk…"
                    } else {
                        "Keine SMB-Geräte gefunden. Du kannst unten manuell verbinden."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Tipp auf ein gefundenes Gerät, um die Daten zu übernehmen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                discoveredHosts.forEach { discovered ->
                    val matchingSaved = existingServers.firstOrNull {
                        it.host.equals(discovered.ip, ignoreCase = true) && it.port == discovered.port
                    }
                    val isSelected = host.trim().equals(discovered.ip, ignoreCase = true) &&
                        (portText.trim().toIntOrNull() ?: 445) == discovered.port

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CutCornerShape(8.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            width = if (isSelected) 1.3.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val prevHost = host.trim()
                                    val shouldUpdateDisplayName = displayName.isBlank() ||
                                        displayName.trim() == prevHost ||
                                        discoveredHosts.any { it.displayName == displayName.trim() || it.ip == displayName.trim() }
                                    activeEditId = matchingSaved?.id
                                    host = matchingSaved?.host ?: discovered.ip
                                    portText = (matchingSaved?.port ?: discovered.port).toString()
                                    share = matchingSaved?.share.orEmpty()
                                    if (matchingSaved != null) {
                                        displayName = matchingSaved.displayName
                                    } else if (shouldUpdateDisplayName) {
                                        displayName = discovered.displayName
                                    }
                                    isGuest = matchingSaved?.isGuest ?: true
                                    username = matchingSaved?.username.orEmpty()
                                    password = matchingSaved?.password.orEmpty()
                                    domain = matchingSaved?.domain.orEmpty()
                                    errorMessage = null
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = discovered.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${discovered.ip}:${discovered.port} · ${discovered.source}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (matchingSaved != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = "Bereits gespeichert.",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "Verbindungsdaten",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it; errorMessage = null },
            label = { Text("Host / IP-Adresse") },
            placeholder = { Text("z. B. 192.168.1.10 oder nas.local") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Name (optional)") },
            placeholder = { Text("z. B. NAS oder Wohnzimmer-PC") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp),
            )
            OutlinedTextField(
                value = share,
                onValueChange = { share = it },
                label = { Text("Freigabe (optional)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Gast / Anonym",
                style = MaterialTheme.typography.bodyMedium,
            )
            MonolithSwitch(
                checked = isGuest,
                onCheckedChange = {
                    isGuest = it
                    errorMessage = null
                },
            )
        }
        if (!isGuest) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; errorMessage = null },
                label = { Text("Benutzername") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("Passwort") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("Domäne (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        errorMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
