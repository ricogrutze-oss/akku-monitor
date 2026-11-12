package de.rico.akkumonitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Ordnet den gemessenen Akkustrom der App zu, die gerade im Vordergrund ist (nur ohne Netzteil).
 * Bei ausgeschaltetem Display kann Android keiner App Strom zuordnen. Erfasst wird dann,
 * welche Apps einen Vordergrunddienst laufen hatten (z. B. Navigation, Musik, Sync) und wie lange.
 *
 * Braucht die Berechtigung "Nutzungsdatenzugriff".
 */
object AppTracker {
    const val OFF = "#aus"
    const val UNKNOWN = "#unbekannt"

    class Usage(var onMs: Long = 0, var mah: Double = 0.0, var bgMs: Long = 0)

    private val map = HashMap<String, Usage>()
    private var day = ""
    private var lastEl = 0L
    private var lastScreen = true
    private var lastEvt = 0L
    private var fg: String? = null
    private val fgs = HashSet<String>()
    private var lastSave = 0L

    private fun dayName(t: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))
    private fun file(ctx: Context, d: String) = File(File(ctx.filesDir, "log").apply { mkdirs() }, "a_$d.csv")

    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun u(k: String) = map.getOrPut(k) { Usage() }

    @Synchronized
    fun update(ctx: Context, dq: Double, plugged: Boolean, screenOn: Boolean) {
        if (!hasPermission(ctx)) return
        val now = System.currentTimeMillis()
        val el = SystemClock.elapsedRealtime()
        val d = dayName(now)
        if (d != day) {
            if (day.isNotEmpty()) save(ctx)
            day = d
            map.clear()
            map.putAll(read(ctx, d))
        }
        if (lastEl > 0 && el > lastEl && !plugged) {
            val dt = el - lastEl
            val used = if (dq.isNaN()) 0.0 else -dq   // Entladen = positiv
            if (lastScreen) {
                val k = fg ?: UNKNOWN
                u(k).onMs += dt; u(k).mah += used
            } else {
                u(OFF).bgMs += dt; u(OFF).mah += used
                for (p in fgs) u(p).bgMs += dt
            }
        }
        lastEl = el
        lastScreen = screenOn
        readEvents(ctx, now)
        if (now - lastSave > 60_000L) save(ctx)
    }

    private fun readEvents(ctx: Context, now: Long) {
        try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val from = if (lastEvt == 0L) now - 24 * 3_600_000L else lastEvt
            val ev = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                val pkg = e.packageName ?: continue
                when (e.eventType) {
                    1 -> fg = pkg                               // ACTIVITY_RESUMED
                    2 -> if (fg == pkg) fg = null               // ACTIVITY_PAUSED
                    19 -> if (pkg != ctx.packageName) fgs.add(pkg)  // FOREGROUND_SERVICE_START
                    20 -> fgs.remove(pkg)                       // FOREGROUND_SERVICE_STOP
                }
            }
            lastEvt = now
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun save(ctx: Context) {
        if (day.isEmpty()) return
        lastSave = System.currentTimeMillis()
        val sb = StringBuilder()
        for ((k, v) in map) sb.append("$k;${v.onMs};${String.format(Locale.US, "%.2f", v.mah)};${v.bgMs}\n")
        try { file(ctx, day).writeText(sb.toString()) } catch (e: Exception) {}
    }

    private fun read(ctx: Context, d: String): Map<String, Usage> {
        val out = HashMap<String, Usage>()
        val f = file(ctx, d)
        if (!f.exists()) return out
        f.forEachLine { line ->
            val p = line.split(';')
            if (p.size >= 4) try {
                out[p[0]] = Usage(p[1].toLong(), p[2].toDouble(), p[3].toLong())
            } catch (e: Exception) {
            }
        }
        return out
    }

    /** Summe der letzten n Tage (1 = heute). Heute inkl. noch nicht gespeicherter Werte. */
    @Synchronized
    fun load(ctx: Context, days: Int): Map<String, Usage> {
        val sum = HashMap<String, Usage>()
        val cal = Calendar.getInstance()
        repeat(days) {
            val d = dayName(cal.timeInMillis)
            val src = if (d == day) map else read(ctx, d)
            for ((k, v) in src) {
                val t = sum.getOrPut(k) { Usage() }
                t.onMs += v.onMs; t.mah += v.mah; t.bgMs += v.bgMs
            }
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    private val labels = HashMap<String, String>()

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }
}
