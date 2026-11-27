package com.passguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Service de premier plan (type specialUse) : suit le forfait, même en arrière-plan, et gère la notification et les alertes. */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, getString(R.string.channel_live), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                ID_LIVE,
                liveNotification(getString(R.string.app_name), getString(R.string.notif_starting)),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun lang(): String =
        if (resources.configuration.locales.get(0).language == "en") "en" else "fr"

    private suspend fun loop() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val meter = SpeedMeter()
        while (true) {
            val pass = PassStore.load(this)
            if (pass == null) {
                stopSelf()
                return
            }
            Heartbeat.touch(this)
            if (System.currentTimeMillis() >= pass.endMs) {
                endOfPass(pass)
                return
            }
            val used = currentUsed(this, pass)
            if (used >= pass.totalBytes && !pass.exhausted) {
                exhausted(used)
                return
            }
            val speed = meter.sample()
            updateLive(pass, used, speed)
            checkAlerts(pass, used)
            val fast = power.isInteractive && UsageReader.isMobileDataActive(this)
            delay(if (fast) 1_000L else 15_000L)
        }
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun liveNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateLive(pass: Pass, used: Long, speed: Long) {
        val lang = lang()
        val left = (pass.totalBytes - used).coerceAtLeast(0L)
        val title = if (left > 0L) {
            getString(R.string.notif_left_title, formatBytes(left, lang))
        } else {
            getString(R.string.notif_exhausted_title)
        }
        val text = getString(
            R.string.notif_live_text,
            formatBytes(used, lang), formatBytes(pass.totalBytes, lang), formatSpeed(speed, lang)
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_LIVE, liveNotification(title, text))
    }

    /** Seuils 30/50/80/90 (F8) ; 100 est géré à part par exhausted(), avec sa notification distincte. */
    private fun checkAlerts(pass: Pass, used: Long) {
        if (pass.totalBytes <= 0L) return
        val percent = (used * 100L / pass.totalBytes).toInt().coerceIn(0, 99)
        val level = alertLevelFor(percent)
        if (level <= pass.lastAlert) return
        PassStore.setAlert(this, level)
        val message = when (level) {
            90 -> getString(R.string.alert_90)
            80 -> getString(R.string.alert_80)
            50 -> getString(R.string.alert_50)
            else -> getString(R.string.alert_30)
        }
        notifyAlert(getString(R.string.app_name), message)
    }

    /** F8/F9 : le volume est épuisé avant la fin de la durée. Notification distincte, arrêt, historique conservé. */
    private fun exhausted(used: Long) {
        PassStore.setAlert(this, 100)
        PassStore.setExhausted(this, true)
        notifyAlert(getString(R.string.notif_exhausted_title), getString(R.string.alert_100))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** F9 : la durée est arrivée à expiration (indépendamment du volume restant). */
    private fun endOfPass(pass: Pass) {
        val lang = lang()
        val used = currentUsed(this, pass)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifyAlert(
            getString(R.string.notif_end_title),
            getString(R.string.notif_end_text, formatBytes(used, lang), formatBytes(pass.totalBytes, lang))
        )
        stopSelf()
    }

    private fun notifyAlert(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_ALERT, notification)
    }

    private companion object {
        const val CH_LIVE = "live"
        const val CH_ALERT = "alert"
        const val ID_LIVE = 1
        const val ID_ALERT = 2
    }
}
