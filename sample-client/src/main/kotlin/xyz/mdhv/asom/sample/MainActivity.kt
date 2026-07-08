package xyz.mdhv.asom.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mdhv.asom.client.AsomDiscovery
import xyz.mdhv.asom.client.FallbackResolver
import xyz.mdhv.asom.client.NudgePolicy
import xyz.mdhv.asom.client.SuiteInventory
import xyz.mdhv.asom.clientcloud.CloudOnly
import xyz.mdhv.asom.clientcloud.CloudProvider

/**
 * End-to-end §10A proof app: discover → pair → chat through whatever tier
 * [FallbackResolver] picks (RemoteAsom when asom is present + paired,
 * CloudOnly otherwise — including after asom is uninstalled mid-session).
 * This app holds no models and no engine; in CloudOnly mode it holds ITS OWN
 * key, entered here (§10A.3 — keys never transfer programmatically).
 */
class MainActivity : ComponentActivity() {

    /** OWNER-FILL: first-party suite packages (§10A.4). Empty = nudges off. */
    private val suitePackages: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cloudOnly = CloudOnly(
            this,
            providers = listOf(
                CloudProvider("openrouter", "https://openrouter.ai/api/v1", listOf("llama-3.3-70b")),
            ),
        )
        val resolver = FallbackResolver(this, embedded = null, cloudOnly = cloudOnly)

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    SampleScreen(Modifier.padding(padding), resolver, cloudOnly, suitePackages, this)
                }
            }
        }
    }
}

@Composable
private fun SampleScreen(
    modifier: Modifier,
    resolver: FallbackResolver,
    cloudOnly: CloudOnly,
    suitePackages: List<String>,
    activity: ComponentActivity,
) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("asom sample client (§10A tiers)\n") }
    var prompt by remember { mutableStateOf("Say hello from the AI hotspot!") }
    var cloudKey by remember { mutableStateOf("") }

    fun append(line: String) {
        log += line + "\n"
    }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val endpoint = withContext(Dispatchers.IO) { AsomDiscovery.discover(activity) }
                    append(
                        endpoint?.let { "asom: port=${it.port} v=${it.version} localEngine=${it.capabilities.hasLocalEngine}" }
                            ?: "asom not installed",
                    )
                    val resolved = resolver.resolve()
                    append("resolved tier: ${resolved.tier}")

                    // §10A.5 nudge demo — fires only under the spec conditions.
                    val summary = withContext(Dispatchers.IO) { SuiteInventory.query(activity, suitePackages) }
                    val decision = NudgePolicy().decide(
                        NudgePolicy.Signals(
                            asomInstalled = endpoint != null,
                            asomPaired = resolver.remoteAsom().available(),
                            suiteAppCount = summary.suiteAppCount,
                            reclaimableBytes = summary.reclaimableBytes,
                            appLabels = summary.appLabels,
                            holdsLocalKeyOrModel = cloudOnly.vault.providersWithKeys().isNotEmpty(),
                        ),
                        NudgePolicy.State(), // demo: fresh state
                        nowMs = System.currentTimeMillis(),
                    )
                    append("nudge decision: $decision")
                }
            }) {
                Text("Status")
            }

            Button(onClick = {
                scope.launch {
                    append("pairing…")
                    append("pairing result: ${resolver.remoteAsom().pairing.pair(activity)}")
                }
            }) {
                Text("Pair")
            }
        }

        OutlinedTextField(
            value = cloudKey,
            onValueChange = { cloudKey = it },
            label = { Text("CloudOnly key for openrouter (this app's own vault)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedButton(
            onClick = {
                val k = cloudKey
                cloudKey = ""
                scope.launch(Dispatchers.IO) { cloudOnly.vault.storeKey("openrouter", k) }
                append("stored CloudOnly key (this app's vault, human-entered — §10A.3)")
            },
            enabled = cloudKey.isNotBlank(),
        ) {
            Text("Save CloudOnly key")
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = {
            scope.launch {
                val resolved = resolver.resolve()
                val client = resolved.client
                if (client == null) {
                    append("no tier available (no asom, no cloud key)")
                    return@launch
                }
                append("→ streaming via ${resolved.tier} (model=${if (resolved.tier == FallbackResolver.Tier.REMOTE_ASOM) "cheapest" else "llama-3.3-70b"}) …")
                val model = if (resolved.tier == FallbackResolver.Tier.REMOTE_ASOM) "cheapest" else "llama-3.3-70b"
                val body = """{"model":"$model","messages":[{"role":"user","content":${jsonString(prompt)}}]}"""
                try {
                    withContext(Dispatchers.IO) {
                        val stream = client.chatStream(body)
                        val text = StringBuilder()
                        stream.chunks.collect { chunk ->
                            val delta = Regex("\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                                .find(chunk)?.groupValues?.get(1) ?: ""
                            text.append(delta.replace("\\n", "\n").replace("\\\"", "\""))
                        }
                        withContext(Dispatchers.Main) {
                            append("served-by: ${stream.headers.servedBy} · egress: ${stream.headers.egress}")
                            append("← $text")
                        }
                    }
                } catch (e: Exception) {
                    append("error: ${e.message}")
                }
            }
        }) {
            Text("Send (stream)")
        }

        Text(log, style = MaterialTheme.typography.bodySmall)
    }
}

private fun jsonString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
