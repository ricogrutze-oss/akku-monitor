package de.rico.akkumonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale

class MonitorService : Service() {

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        @Volatile var running = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        fun intervalMs(ctx: Context) = Prefs.get(ctx).getInt("intervalS", 10) * 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, intervalMs(this@MonitorService))
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Netzteil an/ab bzw. Display an/aus sofort erfassen
            if (i.action == Intent.ACTION_SCREEN_ON || i.action == Intent.ACTION_SCREEN_OFF) sample()
            else handler.postDelayed({ sample() }, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Akku-Messung", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(powerReceiver, f)
        }
        Alerts.ensureChannel(this)
        DataLog.cleanup(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification(null)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!running) {
            running = true
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(powerReceiver) } catch (e: Exception) {}
        releaseWakeLock()
        AppTracker.save(this)
        super.onDestroy()
    }

    private fun sample() {
        val s = BatteryReader.read(this) ?: return
        DataLog.append(this, s)
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val dq = Tracker.update(this, s, SystemClock.elapsedRealtime(), intervalMs(this), screenOn)
        AppTracker.update(this, dq, s.isPlugged, screenOn)
        updateWakeLock(s)
        Alerts.check(this, s)
        BatteryWidget.update(this, s)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    /** Beim Laden CPU wachhalten -> lückenlose Messung. Kostet nichts, da Netzteil dran. */
    private fun updateWakeLock(s: Sample) {
        val p = Prefs.get(this)
        val want = (s.isPlugged && p.getBoolean("wlCharge", true)) || p.getBoolean("wlAlways", false)
        if (want) {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AkkuMonitor:messung").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(10 * 60 * 1000L) // wird bei jedem Messpunkt verlängert
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(s: Sample?): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pi)
        if (s == null) {
            b.setContentTitle("Akku-Monitor misst …")
        } else {
            val cur = if (s.currentMa.isNaN()) "– mA" else String.format(Locale.GERMANY, "%+.0f mA", s.currentMa)
            val pw = if (s.currentMa.isNaN()) "–" else String.format(Locale.GERMANY, "%+.2f W", s.powerW)
            b.setContentTitle("${s.level} %  ·  $cur  ·  $pw")
            b.setContentText(
                String.format(Locale.GERMANY, "%.3f V · %.1f °C · %s", s.voltageV, s.tempC, BatteryReader.statusText(s))
            )
        }
        return b.build()
    }
}
