package de.rico.akkumonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Ui {
    val BG = 0xFF0F1216.toInt()
    val CARD = 0xFF1B2027.toInt()
    val TEXT = 0xFFE6EDF3.toInt()
    val SUB = 0xFF8B96A3.toInt()
    val GREEN = 0xFF3FB950.toInt()
    val ORANGE = 0xFFF0883E.toInt()
    val BLUE = 0xFF58A6FF.toInt()
    val RED = 0xFFF85149.toInt()
    val PURPLE = 0xFFBC8CFF.toInt()
}

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val de = Locale.GERMANY
    private val REQ_EXPORT = 42

    private lateinit var tvLevel: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvPower: TextView
    private lateinit var tvDetails: TextView
    private lateinit var tvCap: TextView
    private lateinit var tvSeg: TextView
    private lateinit var tvEst: TextView
    private lateinit var tvRaw: TextView
    private lateinit var btnService: TextView
    private lateinit var chart: ChartView
    private lateinit var tvRemain: TextView
    private lateinit var tvStandby: TextView
    private lateinit var sessionsBox: LinearLayout
    private lateinit var capChart: CapacityChartView
    private lateinit var tvCycles: TextView
    private var sessKey = ""
    private lateinit var appsBox: LinearLayout
    private var appDays = 1
    private var capKey = ""
    private val recent = ArrayDeque<Sample>()

    private var rangeH = 6
    private var lastChartLoad = 0L

    private val refresh = object : Runnable {
        override fun run() {
            updateLive()
            if (System.currentTimeMillis() - lastChartLoad > 30_000) { loadChart(); refreshApps() }
            ui.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(v: Double, d: Int) = String.format(de, "%,.${d}f", v)

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Prefs.get(this).getBoolean("enabled", true)) MonitorService.start(this)
    }

    override fun onResume() {
        super.onResume()
        lastChartLoad = 0
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    // ---------------------------------------------------------------- Oberfläche

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = dp(14).toFloat() }
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        parent.addView(c, lp)
        c.addView(text(title.uppercase(de), 12f, Ui.SUB, bold = true))
        return c
    }

    private fun text(s: String, size: Float, color: Int = Ui.TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

    private fun button(label: String, color: Int = Ui.BLUE, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(color); gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(0x14FFFFFF); cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { onClick() }
    }

    /** Segment-Schalter (z. B. 1 h | 6 h | 24 h) */
    private fun segmented(labels: List<String>, initial: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            (v.background as GradientDrawable).setColor(if (i == sel) Ui.BLUE else 0x14FFFFFF)
            v.setTextColor(if (i == sel) Ui.BG else Ui.TEXT)
        }
        labels.forEachIndexed { i, l ->
            val v = TextView(this).apply {
                text = l; textSize = 13f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
                setOnClickListener { paint(i); onSelect(i) }
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }
        paint(initial)
        return row
    }

    private fun space(parent: LinearLayout, h: Int) = parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(28))
        }

        // Kopf: große Live-Werte
        val head = card(root, "Live")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        tvLevel = text("– %", 44f, Ui.TEXT, true)
        tvCurrent = text("– mA", 30f, Ui.GREEN, true).apply { gravity = Gravity.END }
        row.addView(tvLevel, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tvCurrent, LinearLayout.LayoutParams(0, -2, 1.3f))
        head.addView(row)
        tvPower = text("", 18f, Ui.TEXT, true).apply { gravity = Gravity.END }
        head.addView(tvPower, LinearLayout.LayoutParams(-1, -2))
        tvDetails = text("", 14f, Ui.SUB)
        head.addView(tvDetails)
        tvRemain = text("", 15f, Ui.TEXT, true).apply { setPadding(0, dp(6), 0, 0) }
        head.addView(tvRemain)

        // Diagramm
        val ch = card(root, "Verlauf")
        space(ch, 8)
        ch.addView(segmented(listOf("1 h", "6 h", "24 h", "3 Tage"), 1) { i ->
            rangeH = listOf(1, 6, 24, 72)[i]; loadChart()
        })
        space(ch, 8)
        chart = ChartView(this)
        ch.addView(chart)
        ch.addView(text("━ Strom in mA (links, + = Laden)     ━ Ladestand in % (rechts)", 11f, Ui.SUB).apply {
            val sp = android.text.SpannableString(text)
            sp.setSpan(android.text.style.ForegroundColorSpan(Ui.ORANGE), 0, 1, 0)
            val idx = text.indexOf("━", 2)
            sp.setSpan(android.text.style.ForegroundColorSpan(Ui.BLUE), idx, idx + 1, 0)
            text = sp
        })

        // Kapazität
        tvCap = text("", 15f)
        card(root, "Akkukapazität").addView(tvCap)

        tvSeg = text("", 14f)
        card(root, "Laufender Abschnitt").addView(tvSeg)

        tvStandby = text("", 14f)
        card(root, "Verbrauch (Display an / aus)").addView(tvStandby)

        val sc = card(root, "Ladevorgänge")
        sc.addView(text("Antippen für Ladekurve und Namen (z. B. Ladegerät)", 12f, Ui.SUB))
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sc.addView(sessionsBox)

        val ac = card(root, "Apps (nur ohne Netzteil)")
        space(ac, 6)
        ac.addView(segmented(listOf("Heute", "7 Tage", "30 Tage"), 0) { i ->
            appDays = listOf(1, 7, 30)[i]; refreshApps()
        })
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ac.addView(appsBox)
        space(ac, 8)
        ac.addView(button("Android-Akkuverbrauch je App öffnen") {
            try { startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) } catch (e: Exception) {
                Toast.makeText(this, "Auf diesem Gerät nicht verfügbar", Toast.LENGTH_SHORT).show()
            }
        })

        val lc = card(root, "Langzeit")
        space(lc, 6)
        capChart = CapacityChartView(this)
        lc.addView(capChart)
        lc.addView(text("● Laden  ● Entladen  – – Nennkapazität", 11f, Ui.SUB).apply {
            val sp = android.text.SpannableString(text)
            sp.setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), 0, 1, 0)
            val idx = text.indexOf("●", 2)
            sp.setSpan(android.text.style.ForegroundColorSpan(Ui.ORANGE), idx, idx + 1, 0)
            text = sp
        })
        tvCycles = text("", 14f).apply { setPadding(0, dp(6), 0, 0) }
        lc.addView(tvCycles)

        tvEst = text("", 13f, Ui.SUB).apply { typeface = Typeface.MONOSPACE }
        card(root, "Letzte Messungen").addView(tvEst)

        // Einstellungen
        val p = Prefs.get(this)
        val set = card(root, "Einstellungen")

        set.addView(text("Messintervall", 13f, Ui.SUB).apply { setPadding(0, dp(8), 0, dp(4)) })
        val intervals = listOf(5, 10, 30, 60)
        set.addView(segmented(intervals.map { "$it s" }, intervals.indexOf(p.getInt("intervalS", 10)).coerceAtLeast(0)) {
            p.edit().putInt("intervalS", intervals[it]).apply()
        })

        set.addView(text("Einheit Rohwert", 13f, Ui.SUB).apply { setPadding(0, dp(10), 0, dp(4)) })
        set.addView(segmented(listOf("Auto", "µA", "mA"), p.getInt("unitMode", 0)) {
            p.edit().putInt("unitMode", it).apply()
        })

        set.addView(text("Vorzeichen", 13f, Ui.SUB).apply { setPadding(0, dp(10), 0, dp(4)) })
        set.addView(segmented(listOf("Auto", "Normal", "Invertiert"), p.getInt("signMode", 0)) {
            p.edit().putInt("signMode", it).apply()
        })

        set.addView(text("Ladegrenze-Alarm (Ton + Vibration)", 13f, Ui.SUB).apply { setPadding(0, dp(10), 0, dp(4)) })
        val levels = listOf(0, 80, 85, 90, 100)
        set.addView(segmented(listOf("Aus", "80 %", "85 %", "90 %", "100 %"),
            levels.indexOf(p.getInt("alarmLevel", 0)).coerceAtLeast(0)) {
            p.edit().putInt("alarmLevel", levels[it]).putBoolean("al_level_done", false).apply()
        })

        set.addView(text("Temperaturwarnung ab", 13f, Ui.SUB).apply { setPadding(0, dp(10), 0, dp(4)) })
        val temps = listOf(0, 38, 40, 42, 45)
        set.addView(segmented(listOf("Aus", "38 °C", "40 °C", "42 °C", "45 °C"),
            temps.indexOf(p.getInt("tempLimit", 40)).coerceAtLeast(0)) {
            p.edit().putInt("tempLimit", temps[it]).putBoolean("al_temp_armed", true).apply()
        })

        set.addView(text("Nennkapazität in mAh (leer = vom System)", 13f, Ui.SUB).apply { setPadding(0, dp(10), 0, 0) })
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
            val sys = DeviceInfo.systemDesignMah(this@MainActivity)
            hint = if (sys != null) "System: ${fmt(sys, 0)}" else "z. B. 4500"
            val v = p.getInt("designMah", 0)
            if (v > 0) setText(v.toString())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    p.edit().putInt("designMah", s?.toString()?.toIntOrNull() ?: 0).apply()
                }
            })
        }
        set.addView(et)

        fun check(label: String, key: String, def: Boolean) = CheckBox(this).apply {
            text = label; setTextColor(Ui.TEXT); textSize = 14f
            isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply() }
        }
        set.addView(check("Beim Laden wach halten (lückenlose Ladekurve, empfohlen)", "wlCharge", true))
        set.addView(check("Immer wach halten (genauer beim Entladen, kostet etwas Akku)", "wlAlways", false))

        space(set, 10)
        btnService = button("") { toggleService() }
        set.addView(btnService)
        space(set, 8)
        set.addView(button("Akku-Optimierung für diese App ausschalten") { askBatteryOpt() })
        space(set, 8)
        set.addView(button("Daten als CSV exportieren") { startExport() })
        space(set, 8)
        set.addView(button("Alle Messdaten löschen", Ui.RED) { confirmDelete() })

        tvRaw = text("", 11f, Ui.SUB).apply { setPadding(0, dp(12), 0, 0) }
        set.addView(tvRaw)

        return ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        }
    }

    // ---------------------------------------------------------------- Anzeige

    private fun updateLive() {
        val s = BatteryReader.read(this) ?: return
        tvLevel.text = if (s.level >= 0) "${s.level} %" else "– %"
        if (s.currentMa.isNaN()) {
            tvCurrent.text = "– mA"
            tvPower.text = "Strommessung vom Gerät nicht unterstützt"
        } else {
            tvCurrent.text = String.format(de, "%+,.0f mA", s.currentMa)
            tvCurrent.setTextColor(if (s.currentMa >= 0) Ui.GREEN else Ui.ORANGE)
            tvPower.text = String.format(de, "%+.2f W", s.powerW)
            tvPower.setTextColor(if (s.currentMa >= 0) Ui.GREEN else Ui.ORANGE)
        }
        tvDetails.text = String.format(de, "%s\n%.3f V  ·  %.1f °C", BatteryReader.statusText(s), s.voltageV, s.tempC)
        recent.addLast(s)
        while (recent.isNotEmpty() && s.time - recent.first().time > 15 * 60_000L) recent.removeFirst()

        // Kapazität
        val all = DataLog.loadEstimates(this)
        val last = all.takeLast(10)
        val est = DataLog.weightedCapacity(last)
        val design = DeviceInfo.designMah(this)
        val sb = StringBuilder()
        if (est != null) {
            sb.append("Ist-Kapazität:  ${fmt(est, 0)} mAh\n")
            sb.append("(Mittel aus ${last.size} Messung${if (last.size == 1) "" else "en"})\n")
        } else {
            sb.append("Ist-Kapazität:  noch keine Messung\n")
            sb.append("Braucht einen Lade- oder Entladevorgang über mindestens 10 %.\n")
        }
        if (design != null) {
            sb.append("Nennkapazität:  ${fmt(design.first, 0)} mAh (${design.second})\n")
            if (est != null) sb.append("Zustand (SoH):  ${fmt(est / design.first * 100, 0)} %\n")
        } else {
            sb.append("Nennkapazität:  unbekannt – unten eintragen\n")
        }
        if (!s.chargeCounterMah.isNaN() && s.level > 5) {
            sb.append("Fuel-Gauge:  ${fmt(s.chargeCounterMah.toDouble(), 0)} mAh drin → ${fmt(s.chargeCounterMah / s.level * 100.0, 0)} mAh voll")
        }
        tvCap.text = sb.toString().trimEnd()

        // Laufender Abschnitt
        val st = Tracker.load(Prefs.get(this))
        if (st.type == 0) {
            tvSeg.text = "Noch keine Daten – Messung läuft im Hintergrund."
        } else {
            val tf = SimpleDateFormat("dd.MM. HH:mm", de)
            val dur = (System.currentTimeMillis() - st.startTime) / 60_000
            val seg = StringBuilder()
            seg.append(if (st.type == 1) "Laden" else "Entladen")
            seg.append(" seit ${tf.format(Date(st.startTime))} (${dur / 60} h ${dur % 60} min)\n")
            seg.append("Ladung:  ${String.format(de, "%+,.0f", st.q)} mAh   ·   Energie:  ${String.format(de, "%+.2f", st.e)} Wh\n")
            seg.append("Ladestand:  ${st.startLevel} % → ${st.lastLevel} %")
            val e = st.estimate()
            if (e != null && abs(e.second) >= 3) {
                seg.append("\nHochrechnung:  ${fmt(e.first, 0)} mAh  (über ${abs(e.second)} %")
                seg.append(if (abs(e.second) < 10) ", wird ab 10 % gespeichert)" else ")")
            }
            if (st.inexact) seg.append("\n⚠ Messlücken überbrückt – Wert ungenauer")
            tvSeg.text = seg.toString()
        }

        // Liste
        if (all.isEmpty()) {
            tvEst.text = "–"
        } else {
            val tf = SimpleDateFormat("dd.MM. HH:mm", de)
            tvEst.text = all.takeLast(12).reversed().joinToString("\n") {
                String.format(
                    de, "%s  %s %+4d %%  %,6.0f mAh%s",
                    tf.format(Date(it.time)), if (it.charging) "▲" else "▼", it.deltaLevel, it.capacityMah,
                    if (it.inexact) " ~" else ""
                )
            } + "\n\n▲ Laden  ▼ Entladen  ~ ungenau"
        }

        val cap = bestCapacity(s, est, design?.first)
        tvRemain.text = remainingText(s, cap)
        tvStandby.text = standbyText(st, cap)
        refreshSessions()
        val ck = "${all.size}|${design?.first}"
        if (ck != capKey) { capKey = ck; capChart.setData(all, design?.first) }
        val total = Prefs.get(this).getFloat("totalChargeMah", 0f).toDouble()
        tvCycles.text = if (cap != null && total > 0)
            "Geladen seit Installation: ${fmt(total, 0)} mAh ≈ ${fmt(total / cap, 1)} Vollzyklen"
        else "Ladezyklen: noch keine Daten"

        // Diagnose
        val raw = Calibration.lastRaw
        tvRaw.text = "Rohwert CURRENT_NOW: ${if (raw == Int.MIN_VALUE) "n/v" else raw.toString()}  ·  " +
            "Einheit: ${if (Calibration.usingMicro) "µA" else "mA"}  ·  " +
            "Vorzeichen: ${if (Calibration.inverted) "invertiert" else "normal"}\n" +
            "Messung ${if (MonitorService.running) "läuft" else "gestoppt"}"
        btnService.text = if (MonitorService.running) "Messung stoppen" else "Messung starten"
    }

    private fun loadChart() {
        lastChartLoad = System.currentTimeMillis()
        val to = System.currentTimeMillis()
        val from = to - rangeH * 3_600_000L
        Thread {
            val list = DataLog.load(this, from)
            runOnUiThread {
                chart.setData(list, from, to)
                if (recent.size < 5) {
                    recent.clear()
                    list.filter { to - it.time <= 15 * 60_000L }.forEach { recent.addLast(it) }
                }
            }
        }.start()
    }

    private fun dur(h: Double): String {
        if (h.isNaN() || h.isInfinite() || h < 0) return "–"
        val m = (h * 60).toLong()
        return if (m >= 60 * 48) "${m / 1440} Tage ${(m % 1440) / 60} h" else "${m / 60} h ${m % 60} min"
    }

    /** Beste verfügbare Kapazität: Messung > Fuel-Gauge > Nennwert */
    private fun bestCapacity(s: Sample, est: Double?, design: Double?): Double? {
        if (est != null) return est
        if (!s.chargeCounterMah.isNaN() && s.level > 10) return s.chargeCounterMah / s.level * 100.0
        return design
    }

    /** Mittlerer Strom der letzten 10 min im gleichen Zustand (Laden/Entladen) */
    private fun recentAvg(s: Sample): Double? {
        val l = recent.filter { s.time - it.time <= 10 * 60_000L && it.isPlugged == s.isPlugged && !it.currentMa.isNaN() }
        if (l.size < 3) return null
        return l.map { it.currentMa.toDouble() }.average()
    }

    private fun remainingText(s: Sample, cap: Double?): String {
        if (cap == null || s.level < 0 || s.currentMa.isNaN()) return ""
        val avg = recentAvg(s) ?: s.currentMa.toDouble()
        return if (s.isPlugged) {
            if (s.level >= 100 || avg <= 20) "Voll bzw. lädt nicht"
            else "Voll in ca. ${dur((100 - s.level) / 100.0 * cap / avg)}  (die letzten % dauern länger)"
        } else {
            if (avg >= -5) "" else {
                val st = Tracker.load(Prefs.get(this))
                val segH = (System.currentTimeMillis() - st.startTime) / 3_600_000.0
                val segAvg = if (st.type == -1 && segH > 0.5) st.q / segH else null
                var t = "Restlaufzeit: ${dur(s.level / 100.0 * cap / -avg)} (aktueller Verbrauch)"
                if (segAvg != null && segAvg < -5) t += "\n…oder ${dur(s.level / 100.0 * cap / -segAvg)} (Ø seit Abstecken)"
                t
            }
        }
    }

    private fun usageLine(label: String, mah: Double, ms: Long, cap: Double?): String {
        val h = ms / 3_600_000.0
        if (h < 0.05) return "$label: –"
        val ma = -mah / h
        val pct = if (cap != null) String.format(de, " · %.1f %%/h", ma / cap * 100) else ""
        return String.format(de, "%s: Ø %,.0f mA%s  (%s)", label, ma, pct, dur(h))
    }

    private fun standbyText(st: TrackState, cap: Double?): String {
        val sb = StringBuilder()
        if (st.type == -1) {
            sb.append("Seit Abstecken\n")
            sb.append(usageLine("  Display an", st.onMah, st.onMs, cap)).append("\n")
            sb.append(usageLine("  Display aus", st.offMah, st.offMs, cap)).append("\n")
        }
        val p = Prefs.get(this)
        val end = p.getLong("ld_end", 0)
        if (end > 0) {
            val tf = SimpleDateFormat("dd.MM. HH:mm", de)
            sb.append("Letzte Entladung (bis ${tf.format(Date(end))})\n")
            sb.append(usageLine("  Display an", p.getFloat("ld_onQ", 0f).toDouble(), p.getLong("ld_onMs", 0), cap)).append("\n")
            sb.append(usageLine("  Display aus", p.getFloat("ld_offQ", 0f).toDouble(), p.getLong("ld_offMs", 0), cap)).append("\n")
        }
        if (sb.isEmpty()) return "Noch keine Daten – wird beim nächsten Entladen erfasst."
        sb.append("Hoher Wert bei „Display aus“ (> ca. 1 %/h) deutet auf eine App, die im Hintergrund Strom zieht.")
        return sb.toString()
    }

    private fun plugName(pl: Int) = when (pl) {
        android.os.BatteryManager.BATTERY_PLUGGED_AC -> "Netzteil"
        android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "kabellos"
        else -> "?"
    }

    private fun refreshSessions() {
        val list = DataLog.loadSessions(this).takeLast(10).reversed()
        val key = list.joinToString { it.start.toString() + DataLog.sessionName(this, it.start) }
        if (key == sessKey) return
        sessKey = key
        sessionsBox.removeAllViews()
        if (list.isEmpty()) {
            sessionsBox.addView(text("Noch kein abgeschlossener Ladevorgang.", 14f, Ui.SUB).apply { setPadding(0, dp(6), 0, 0) })
            return
        }
        val tf = SimpleDateFormat("dd.MM. HH:mm", de)
        list.forEach { c ->
            val name = DataLog.sessionName(this, c.start)
            val row = text(
                String.format(
                    de, "%s  %d→%d %%%s\nmax %.2f A · %.1f W · Ø %.1f W · %s",
                    tf.format(Date(c.start)), c.startLevel, c.endLevel,
                    if (name.isNotEmpty()) "  · $name" else "",
                    c.maxMa / 1000f, c.maxW, c.avgW, plugName(c.plugged)
                ), 14f
            ).apply {
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showSession(c) }
            }
            sessionsBox.addView(row)
        }
        // Vergleich der Ladegeräte nach Namen
        val named = DataLog.loadSessions(this).groupBy { DataLog.sessionName(this, it.start) }.filterKeys { it.isNotEmpty() }
        if (named.isNotEmpty()) {
            val cmp = named.map { (n, l) -> n to l.maxOf { it.maxW } }.sortedByDescending { it.second }
                .joinToString("\n") { String.format(de, "  %-14s max %.1f W", it.first, it.second) }
            sessionsBox.addView(text("Vergleich (bester Wert je Name):\n$cmp", 13f, Ui.SUB).apply {
                typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun refreshApps() {
        if (!::appsBox.isInitialized) return
        appsBox.removeAllViews()
        if (!AppTracker.hasPermission(this)) {
            appsBox.addView(text(
                "Damit die App sieht, welche App gerade vorne ist, braucht sie den „Nutzungsdatenzugriff“. " +
                    "Daten verlassen das Handy nicht.", 14f, Ui.SUB
            ).apply { setPadding(0, dp(8), 0, dp(8)) })
            appsBox.addView(button("Nutzungsdatenzugriff erlauben") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
            return
        }
        Thread {
            val m = AppTracker.load(this, appDays)
            runOnUiThread { showApps(m) }
        }.start()
    }

    private fun showApps(m: Map<String, AppTracker.Usage>) {
        appsBox.removeAllViews()
        val on = m.filterKeys { !it.startsWith("#") || it == AppTracker.UNKNOWN }
            .filter { it.value.onMs > 30_000 }
            .toList().sortedByDescending { it.second.mah }
        val totOn = on.sumOf { it.second.mah }
        val sb = StringBuilder()
        if (on.isEmpty()) {
            sb.append("Display an: noch keine Daten – wird ohne Netzteil erfasst.")
        } else {
            sb.append(String.format(de, "Display an – gesamt %,.0f mAh\n", totOn))
            on.take(12).forEach { (pkg, u) ->
                val h = u.onMs / 3_600_000.0
                val name = if (pkg == AppTracker.UNKNOWN) "(unbekannt)" else AppTracker.label(this, pkg)
                sb.append(String.format(
                    de, "%-16.16s %5.0f mAh %3.0f%% %s Ø%4.0f mA\n",
                    name, u.mah, if (totOn > 0) u.mah / totOn * 100 else 0.0, durShort(u.onMs), if (h > 0) u.mah / h else 0.0
                ))
            }
        }
        val off = m[AppTracker.OFF]
        if (off != null && off.bgMs > 60_000) {
            val h = off.bgMs / 3_600_000.0
            sb.append(String.format(de, "\nDisplay aus – gesamt %,.0f mAh in %s (Ø %.0f mA)\n", off.mah, durShort(off.bgMs), off.mah / h))
            val bg = m.filterKeys { !it.startsWith("#") }.filter { it.value.bgMs > 60_000 }
                .toList().sortedByDescending { it.second.bgMs }
            if (bg.isEmpty()) sb.append("Keine App lief bei Display aus im Vordergrunddienst.\n")
            else {
                sb.append("Aktiv im Hintergrund (Vordergrunddienst):\n")
                bg.take(10).forEach { (pkg, u) ->
                    sb.append(String.format(
                        de, "%-16.16s %s (%3.0f%% der Zeit)\n",
                        AppTracker.label(this, pkg), durShort(u.bgMs), u.bgMs * 100.0 / off.bgMs
                    ))
                }
            }
        }
        appsBox.addView(text(sb.toString().trimEnd(), 12f).apply {
            typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0)
        })
        appsBox.addView(text(
            "Ø mA = Verbrauch des ganzen Handys (inkl. Display), solange die App vorne war. " +
                "Bei Display aus kann Android den Strom keiner App zuordnen – gezeigt wird, welche Apps da aktiv liefen. " +
                "Die genaue Systemschätzung je App gibt es über den Knopf unten.", 11f, Ui.SUB
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun durShort(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) String.format(de, "%2dh%02d", m / 60, m % 60) else String.format(de, "%3d min", m)
    }

    private fun showSession(c: ChargeSession) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val cv = ChartView(this, 260).apply { showExtra = true }
        box.addView(cv)
        box.addView(text("━ Strom  ━ Ladestand  ━ Spannung  ┅ Temperatur", 11f, Ui.SUB).apply {
            val sp = android.text.SpannableString(text)
            val cols = listOf(Ui.ORANGE, Ui.BLUE, Ui.PURPLE, Ui.RED)
            var idx = 0
            cols.forEach { col ->
                val i = text.indexOfAny(charArrayOf('━', '┅'), idx)
                if (i >= 0) { sp.setSpan(android.text.style.ForegroundColorSpan(col), i, i + 1, 0); idx = i + 1 }
            }
            text = sp
        })
        val h = (c.end - c.start) / 3_600_000.0
        box.addView(text(
            String.format(
                de, "Dauer %s · %d→%d %%\nGeladen %,.0f mAh · %.2f Wh\nmax %.0f mA · max %.1f W · Ø %.1f W\nmax Temperatur %.1f °C · %s",
                dur(h), c.startLevel, c.endLevel, c.mah, c.wh, c.maxMa, c.maxW, c.avgW, c.maxTempC, plugName(c.plugged)
            ), 14f
        ).apply { setPadding(0, dp(8), 0, 0) })

        val et = EditText(this).apply {
            hint = "Name, z. B. Samsung 25 W"
            setText(DataLog.sessionName(this@MainActivity, c.start))
            setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(et)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("Ladevorgang " + SimpleDateFormat("dd.MM. HH:mm", de).format(Date(c.start)))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Speichern") { _, _ ->
                DataLog.setSessionName(this, c.start, et.text.toString())
                refreshSessions()
            }
            .setNegativeButton("Schließen", null)
            .show()

        Thread {
            val list = DataLog.load(this, c.start).filter { it.time <= c.end }
            runOnUiThread { cv.setData(list, c.start, c.end) }
        }.start()
    }

    // ---------------------------------------------------------------- Aktionen

    private fun toggleService() {
        val p = Prefs.get(this)
        if (MonitorService.running) {
            p.edit().putBoolean("enabled", false).apply()
            MonitorService.stop(this)
        } else {
            p.edit().putBoolean("enabled", true).apply()
            MonitorService.start(this)
        }
        ui.postDelayed({ updateLive() }, 400)
    }

    private fun askBatteryOpt() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Ist bereits ausgeschaltet ✓", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun startExport() {
        val name = "akku_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, name)
            }, REQ_EXPORT
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { DataLog.exportCsv(this, it) }
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Export gespeichert" else "Export fehlgeschlagen", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Alle Messdaten löschen?")
            .setMessage("Verlauf und alle Kapazitätsschätzungen werden gelöscht.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Löschen") { _, _ ->
                DataLog.deleteAll(this)
                Prefs.get(this).edit().putInt("t_type", 0).apply()
                loadChart(); updateLive()
            }
            .show()
    }
}
