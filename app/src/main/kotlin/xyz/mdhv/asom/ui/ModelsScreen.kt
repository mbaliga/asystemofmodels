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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.mdhv.asom.ServiceLocator
import xyz.mdhv.asom.catalogue.ModelEntry
import xyz.mdhv.asom.storage.DownloadStatus
import xyz.mdhv.asom.storage.ModelDownloadEntity
import xyz.mdhv.asom.ui.theme.AsomTokens

/** Models tab (brief §10): download, progress, pin, evict, storage stats. */
@Composable
fun ModelsScreen() {
    val manager = ServiceLocator.modelDownloadManager
    val states by remember { manager.observeAll() }.collectAsState(initial = emptyList())
    val byId = states.associateBy { it.modelId }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val totalBytes = remember(states) { manager.totalBytesOnDisk() }
        Text("Total storage: ${formatBytes(totalBytes)}", color = AsomTokens.OnSurfaceDim)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val downloadableModels = ServiceLocator.catalogue.models.filter { it.files.isNotEmpty() }
            items(downloadableModels, key = { it.id }) { model ->
                ModelCard(model, byId[model.id], manager)
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelEntry, state: ModelDownloadEntity?, manager: xyz.mdhv.asom.storage.ModelDownloadManager) {
    val status = state?.status?.let { runCatching { DownloadStatus.valueOf(it) }.getOrNull() }
        ?: DownloadStatus.NOT_DOWNLOADED
    val pinned = state?.pinned == true

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(model.id, style = MaterialTheme.typography.titleMedium)
                // §1.6: shape + label + hue, never color alone.
                val (glyph, label, color) = when (status) {
                    DownloadStatus.DOWNLOADED -> Triple(AsomTokens.StateGlyph.ACTIVE, "downloaded", AsomTokens.Cyan)
                    DownloadStatus.DOWNLOADING -> Triple("◐", "downloading", AsomTokens.Violet)
                    DownloadStatus.VERIFYING -> Triple("◑", "verifying", AsomTokens.Violet)
                    DownloadStatus.FAILED -> Triple("✕", "failed", AsomTokens.Violet)
                    DownloadStatus.NOT_DOWNLOADED -> Triple(AsomTokens.StateGlyph.INACTIVE, "not downloaded", AsomTokens.OnSurfaceDim)
                }
                Text("$glyph $label", color = color)
            }
            val file = model.files.first()
            Text("${model.family} · ${file.quant ?: "?"} · ${formatBytes(file.bytes)}", style = MaterialTheme.typography.bodySmall)

            if (status == DownloadStatus.DOWNLOADING && state != null && state.bytesTotal > 0) {
                Text(
                    "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.bytesTotal)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AsomTokens.OnSurfaceDim,
                )
            }
            if (status == DownloadStatus.FAILED && state?.error != null) {
                Text("error: ${state.error}", color = AsomTokens.Violet, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    DownloadStatus.NOT_DOWNLOADED, DownloadStatus.FAILED -> Button(onClick = { manager.download(model.id) }) {
                        Text("Download")
                    }
                    DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING -> OutlinedButton(onClick = { manager.cancel(model.id) }) {
                        Text("Cancel")
                    }
                    DownloadStatus.DOWNLOADED -> {
                        OutlinedButton(onClick = { manager.pin(model.id, !pinned) }) {
                            Text(if (pinned) "Unpin" else "Pin")
                        }
                        OutlinedButton(onClick = { manager.evict(model.id) }, enabled = !pinned) {
                            Text("Evict")
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return "%.1f %s".format(bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
