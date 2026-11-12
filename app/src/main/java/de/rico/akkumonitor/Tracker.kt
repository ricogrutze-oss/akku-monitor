package de.rico.akkumonitor

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.max

/**
 * Zustand des laufenden Lade- bzw. Entladeabschnitts.
 *
 * Prinzip: Strom wird über die Zeit integriert (Coulomb-Zählung, Trapezregel).
 * Gemessen wird nur zwischen zwei Prozentsprüngen der Ladestandsanzeige
 * ("Anker" = erster Sprung, "Kante" = letzter Sprung). Dadurch entfällt die
 * Rundungsunschärfe der ganzzahligen Prozentanzeige.
 * Kapazität = ΔQ / Δ% · 100.
 */
class TrackState {
    var type = 0                  // 1 Laden, -1 Entladen, 0 leer
    var startTime = 0L
    var lastElapsed = 0L
    var lastCurrent = Float.NaN
    var lastCc = Float.NaN
    var q = 0.0                   // mAh seit Abschnittsbeginn (vorzeichenbehaftet)
    var e = 0.0                   // Wh seit Abschnittsbeginn
    var startLevel = -1
    var lastLevel = -1
    var anchorLevel = -1
    var qAnchor = 0.0
    var edgeLevel = -1
    var qEdge = 0.0
    var inexact = false

    // Ladevorgang-Statistik
    var maxI = 0f
    var maxP = 0f
    var maxT = -100f
    var plugged = 0

    // Verbrauch nach Displayzustand
    var lastScreen = true
    var onMs = 0L
    var onMah = 0.0
    var offMs = 0L
    var offMah = 0.0

    /** Zwischenschätzung (Kapazität in mAh, Δ%) oder null */
    fun estimate(): Pair<Double, Int>? {
        if (anchorLevel < 0 || edgeLevel < 0) return null
        val dl = edgeLevel - anchorLevel
        val dq = qEdge - qAnchor
        if (dl == 0 || dl * dq <= 0) return null
        return dq / dl * 100.0 to dl
    }
}

object Tracker {
    private const val MIN_DELTA_LEVEL = 10

    fun load(p: SharedPreferences) = TrackState().apply {
        type = p.getInt("t_type", 0)
        startTime = p.getLong("t_start", 0)
        lastElapsed = p.getLong("t_lastEl", 0)
        lastCurrent = p.getFloat("t_lastI", Float.NaN)
        lastCc = p.getFloat("t_lastCc", Float.NaN)
        q = p.getFloat("t_q", 0f).toDouble()
        e = p.getFloat("t_e", 0f).toDouble()
        startLevel = p.getInt("t_startL", -1)
        lastLevel = p.getInt("t_lastL", -1)
        anchorLevel = p.getInt("t_anchL", -1)
        qAnchor = p.getFloat("t_qAnch", 0f).toDouble()
        edgeLevel = p.getInt("t_edgeL", -1)
        qEdge = p.getFloat("t_qEdge", 0f).toDouble()
        inexact = p.getBoolean("t_inex", false)
        maxI = p.getFloat("t_maxI", 0f)
        maxP = p.getFloat("t_maxP", 0f)
        maxT = p.getFloat("t_maxT", -100f)
        plugged = p.getInt("t_plug", 0)
        lastScreen = p.getBoolean("t_scr", true)
        onMs = p.getLong("t_onMs", 0)
        onMah = p.getFloat("t_onQ", 0f).toDouble()
        offMs = p.getLong("t_offMs", 0)
        offMah = p.getFloat("t_offQ", 0f).toDouble()
    }

    private fun save(p: SharedPreferences, s: TrackState) {
        p.edit()
            .putInt("t_type", s.type).putLong("t_start", s.startTime).putLong("t_lastEl", s.lastElapsed)
            .putFloat("t_lastI", s.lastCurrent).putFloat("t_lastCc", s.lastCc)
            .putFloat("t_q", s.q.toFloat()).putFloat("t_e", s.e.toFloat())
            .putInt("t_startL", s.startLevel).putInt("t_lastL", s.lastLevel)
            .putInt("t_anchL", s.anchorLevel).putFloat("t_qAnch", s.qAnchor.toFloat())
            .putInt("t_edgeL", s.edgeLevel).putFloat("t_qEdge", s.qEdge.toFloat())
            .putBoolean("t_inex", s.inexact)
            .putFloat("t_maxI", s.maxI).putFloat("t_maxP", s.maxP).putFloat("t_maxT", s.maxT)
            .putInt("t_plug", s.plugged)
            .putBoolean("t_scr", s.lastScreen)
            .putLong("t_onMs", s.onMs).putFloat("t_onQ", s.onMah.toFloat())
            .putLong("t_offMs", s.offMs).putFloat("t_offQ", s.offMah.toFloat())
            .apply()
    }

    private fun start(st: TrackState, type: Int, s: Sample, elapsed: Long, screenOn: Boolean) {
        st.type = type
        st.startTime = s.time
        st.lastElapsed = elapsed
        st.lastCurrent = s.currentMa
        st.lastCc = s.chargeCounterMah
        st.q = 0.0; st.e = 0.0
        st.startLevel = s.level; st.lastLevel = s.level
        st.anchorLevel = -1; st.qAnchor = 0.0
        st.edgeLevel = -1; st.qEdge = 0.0
        st.inexact = false
        st.maxI = 0f; st.maxP = 0f; st.maxT = s.tempC
        st.plugged = s.plugged
        st.lastScreen = screenOn
        st.onMs = 0; st.onMah = 0.0; st.offMs = 0; st.offMah = 0.0
    }

    private fun finish(ctx: Context, st: TrackState, p: SharedPreferences) {
        val now = System.currentTimeMillis()
        val durMs = now - st.startTime

        // Kapazitätsschätzung
        st.estimate()?.let { (cap, dl) ->
            if (abs(dl) >= MIN_DELTA_LEVEL && cap in 300.0..30000.0) {
                DataLog.appendEstimate(
                    ctx, Estimate(now, st.type == 1, dl, st.qEdge - st.qAnchor, cap, st.inexact)
                )
            }
        }
        // Ladevorgang speichern
        if (st.type == 1 && durMs >= 3 * 60_000L && st.lastLevel >= 0) {
            DataLog.appendSession(
                ctx, ChargeSession(
                    st.startTime, now, st.startLevel, st.lastLevel, st.q, st.e,
                    st.maxI, st.maxP, st.maxT, st.plugged
                )
            )
        }
        // Letzte Entladung für Standby-Auswertung merken
        if (st.type == -1 && durMs >= 30 * 60_000L) {
            p.edit()
                .putLong("ld_end", now).putLong("ld_start", st.startTime)
                .putLong("ld_onMs", st.onMs).putFloat("ld_onQ", st.onMah.toFloat())
                .putLong("ld_offMs", st.offMs).putFloat("ld_offQ", st.offMah.toFloat())
                .putInt("ld_startL", st.startLevel).putInt("ld_endL", st.lastLevel)
                .apply()
        }
    }

    @Synchronized
    /** Liefert die im Intervall geflossene Ladung in mAh (+ = Laden) oder NaN. */
    fun update(ctx: Context, s: Sample, elapsed: Long, intervalMs: Long, screenOn: Boolean): Double {
        val p = Prefs.get(ctx)
        val st = load(p)
        val type = if (s.isPlugged) 1 else -1

        // Erststart oder Neustart des Handys (elapsedRealtime springt zurück)
        if (st.type == 0 || st.lastElapsed > elapsed) {
            start(st, type, s, elapsed, screenOn); save(p, st); return Double.NaN
        }
        val dtMs = elapsed - st.lastElapsed
        if (dtMs <= 0) return Double.NaN

        val haveCc = !st.lastCc.isNaN() && !s.chargeCounterMah.isNaN()
        val gap = dtMs > max(3 * intervalMs, 90_000L)

        // Lange Lücke ohne Ladungszähler -> Abschnitt bis hierher werten und neu beginnen
        if (gap && !haveCc && dtMs > 30 * 60_000L) {
            finish(ctx, st, p); start(st, type, s, elapsed, screenOn); save(p, st); return Double.NaN
        }
        // Netzteil an/ab -> Abschnitt wechseln
        if (st.type != type) {
            finish(ctx, st, p); start(st, type, s, elapsed, screenOn); save(p, st); return Double.NaN
        }

        val dtH = dtMs / 3_600_000.0
        val haveI = !s.currentMa.isNaN() && !st.lastCurrent.isNaN()
        var dq = Double.NaN
        if (!gap) {
            if (haveI) dq = (st.lastCurrent + s.currentMa) / 2.0 * dtH
        } else if (haveCc) {
            // Gerät hat geschlafen: Ladungszähler des Fuel-Gauge überbrückt die Lücke
            dq = (s.chargeCounterMah - st.lastCc).toDouble()
        } else if (haveI) {
            dq = (st.lastCurrent + s.currentMa) / 2.0 * dtH
            if (st.anchorLevel >= 0) st.inexact = true
        }
        if (!dq.isNaN()) {
            st.q += dq
            st.e += dq / 1000.0 * s.voltageV
            if (st.lastScreen) { st.onMah += dq; st.onMs += dtMs } else { st.offMah += dq; st.offMs += dtMs }
            if (type == 1 && dq > 0) {
                p.edit().putFloat("totalChargeMah", p.getFloat("totalChargeMah", 0f) + dq.toFloat()).apply()
            }
        }

        if (!s.currentMa.isNaN()) {
            st.maxI = max(st.maxI, s.currentMa)
            st.maxP = max(st.maxP, s.powerW)
        }
        st.maxT = max(st.maxT, s.tempC)
        if (s.plugged != 0) st.plugged = s.plugged

        if (s.level >= 0 && st.lastLevel >= 0 && s.level != st.lastLevel) {
            if (st.anchorLevel < 0) {
                st.anchorLevel = s.level; st.qAnchor = st.q
            } else {
                st.edgeLevel = s.level; st.qEdge = st.q
            }
        }
        st.lastLevel = s.level
        st.lastElapsed = elapsed
        st.lastCurrent = s.currentMa
        st.lastCc = s.chargeCounterMah
        st.lastScreen = screenOn
        save(p, st)
        return dq
    }
}
