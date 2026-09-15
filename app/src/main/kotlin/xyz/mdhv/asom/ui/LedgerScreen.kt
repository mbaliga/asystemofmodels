package xyz.mdhv.asom.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.ledger.LedgerExport
import xyz.mdhv.asom.ledger.RouteLogEntity
import xyz.mdhv.asom.ui.theme.AsomTokens

/**
 * The watched object (§0/§9): every route — served or failed, local or
 * cloud — as a ledger row. Metadata only by default.
 *
 * Export is the ONE sanctioned egress action in v1 (§1.1), so the exact
 * payload is rendered for review before any share intent is built.
 */
@Composable
fun LedgerScreen() {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val rows by remember { ServiceLocator.ledgerDb.dao().recent(200) }
        .collectAsState(initial = emptyList())
    var payload by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${rows.size} recent routes", color = AsomTokens.OnSurfaceDim)
            OutlinedButton(onClick = {
                uiScope.launch {
                    payload = withContext(Dispatchers.IO) {
                        LedgerExport.toJson(ServiceLocator.ledgerDb.dao().allForExport())
                    }
                }
            }) {
                Text("Export…")
            }
        }

        if (rows.isEmpty()) {
            Text(
                "No routes yet. Start the daemon and send a request.",
                Modifier.padding(16.dp),
                color = AsomTokens.OnSurfaceDim,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { row -> LedgerRow(row) }
            }
        }
    }

    payload?.let { json ->
        ExportPreviewDialog(
            payload = json,
            onDismiss = { payload = null },
            onShare = {
                payload = null
                uiScope.launch {
                    val uri = withContext(Dispatchers.IO) { writeExportFile(context, json) }
                    context.startActivity(shareIntent(uri))
                }
            },
        )
    }
}

/**
 * §1.1: the user sees the exact bytes that would leave the device, in full,
 * before the share sheet is ever built. Nothing is written to disk until they
 * confirm.
 */
@Composable
private fun ExportPreviewDialog(payload: String, onDismiss: () -> Unit, onShare: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export ledger") },
        text = {
            // Line-wise and lazy: the payload is the whole ledger, which one
            // Text composable would have to lay out in a single pass.
            val lines = remember(payload) { payload.lines() }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Text(
                        "This is the exact payload that will be shared. " +
                            "Nothing leaves the device until you pick an app.",
                        color = AsomTokens.OnSurfaceDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(lines.size) { index ->
                    Text(lines[index], style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun writeExportFile(context: Context, payload: String): Uri {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val file = File(dir, "asom-ledger.json").apply { writeText(payload) }
    return FileProvider.getUriForFile(context, "${context.packageName}.export", file)
}

private fun shareIntent(uri: Uri): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "Export ledger").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

@Composable
private fun LedgerRow(row: RouteLogEntity) {
    val time = remember(row.ts) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(row.ts))
    }
    val cloud = row.egress == "cloud"

    Card {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    // §1.6 shape+label redundancy for the egress state.
                    text = (if (cloud) "▲ cloud" else "■ ${row.egress}"),
                    color = if (cloud) AsomTokens.Violet else AsomTokens.Cyan,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(time, color = AsomTokens.OnSurfaceDim, style = MaterialTheme.typography.labelSmall)
            }
            Text("${row.callerPkg} → ${row.requestedModel}")
            val served = row.servedProvider?.let { "${it}/${row.servedModel}" } ?: "—"
            Text("served by $served · HTTP ${row.status} · ${row.latencyMs}ms", style = MaterialTheme.typography.bodySmall)
            val cost = row.costEst?.let { "${RouteRecord.formatUsd(it)} USD (${row.costBasis})" } ?: "cost n/a"
            Text(
                "tokens ${row.tokensIn ?: "?"}→${row.tokensOut ?: "?"} · $cost · ${row.bytesOut}B out",
                style = MaterialTheme.typography.bodySmall,
                color = AsomTokens.OnSurfaceDim,
            )
        }
    }
}
