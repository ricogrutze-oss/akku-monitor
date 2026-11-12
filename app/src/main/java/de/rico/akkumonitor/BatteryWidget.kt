package de.rico.akkumonitor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

/** Startbildschirm-Widget: Ladestand, Strom, Leistung. Wird vom Mess-Dienst aktualisiert. */
class BatteryWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        update(ctx, BatteryReader.read(ctx))
    }

    companion object {
        fun update(ctx: Context, s: Sample?) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BatteryWidget::class.java))
            if (ids.isEmpty() || s == null) return

            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            rv.setTextViewText(R.id.w_level, "${s.level} %")
            if (s.currentMa.isNaN()) {
                rv.setTextViewText(R.id.w_current, "– mA")
                rv.setTextViewText(R.id.w_power, "")
            } else {
                val col = if (s.currentMa >= 0) Ui.GREEN else Ui.ORANGE
                rv.setTextViewText(R.id.w_current, String.format(Locale.GERMANY, "%+,.0f mA", s.currentMa))
                rv.setTextViewText(
                    R.id.w_power,
                    String.format(Locale.GERMANY, "%+.2f W · %.1f °C", s.powerW, s.tempC)
                )
                rv.setTextColor(R.id.w_current, col)
                rv.setTextColor(R.id.w_power, col)
            }
            val pi = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.w_root, pi)
            mgr.updateAppWidget(ids, rv)
        }
    }
}
