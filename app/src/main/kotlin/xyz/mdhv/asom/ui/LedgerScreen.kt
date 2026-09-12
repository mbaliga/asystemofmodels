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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.ledger.RouteLogEntity
import xyz.mdhv.asom.ui.theme.AsomTokens

/**
 * The watched object (§0/§9): every route — served or failed, local or
 * cloud — as a ledger row. Metadata only by default.
 */
@Composable
fun LedgerScreen() {
    val rows by remember { ServiceLocator.ledgerDb.dao().recent(200) }
        .collectAsState(initial = emptyList())

    if (rows.isEmpty()) {
        Text(
            "No routes yet. Start the daemon and send a request.",
            Modifier.padding(16.dp),
            color = AsomTokens.OnSurfaceDim,
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.id }) { row -> LedgerRow(row) }
    }
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
