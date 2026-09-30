package xyz.mdhv.asom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.Settings
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.server.ledger.BodySink
import xyz.mdhv.asom.service.AsomService
import xyz.mdhv.asom.ui.theme.AsomTokens

@Composable
fun StatusScreen() {
    val context = LocalContext.current
    val running by AsomService.running.collectAsState()
    val activity by AsomService.activity.collectAsState()
    val settings = remember { Settings(context) }
    var bootStart by remember { mutableStateOf(settings.bootStartEnabled) }
    val verbose by ServiceLocator.verboseMode.collectAsState()
    val startError by AsomService.startError.collectAsState()
    val ledgerDegraded by ServiceLocator.ledgerDegraded.collectAsState()
    // Binder call — re-read on daemon state changes and tab re-entry, not on
    // every recomposition.
    val notificationsEnabled = remember(running) { NotificationManagerCompat.from(context).areNotificationsEnabled() }

    Column(
        Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // §1.6: state = color + shape + label (never color alone).
        Text(
            text = if (running) {
                "${AsomTokens.StateGlyph.ACTIVE} daemon running"
            } else {
                "${AsomTokens.StateGlyph.INACTIVE} daemon stopped"
            },
            color = if (running) AsomTokens.Cyan else AsomTokens.Violet,
            style = MaterialTheme.typography.titleLarge,
        )
        if (running) {
            val activityText = when (val a = activity) {
                AsomService.Activity.Idle -> "idle"
                AsomService.Activity.Serving -> "serving a request…"
                is AsomService.Activity.Streaming -> "streaming via ${a.providerId}…"
            }
            Text(activityText, color = AsomTokens.OnSurfaceDim)
        }
        // §1.6: every warning carries a glyph and a label, never hue alone.
        startError?.let { Text("✕ $it", color = AsomTokens.Violet) }
        if (!notificationsEnabled) {
            Text(
                "⚠ notifications are off — the daemon runs with no visible indicator",
                color = AsomTokens.Violet,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (ledgerDegraded) {
            Text(
                "⚠ a ledger row could not be written — the ledger is incomplete",
                color = AsomTokens.Violet,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { AsomService.start(context) }, enabled = !running) {
                Text("Start")
            }
            OutlinedButton(onClick = { AsomService.stop(context) }, enabled = running) {
                Text("Stop")
            }
        }

        InfoRow("Endpoint", "http://${Asom.BIND_HOST}:${Asom.DEFAULT_PORT}")
        InfoRow("Version", Asom.VERSION)
        InfoRow("Catalogue version", ServiceLocator.catalogue.version.toString())
        InfoRow("Local engine", "absent (v1 — local-only returns 501)")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Start on boot")
                Text("Default OFF — nothing runs unless you start it", color = AsomTokens.OnSurfaceDim, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = bootStart,
                onCheckedChange = {
                    bootStart = it
                    settings.bootStartEnabled = it
                },
            )
        }

        // §9 verbose mode. The subtitle states exactly what is and is not
        // stored, so the toggle can never overpromise: streamed responses are
        // forwarded, never buffered, so only their metadata is recorded.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                // §1.6: state carries a glyph and a label, never hue alone.
                Text(if (verbose) "⦿ Verbose ledger mode — recording" else "Verbose ledger mode")
                Text(
                    "Default OFF. Stores request bodies and non-streamed response " +
                        "bodies on this device only, capped at ${BodySink.MAX_BODY_CHARS / 1024} KB " +
                        "each and purged after 24h. Streamed responses record metadata only — " +
                        "they are never buffered. Never exported, never uploaded.",
                    color = AsomTokens.OnSurfaceDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = verbose,
                onCheckedChange = { ServiceLocator.setVerboseMode(it) },
            )
        }

        Text("Device-owner bearer token (P6 replaces this with pairing):", color = AsomTokens.OnSurfaceDim)
        SelectionContainer {
            Text(ServiceLocator.devToken, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AsomTokens.OnSurfaceDim)
        Text(value)
    }
}
