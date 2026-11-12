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

    private var rangeH = 6
    private var lastChartLoad = 0L

    private val refresh = object : Runnable {
        override fun run() {
            updateLive()
            if (System.currentTimeMillis() - lastChartLoad > 30_000) loadChart()
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
            runOnUiThread { chart.setData(list, from, to) }
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
