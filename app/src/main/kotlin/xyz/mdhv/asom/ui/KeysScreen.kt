package xyz.mdhv.asom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.catalogue.ProviderEntry
import xyz.mdhv.asom.ui.theme.AsomTokens

/**
 * THE only key write path in the entire system (invariant §1.4): BYOK keys
 * are entered here, Keystore-wrapped by the vault, and never accepted or
 * returned by any API. Stored keys are never redisplayed.
 */
@Composable
fun KeysScreen() {
    var refresh by remember { mutableIntStateOf(0) }
    var keyed by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(refresh) {
        keyed = withContext(Dispatchers.IO) { ServiceLocator.vault.providersWithKeys().toSet() }
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(ServiceLocator.catalogue.providers, key = { it.id }) { provider ->
            ProviderKeyCard(
                provider = provider,
                hasKey = provider.id in keyed,
                onChanged = { refresh++ },
            )
        }
    }
}

@Composable
private fun ProviderKeyCard(provider: ProviderEntry, hasKey: Boolean, onChanged: () -> Unit) {
    var draft by remember(provider.id) { mutableStateOf("") }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
                // §1.6: shape + label + hue, never color alone.
                Text(
                    text = if (hasKey) "${AsomTokens.StateGlyph.ACTIVE} key stored" else "${AsomTokens.StateGlyph.INACTIVE} no key",
                    color = if (hasKey) AsomTokens.Cyan else AsomTokens.Violet,
                )
            }
            if (provider.trainsOnData) {
                Text("⚠ trains on your data", color = AsomTokens.Violet, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(if (hasKey) "Replace key" else "Enter key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val value = draft
                        draft = ""
                        vaultIo(onChanged) { ServiceLocator.vault.storeKey(provider.id, value) }
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Text("Save")
                }
                if (hasKey) {
                    OutlinedButton(
                        onClick = { vaultIo(onChanged) { ServiceLocator.vault.deleteKey(provider.id) } },
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

/** Runs vault I/O off the main thread, then notifies the UI. */
private fun vaultIo(done: () -> Unit, block: () -> Unit) {
    ServiceLocator.scope.launch {
        block()
        withContext(Dispatchers.Main) { done() }
    }
}
