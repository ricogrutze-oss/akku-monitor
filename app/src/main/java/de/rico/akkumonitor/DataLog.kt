package de.rico.akkumonitor

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Ein abgeschlossener Lade-/Entladeabschnitt mit daraus berechneter Kapazität. */
data class Estimate(
    val time: Long,
    val charging: Boolean,
    val deltaLevel: Int,      // z. B. +62 oder -35
    val deltaMah: Double,     // gemessene Ladungsmenge im selben Bereich
    val capacityMah: Double,  // hochgerechnet auf 100 %
    val inexact: Boolean      // Messlücken ohne Ladungszähler überbrückt
)

/** Ein abgeschlossener Ladevorgang (Netzteil an bis ab). */
data class ChargeSession(
    val start: Long,
    val end: Long,
    val startLevel: Int,
    val endLevel: Int,
    val mah: Double,
    val wh: Double,
    val maxMa: Float,
    val maxW: Float,
    val maxTempC: Float,
    val plugged: Int
) {
    val avgW: Double get() = if (end > start) wh / ((end - start) / 3_600_000.0) else 0.0
}

object DataLog {
    private fun dir(ctx: Context) = File(ctx.filesDir, "log").apply { mkdirs() }
    private fun dayName(t: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))
    private fun fileFor(ctx: Context, t: Long) = File(dir(ctx), "s_${dayName(t)}.csv")
    private fun estFile(ctx: Context) = File(ctx.filesDir, "estimates.csv")
    private fun sesFile(ctx: Context) = File(ctx.filesDir, "sessions.csv")

    private fun f(v: Float, d: Int) = if (v.isNaN()) "NaN" else String.format(Locale.US, "%.${d}f", v)

    @Synchronized
    fun append(ctx: Context, s: Sample) {
        fileFor(ctx, s.time).appendText(
            "${s.time};${f(s.currentMa, 1)};${f(s.voltageV, 3)};${s.level};${f(s.tempC, 1)};" +
                "${s.status};${s.plugged};${f(s.chargeCounterMah, 1)}\n"
        )
    }

    private fun parse(line: String): Sample? {
        val p = line.split(';')
        if (p.size < 8) return null
        return try {
            Sample(
                p[0].toLong(), p[1].toFloatOrNull() ?: Float.NaN, p[2].toFloat(), p[3].toInt(),
                p[4].toFloat(), p[5].toInt(), p[6].toInt(), p[7].toFloatOrNull() ?: Float.NaN
            )
        } catch (e: Exception) {
            null
        }
    }

    fun load(ctx: Context, since: Long): List<Sample> {
        val out = ArrayList<Sample>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = since
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val now = System.currentTimeMillis()
        while (cal.timeInMillis <= now) {
            val file = fileFor(ctx, cal.timeInMillis)
            if (file.exists()) file.forEachLine { line ->
                parse(line)?.let { if (it.time >= since) out.add(it) }
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    fun cleanup(ctx: Context, keepDays: Int = 60) {
        val limit = dayName(System.currentTimeMillis() - keepDays * 86_400_000L)
        dir(ctx).listFiles()?.forEach {
            val d = it.name.removePrefix("s_").removePrefix("a_").removeSuffix(".csv")
            if (d.length == 8 && d < limit) it.delete()
        }
    }

    fun deleteAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        estFile(ctx).delete()
        sesFile(ctx).delete()
    }

    /** Export als CSV für Excel (Semikolon, Dezimalkomma). */
    fun exportCsv(ctx: Context, out: OutputStream) {
        val tf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("﻿Zeit;Strom_mA;Spannung_V;Leistung_W;Ladestand_%;Temperatur_C;Status;Quelle;Ladungszaehler_mAh\n")
        dir(ctx).listFiles()?.filter { it.name.startsWith("s_") }?.sortedBy { it.name }?.forEach { file ->
            file.forEachLine { line ->
                val s = parse(line) ?: return@forEachLine
                fun c(v: Float, d: Int) = if (v.isNaN()) "" else String.format(Locale.GERMANY, "%.${d}f", v)
                w.write(
                    "${tf.format(Date(s.time))};${c(s.currentMa, 1)};${c(s.voltageV, 3)};${c(s.powerW, 2)};" +
                        "${s.level};${c(s.tempC, 1)};${BatteryReader.statusText(s)};${s.plugged};${c(s.chargeCounterMah, 0)}\n"
                )
            }
        }
        w.write("\n\nKapazitätsschätzungen\nZeit;Art;Delta_%;Delta_mAh;Kapazitaet_mAh;ungenau\n")
        loadEstimates(ctx).forEach { e ->
            w.write(
                "${tf.format(Date(e.time))};${if (e.charging) "Laden" else "Entladen"};${e.deltaLevel};" +
                    "${String.format(Locale.GERMANY, "%.0f", e.deltaMah)};${String.format(Locale.GERMANY, "%.0f", e.capacityMah)};" +
                    "${if (e.inexact) "ja" else "nein"}\n"
            )
        }
        w.write("\n\nLadevorgänge\nBeginn;Ende;Start_%;Ende_%;mAh;Wh;max_mA;max_W;Mittel_W;max_Temp_C;Quelle;Name\n")
        loadSessions(ctx).forEach { c ->
            w.write(
                "${tf.format(Date(c.start))};${tf.format(Date(c.end))};${c.startLevel};${c.endLevel};" +
                    "${String.format(Locale.GERMANY, "%.0f", c.mah)};${String.format(Locale.GERMANY, "%.2f", c.wh)};" +
                    "${String.format(Locale.GERMANY, "%.0f", c.maxMa)};${String.format(Locale.GERMANY, "%.2f", c.maxW)};" +
                    "${String.format(Locale.GERMANY, "%.2f", c.avgW)};${String.format(Locale.GERMANY, "%.1f", c.maxTempC)};" +
                    "${c.plugged};${sessionName(ctx, c.start)}\n"
            )
        }
        w.flush()
    }

    @Synchronized
    fun appendEstimate(ctx: Context, e: Estimate) {
        estFile(ctx).appendText(
            "${e.time};${if (e.charging) 1 else 0};${e.deltaLevel};" +
                "${String.format(Locale.US, "%.1f", e.deltaMah)};${String.format(Locale.US, "%.1f", e.capacityMah)};" +
                "${if (e.inexact) 1 else 0}\n"
        )
    }

    fun loadEstimates(ctx: Context): List<Estimate> {
        val f = estFile(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(';')
            try {
                Estimate(p[0].toLong(), p[1] == "1", p[2].toInt(), p[3].toDouble(), p[4].toDouble(), p[5] == "1")
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Gewichteter Mittelwert der letzten Schätzungen: großer Bereich zählt mehr, ungenaue halb. */
    fun weightedCapacity(list: List<Estimate>): Double? {
        var sw = 0.0
        var sv = 0.0
        list.forEach {
            val w = abs(it.deltaLevel) * (if (it.inexact) 0.5 else 1.0)
            sw += w; sv += w * it.capacityMah
        }
        return if (sw > 0) sv / sw else null
    }

    @Synchronized
    fun appendSession(ctx: Context, c: ChargeSession) {
        sesFile(ctx).appendText(
            "${c.start};${c.end};${c.startLevel};${c.endLevel};" +
                "${String.format(Locale.US, "%.1f", c.mah)};${String.format(Locale.US, "%.3f", c.wh)};" +
                "${f(c.maxMa, 0)};${f(c.maxW, 2)};${f(c.maxTempC, 1)};${c.plugged}\n"
        )
    }

    fun loadSessions(ctx: Context): List<ChargeSession> {
        val f = sesFile(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(';')
            try {
                ChargeSession(
                    p[0].toLong(), p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toDouble(), p[5].toDouble(),
                    p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), p[9].toInt()
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun sessionName(ctx: Context, start: Long): String = Prefs.get(ctx).getString("sname_$start", "") ?: ""
    fun setSessionName(ctx: Context, start: Long, name: String) =
        Prefs.get(ctx).edit().putString("sname_$start", name.trim()).apply()
}
