package xyz.mdhv.asom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.contract.PairingStatusCode
import xyz.mdhv.asom.pairing.PairingEntity
import xyz.mdhv.asom.ui.theme.AsomTokens

/**
 * The Hotspot tab (§5.7): paired apps, live status, immediate revocation —
 * the server checks the store per request, so revoke takes effect on the
 * next call.
 */
@Composable
fun HotspotScreen() {
    val pairings by remember { ServiceLocator.pairingDb.dao().observeAll() }
        .collectAsState(initial = emptyList())

    if (pairings.isEmpty()) {
        Text(
            "No paired apps yet. Apps join via the AI-hotspot pairing flow.",
            Modifier.padding(16.dp),
            color = AsomTokens.OnSurfaceDim,
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(pairings, key = { it.packageName + it.certHash }) { pairing ->
            PairingCard(pairing)
        }
    }
}

@Composable
private fun PairingCard(pairing: PairingEntity) {
    val registry = ServiceLocator.pairingRegistry

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(pairing.label, style = MaterialTheme.typography.titleMedium)
                // §1.6: shape + label + hue.
                when (pairing.status) {
                    PairingStatusCode.PAIRED -> Text("${AsomTokens.StateGlyph.ACTIVE} paired", color = AsomTokens.Cyan)
                    PairingStatusCode.PENDING -> Text("◐ pending", color = AsomTokens.Violet)
                    PairingStatusCode.REVOKED -> Text("✕ revoked", color = AsomTokens.Violet)
                    else -> Text("${AsomTokens.StateGlyph.INACTIVE} unknown", color = AsomTokens.OnSurfaceDim)
                }
            }
            Text(pairing.packageName, style = MaterialTheme.typography.bodySmall)
            Text(
                "cert ${pairing.certHash.take(16)}…",
                style = MaterialTheme.typography.bodySmall,
                color = AsomTokens.OnSurfaceDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pairing.status == PairingStatusCode.PAIRED) {
                    OutlinedButton(onClick = {
                        ServiceLocator.scope.launch {
                            registry.revoke(pairing.packageName, pairing.certHash)
                        }
                    }) {
                        Text("Revoke")
                    }
                }
                OutlinedButton(onClick = {
                    ServiceLocator.scope.launch {
                        registry.remove(pairing.packageName, pairing.certHash)
                    }
                }) {
                    Text("Remove")
                }
            }
        }
    }
}
