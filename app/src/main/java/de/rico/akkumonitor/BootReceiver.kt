package de.rico.akkumonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Startet die Messung nach Neustart oder App-Update automatisch wieder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs.get(ctx).getBoolean("enabled", true)) {
            try {
                MonitorService.start(ctx)
            } catch (e: Exception) {
            }
        }
    }
}
