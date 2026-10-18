package xyz.mdhv.asom.sample

import android.app.Application
import xyz.mdhv.asom.client.AppInventory
import xyz.mdhv.asom.client.InventoryRegistry

/**
 * §10A.4: a sibling may query our `InventoryProvider` while this app is not
 * running, which starts the process and creates the provider without ever
 * reaching an Activity — so the inventory has to be published here. This proof
 * app holds no models, so only the label is non-trivial.
 */
class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        InventoryRegistry.set(
            AppInventory(appLabel = applicationInfo.loadLabel(packageManager).toString()),
        )
    }
}
