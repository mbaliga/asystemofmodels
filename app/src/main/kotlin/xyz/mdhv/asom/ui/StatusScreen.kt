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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.service.AsomService
import xyz.mdhv.asom.ui.theme.AsomTokens

@Composable
fun StatusScreen() {
    val context = LocalContext.current
    val running by AsomService.running.collectAsState()

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
