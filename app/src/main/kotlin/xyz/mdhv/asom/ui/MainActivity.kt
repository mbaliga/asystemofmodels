package xyz.mdhv.asom.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import xyz.mdhv.asom.ui.theme.AsomTheme
import xyz.mdhv.asom.widget.WidgetLinks
import xyz.mdhv.asom.widget.model.Destination

/**
 * Placeholder-functional dashboard (invariant §1.7): functional Compose/M3
 * against the token seam, zero visual ambition. P5 tabs: Status/Keys/Ledger.
 * Hotspot (P6/P8) and Models (P7) arrive with their phases.
 */
class MainActivity : ComponentActivity() {

    /** The open tab. A home-screen widget tap names one via [WidgetLinks.EXTRA_DESTINATION]. */
    private val selectedTab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab.intValue = savedInstanceState?.getInt(KEY_TAB) ?: widgetTab(intent) ?: 0
        setContent {
            AsomTheme {
                NotificationPermissionGate()
                Dashboard(selectedTab.intValue) { selectedTab.intValue = it }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetTab(intent)?.let { selectedTab.intValue = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, selectedTab.intValue)
    }

    /** Only a known destination selects a tab; anything else is ignored. */
    private fun widgetTab(intent: Intent?): Int? =
        Destination.fromWire(intent?.getStringExtra(WidgetLinks.EXTRA_DESTINATION))?.tabIndex

    private companion object {
        const val KEY_TAB = "selected_tab"
    }
}

/**
 * The FGS notification is the daemon's only ambient live-state surface (§11
 * P8). On SDK 33+ it is suppressed until the user grants POST_NOTIFICATIONS,
 * so the dashboard asks once on open.
 */
@Composable
private fun NotificationPermissionGate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun Dashboard(selected: Int, onSelect: (Int) -> Unit) {
    // Order is load-bearing: widget Destination.tabIndex values point into it.
    val tabs = listOf("Status", "Hotspot", "Models", "Keys", "Ledger")

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selected == index,
                        onClick = { onSelect(index) },
                        text = { Text(title) },
                    )
                }
            }
            when (selected) {
                0 -> StatusScreen()
                1 -> HotspotScreen()
                2 -> ModelsScreen()
                3 -> KeysScreen()
                4 -> LedgerScreen()
            }
        }
    }
}
