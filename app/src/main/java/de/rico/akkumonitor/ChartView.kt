package de.rico.akkumonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Strom (mA, linke Achse) und Ladestand (%, rechte Achse) über der Zeit. */
class ChartView(ctx: Context) : View(ctx) {

    private var data: List<Sample> = emptyList()
    private var from = 0L
    private var to = 1L
    private val d = resources.displayMetrics.density

    private val pCur = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Ui.ORANGE
        strokeJoin = Paint.Join.ROUND
    }
    private val pLvl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Ui.BLUE
    }
    private val pGrid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val pZero = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1f * d }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SUB; textSize = 10f * d }

    fun setData(list: List<Sample>, fromMs: Long, toMs: Long) {
        data = list; from = fromMs; to = max(toMs, fromMs + 1)
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (230 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val l = 46 * d
        val r = width - 34 * d
        val t = 8 * d
        val b = height - 20 * d

        var mn = 0f
        var mx = 0f
        for (s in data) if (!s.currentMa.isNaN()) { mn = min(mn, s.currentMa); mx = max(mx, s.currentMa) }
        if (mx - mn < 100f) { mx += 50f; mn -= 50f }
        val pad = (mx - mn) * 0.06f
        mx += pad; mn -= pad

        fun x(tt: Long) = l + (tt - from).toFloat() / (to - from) * (r - l)
        fun yc(v: Float) = b - (v - mn) / (mx - mn) * (b - t)
        fun yl(v: Float) = b - v / 100f * (b - t)

        // Raster + Achsbeschriftung
        pTxt.textAlign = Paint.Align.RIGHT
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

        // Kurven, auf 1 Pixel gemittelt; bei Lücken > gapMs wird abgesetzt
        val gapMs = max(5 * 60_000L, (to - from) / 150)
        val pathC = Path()
        val pathL = Path()
        var bx = Int.MIN_VALUE
        var sumC = 0.0; var nC = 0
        var sumL = 0.0; var nL = 0
        var downC = false; var downL = false
        var lastT = Long.MIN_VALUE

        fun flush() {
            if (bx == Int.MIN_VALUE) return
            val xf = bx.toFloat()
            if (nC > 0) {
                val y = yc((sumC / nC).toFloat())
                if (downC) pathC.lineTo(xf, y) else { pathC.moveTo(xf, y); downC = true }
            }
            if (nL > 0) {
                val y = yl((sumL / nL).toFloat())
                if (downL) pathL.lineTo(xf, y) else { pathL.moveTo(xf, y); downL = true }
            }
            sumC = 0.0; nC = 0; sumL = 0.0; nL = 0
        }

        for (s in data) {
            if (lastT != Long.MIN_VALUE && s.time - lastT > gapMs) {
                flush(); bx = Int.MIN_VALUE; downC = false; downL = false
            }
            lastT = s.time
            val px = x(s.time).toInt()
            if (px != bx) { flush(); bx = px }
            if (!s.currentMa.isNaN()) { sumC += s.currentMa; nC++ }
            if (s.level >= 0) { sumL += s.level; nL++ }
        }
        flush()

        c.save()
        c.clipRect(l, t, r, b)
        c.drawPath(pathL, pLvl)
        c.drawPath(pathC, pCur)
        c.restore()
    }
}
