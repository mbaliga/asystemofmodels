package xyz.mdhv.asom.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import xyz.mdhv.asom.ui.theme.AsomTheme

/**
 * Placeholder-functional dashboard (invariant §1.7): functional Compose/M3
 * against the token seam, zero visual ambition. P5 tabs: Status/Keys/Ledger.
 * Hotspot (P6/P8) and Models (P7) arrive with their phases.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AsomTheme {
                Dashboard()
            }
        }
    }
}

@Composable
private fun Dashboard() {
    val tabs = listOf("Status", "Keys", "Ledger")
    var selected by remember { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = { Text(title) },
                    )
                }
            }
            when (selected) {
                0 -> StatusScreen()
                1 -> KeysScreen()
                2 -> LedgerScreen()
            }
        }
    }
}
