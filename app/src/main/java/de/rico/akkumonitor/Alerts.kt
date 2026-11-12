package de.rico.akkumonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Locale

/** Ladegrenze-Alarm und Temperaturwarnung. */
object Alerts {
    private const val CH = "alerts"
    private const val ID_LEVEL = 2
    private const val ID_TEMP = 3

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Akku-Alarme", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
        )
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        val pi = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun check(ctx: Context, s: Sample) {
        val p = Prefs.get(ctx)
        val ed = p.edit()

        // Ladegrenze: einmal pro Ladevorgang
        if (!s.isPlugged) {
            if (p.getBoolean("al_level_done", false)) ed.putBoolean("al_level_done", false)
        } else {
            val lim = p.getInt("alarmLevel", 0)
            if (lim > 0 && s.level >= lim && !p.getBoolean("al_level_done", false)) {
                notify(ctx, ID_LEVEL, "Ladegrenze $lim % erreicht", "Akku hat ${s.level} % – jetzt abstecken")
                ed.putBoolean("al_level_done", true)
            }
        }

        // Temperatur: mit 2 K Hysterese
        val tl = p.getInt("tempLimit", 40)
        if (tl > 0) {
            val armed = p.getBoolean("al_temp_armed", true)
            if (armed && s.tempC >= tl) {
                notify(
                    ctx, ID_TEMP, String.format(Locale.GERMANY, "Akku warm: %.1f °C", s.tempC),
                    if (s.isPlugged) "Beim Laden – Handy kühler legen oder Ladegerät abziehen"
                    else "Handy entlasten oder kühler legen"
                )
                ed.putBoolean("al_temp_armed", false)
            } else if (!armed && s.tempC < tl - 2) {
                ed.putBoolean("al_temp_armed", true)
            }
        }
        ed.apply()
    }
}
