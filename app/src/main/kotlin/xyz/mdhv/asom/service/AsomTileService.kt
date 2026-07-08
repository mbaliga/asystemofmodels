package xyz.mdhv.asom.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile (brief P8): start/stop the daemon from the shade. */
class AsomTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (AsomService.running.value) {
            AsomService.stop(this)
        } else {
            AsomService.start(this)
        }
        // Give the service a moment to flip state, then refresh.
        qsTile?.let { updateTile() }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = AsomService.running.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "asom"
        tile.subtitle = if (running) "serving" else "stopped"
        tile.updateTile()
    }
}
