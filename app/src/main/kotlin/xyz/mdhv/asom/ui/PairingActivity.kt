package xyz.mdhv.asom.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.pairing.PairingEntity
import xyz.mdhv.asom.ui.theme.AsomTheme
import xyz.mdhv.asom.ui.theme.AsomTokens

/**
 * The consent sheet (§5.7): shows ONLY daemon-verified identity — app label,
 * package, signing-cert fingerprint from the AIDL bind's getCallingUid().
 * Nothing is read from the launching intent's extras (§5.7 landmine).
 * Dismissing without deciding leaves the request PENDING; no token minted.
 */
class PairingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AsomTheme {
                ConsentScreen(onDone = { finish() })
            }
        }
    }
}

@Composable
private fun ConsentScreen(onDone: () -> Unit) {
    var pending by remember { mutableStateOf<List<PairingEntity>?>(null) }
    val registry = remember { ServiceLocator.pairingRegistry }

    fun reload() {
        ServiceLocator.scope.launch {
            val rows = registry.pending()
            withContext(Dispatchers.Main) {
                pending = rows
                if (rows.isEmpty()) onDone()
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("AI hotspot — pairing request", style = MaterialTheme.typography.titleLarge)
        Text(
            "An app is asking to use this device's asom daemon. Its identity below " +
                "was verified from the system binder — not self-reported.",
            color = AsomTokens.OnSurfaceDim,
        )

        pending?.forEach { request ->
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(request.label, style = MaterialTheme.typography.titleMedium)
                    Text(request.packageName, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "cert ${request.certHash.chunked(2).joinToString(":").uppercase().take(29)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = AsomTokens.OnSurfaceDim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            ServiceLocator.scope.launch {
                                registry.approve(request.packageName, request.certHash)
                                withContext(Dispatchers.Main) { reload() }
                            }
                        }) {
                            Text("Approve")
                        }
                        OutlinedButton(onClick = {
                            ServiceLocator.scope.launch {
                                registry.deny(request.packageName, request.certHash)
                                withContext(Dispatchers.Main) { reload() }
                            }
                        }) {
                            Text("Deny")
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = onDone) {
            Text("Decide later") // stays PENDING (§5.7)
        }
    }
}
