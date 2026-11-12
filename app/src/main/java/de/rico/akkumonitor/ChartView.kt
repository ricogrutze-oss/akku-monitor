package de.rico.akkumonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Strom (mA, linke Achse) und Ladestand (%, rechte Achse) über der Zeit.
 * Mit showExtra zusätzlich Spannung und Temperatur (eigene Skalen, Bereich oben links angegeben).
 */
class ChartView(ctx: Context, private val heightDp: Int = 230) : View(ctx) {

    var showExtra = false

    private var data: List<Sample> = emptyList()
    private var from = 0L
    private var to = 1L
    private val d = resources.displayMetrics.density

    private fun line(col: Int, w: Float = 1.6f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = w * d; color = col; strokeJoin = Paint.Join.ROUND
    }

    private val pCur = line(Ui.ORANGE)
    private val pLvl = line(Ui.BLUE)
    private val pVolt = line(Ui.PURPLE, 1.3f)
    private val pTemp = line(Ui.RED, 1.3f).apply { pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) }
    private val pGrid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val pZero = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1f * d }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SUB; textSize = 10f * d }

    fun setData(list: List<Sample>, fromMs: Long, toMs: Long) {
        data = list; from = fromMs; to = max(toMs, fromMs + 1)
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (heightDp * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val l = 46 * d
        val r = width - 34 * d
        val t = (if (showExtra) 22 else 8) * d
        val b = height - 20 * d

        var mn = 0f; var mx = 0f
        var vMin = Float.MAX_VALUE; var vMax = -Float.MAX_VALUE
        var tMin = Float.MAX_VALUE; var tMax = -Float.MAX_VALUE
        for (s in data) {
            if (!s.currentMa.isNaN()) { mn = min(mn, s.currentMa); mx = max(mx, s.currentMa) }
            if (s.voltageV > 0) { vMin = min(vMin, s.voltageV); vMax = max(vMax, s.voltageV) }
            tMin = min(tMin, s.tempC); tMax = max(tMax, s.tempC)
        }
        if (mx - mn < 100f) { mx += 50f; mn -= 50f }
        val pad = (mx - mn) * 0.06f
        mx += pad; mn -= pad
        if (vMax - vMin < 0.05f) { vMax += 0.05f; vMin -= 0.05f }
        if (tMax - tMin < 2f) { tMax += 1f; tMin -= 1f }

        fun x(tt: Long) = l + (tt - from).toFloat() / (to - from) * (r - l)
        fun yc(v: Float) = b - (v - mn) / (mx - mn) * (b - t)
        fun yl(v: Float) = b - v / 100f * (b - t)
        fun yv(v: Float) = b - (v - vMin) / (vMax - vMin) * (b - t)
        fun yt(v: Float) = b - (v - tMin) / (tMax - tMin) * (b - t)

        for (i in 0..4) {
            val y = t + i * (b - t) / 4
            c.drawLine(l, y, r, y, pGrid)
            val v = mx - i * (mx - mn) / 4
            pTxt.color = Ui.ORANGE; pTxt.textAlign = Paint.Align.RIGHT
            c.drawText(String.format(Locale.GERMANY, "%.0f", v), l - 4 * d, y + 3.5f * d, pTxt)
            pTxt.color = Ui.BLUE; pTxt.textAlign = Paint.Align.LEFT
            c.drawText("${100 - i * 25}", r + 4 * d, y + 3.5f * d, pTxt)
        }
        if (mn < 0 && mx > 0) c.drawLine(l, yc(0f), r, yc(0f), pZero)

        pTxt.color = Ui.SUB; pTxt.textAlign = Paint.Align.CENTER
        val tf = SimpleDateFormat(if (to - from > 36 * 3_600_000L) "dd.MM. HH:mm" else "HH:mm", Locale.GERMANY)
        for (i in 0..4) {
            val tt = from + i * (to - from) / 4
            c.drawText(tf.format(Date(tt)), x(tt), height - 5 * d, pTxt)
        }

        if (data.isEmpty()) {
            c.drawText("Noch keine Messwerte im Zeitraum", (l + r) / 2, (t + b) / 2, pTxt)
            return
        }

        if (showExtra) {
            pTxt.textAlign = Paint.Align.LEFT
            pTxt.color = Ui.PURPLE
            val sv = String.format(Locale.GERMANY, "U %.2f–%.2f V", vMin, vMax)
            c.drawText(sv, l, 12 * d, pTxt)
            pTxt.color = Ui.RED
            c.drawText(String.format(Locale.GERMANY, "T %.1f–%.1f °C", tMin, tMax), l + pTxt.measureText(sv) + 14 * d, 12 * d, pTxt)
        }

        // Kurven, auf 1 Pixel gemittelt; bei Lücken > gapMs wird abgesetzt
        val gapMs = max(5 * 60_000L, (to - from) / 150)
        val paths = Array(4) { Path() }        // 0 Strom, 1 Ladestand, 2 Spannung, 3 Temperatur
        val down = BooleanArray(4)
        val sum = DoubleArray(4)
        val cnt = IntArray(4)
        var bx = Int.MIN_VALUE
        var lastT = Long.MIN_VALUE

        fun flush() {
            if (bx == Int.MIN_VALUE) return
            val xf = bx.toFloat()
            for (k in 0..3) {
                if (cnt[k] == 0) continue
                val v = (sum[k] / cnt[k]).toFloat()
                val y = when (k) { 0 -> yc(v); 1 -> yl(v); 2 -> yv(v); else -> yt(v) }
                if (down[k]) paths[k].lineTo(xf, y) else { paths[k].moveTo(xf, y); down[k] = true }
                sum[k] = 0.0; cnt[k] = 0
            }
        }

        for (s in data) {
            if (lastT != Long.MIN_VALUE && s.time - lastT > gapMs) {
                flush(); bx = Int.MIN_VALUE; down.fill(false)
            }
            lastT = s.time
            val px = x(s.time).toInt()
            if (px != bx) { flush(); bx = px }
            if (!s.currentMa.isNaN()) { sum[0] += s.currentMa; cnt[0]++ }
            if (s.level >= 0) { sum[1] += s.level; cnt[1]++ }
            if (s.voltageV > 0) { sum[2] += s.voltageV; cnt[2]++ }
            sum[3] += s.tempC; cnt[3]++
        }
        flush()

        c.save()
        c.clipRect(l, t, r, b)
        c.drawPath(paths[1], pLvl)
        if (showExtra) {
            c.drawPath(paths[2], pVolt)
            c.drawPath(paths[3], pTemp)
        }
        c.drawPath(paths[0], pCur)
        c.restore()
    }
}

/** Langzeitverlauf der gemessenen Kapazität (Punkte) mit Nennkapazität (gestrichelt). */
class CapacityChartView(ctx: Context) : View(ctx) {
    private var est: List<Estimate> = emptyList()
    private var design: Double? = null
    private val d = resources.displayMetrics.density
    private val pGrid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val pDesign = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.SUB; strokeWidth = 1.2f * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SUB; textSize = 10f * d }

    fun setData(list: List<Estimate>, designMah: Double?) {
        est = list; design = designMah; invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (170 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val l = 46 * d; val r = width - 10 * d; val t = 8 * d; val b = height - 20 * d
        if (est.isEmpty()) {
            pTxt.textAlign = Paint.Align.CENTER
            c.drawText("Noch keine Kapazitätsmessung", (l + r) / 2, (t + b) / 2, pTxt)
            return
        }
        var lo = est.minOf { it.capacityMah }
        var hi = est.maxOf { it.capacityMah }
        design?.let { lo = min(lo, it); hi = max(hi, it) }
        val span = max(hi - lo, 200.0)
        lo -= span * 0.15; hi += span * 0.15
        val t0 = est.first().time
        val t1 = max(System.currentTimeMillis(), t0 + 86_400_000L)

        fun x(tt: Long) = l + (tt - t0).toFloat() / (t1 - t0) * (r - l)
        fun y(v: Double) = (b - (v - lo) / (hi - lo) * (b - t)).toFloat()

        pTxt.textAlign = Paint.Align.RIGHT
        for (i in 0..3) {
            val v = hi - i * (hi - lo) / 3
            val yy = y(v)
            c.drawLine(l, yy, r, yy, pGrid)
            c.drawText(String.format(Locale.GERMANY, "%,.0f", v), l - 4 * d, yy + 3.5f * d, pTxt)
        }
        design?.let { c.drawLine(l, y(it), r, y(it), pDesign) }

        pTxt.textAlign = Paint.Align.CENTER
        val tf = SimpleDateFormat("dd.MM.yy", Locale.GERMANY)
        for (i in 0..2) {
            val tt = t0 + i * (t1 - t0) / 2
            c.drawText(tf.format(Date(tt)), x(tt).coerceIn(l + 20 * d, r - 20 * d), height - 5 * d, pTxt)
        }
        for (e in est) {
            pDot.color = if (e.charging) Ui.GREEN else Ui.ORANGE
            pDot.alpha = if (e.inexact) 110 else 255
            c.drawCircle(x(e.time), y(e.capacityMah), 3.5f * d, pDot)
        }
    }
}
