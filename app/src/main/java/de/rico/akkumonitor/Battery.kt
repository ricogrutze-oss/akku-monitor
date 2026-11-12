package de.rico.akkumonitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import kotlin.math.abs

/** Ein Messpunkt. Strom: + = in den Akku (Laden), - = aus dem Akku (Entladen). */
data class Sample(
    val time: Long,              // Epoch ms
    val currentMa: Float,        // NaN, wenn nicht verfügbar
    val voltageV: Float,
    val level: Int,              // 0..100, -1 unbekannt
    val tempC: Float,
    val status: Int,
    val plugged: Int,
    val chargeCounterMah: Float  // NaN, wenn nicht verfügbar
) {
    val powerW: Float get() = currentMa / 1000f * voltageV
    val isPlugged: Boolean get() = plugged != 0
}

object Prefs {
    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("akku", Context.MODE_PRIVATE)
}

/**
 * Hersteller liefern BATTERY_PROPERTY_CURRENT_NOW mal in µA, mal in mA und mit
 * unterschiedlichem Vorzeichen. Hier wird das automatisch erkannt (oder manuell vorgegeben).
 */
object Calibration {
    @Volatile var lastRaw: Int = Int.MIN_VALUE
    @Volatile var usingMicro: Boolean = true
    @Volatile var inverted: Boolean = false

    fun normalize(ctx: Context, raw: Int, plugged: Int, status: Int): Float {
        lastRaw = raw
        if (raw == Int.MIN_VALUE || raw == Int.MAX_VALUE) return Float.NaN
        val p = Prefs.get(ctx)

        // Einheit
        val unitMode = p.getInt("unitMode", 0) // 0 Auto, 1 µA, 2 mA
        var micro = when (unitMode) {
            1 -> true
            2 -> false
            else -> p.getBoolean("autoMicro", false)
        }
        if (unitMode == 0 && !micro && abs(raw) > 15000) {
            micro = true
            p.edit().putBoolean("autoMicro", true).apply()
        }
        usingMicro = micro
        val ma = if (micro) raw / 1000f else raw.toFloat()

        // Vorzeichen
        val signMode = p.getInt("signMode", 0) // 0 Auto, 1 Normal, 2 Invertiert
        val inv = when (signMode) {
            1 -> false
            2 -> true
            else -> {
                var learned = p.getInt("autoInvert", -1) // -1 = noch unbekannt
                if (plugged == 0 && abs(ma) > 30f) {
                    // Ohne Netzteil muss Strom aus dem Akku fließen -> eindeutig
                    val obs = if (ma > 0) 1 else 0
                    if (obs != learned) {
                        learned = obs
                        p.edit().putInt("autoInvert", obs).apply()
                    }
                }
                if (learned == -1) {
                    // vorläufig: beim Laden positiven Wert erwarten
                    plugged != 0 && status == BatteryManager.BATTERY_STATUS_CHARGING && ma < -30f
                } else learned == 1
            }
        }
        inverted = inv
        return if (inv) -ma else ma
    }
}

object BatteryReader {
    fun read(ctx: Context): Sample? {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val i = ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val volt = if (mv > 100) mv / 1000f else mv.toFloat()
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val current = Calibration.normalize(ctx, raw, plugged, status)

        val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) // µAh
        val ccMah = if (cc != Int.MIN_VALUE && cc in 100_000..30_000_000) cc / 1000f else Float.NaN

        return Sample(System.currentTimeMillis(), current, volt, pct, temp, status, plugged, ccMah)
    }

    fun statusText(s: Sample): String {
        val src = when (s.plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Netzteil"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "kabellos"
            else -> ""
        }
        return when (s.status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Lädt ($src)"
            BatteryManager.BATTERY_STATUS_FULL -> "Voll ($src)"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Entlädt"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> if (src.isEmpty()) "Lädt nicht" else "Lädt nicht ($src)"
            else -> "Unbekannt"
        }
    }
}

object DeviceInfo {
    /** Nennkapazität aus dem Power-Profile des Herstellers (nicht offizielle API, klappt nicht überall). */
    fun systemDesignMah(ctx: Context): Double? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val pp = cls.getConstructor(Context::class.java).newInstance(ctx)
        val cap = cls.getMethod("getBatteryCapacity").invoke(pp) as Double
        if (cap > 300) cap else null
    } catch (t: Throwable) {
        null
    }

    /** Liefert (Kapazität, Quelle). Manueller Wert hat Vorrang. */
    fun designMah(ctx: Context): Pair<Double, String>? {
        val manual = Prefs.get(ctx).getInt("designMah", 0)
        if (manual > 0) return manual.toDouble() to "manuell"
        return systemDesignMah(ctx)?.let { it to "System" }
    }
}
