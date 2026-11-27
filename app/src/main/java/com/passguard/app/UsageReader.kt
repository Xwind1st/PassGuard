package com.passguard.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

object UsageReader {

    private const val SYSTEM_UID = 1000

    /** Vrai si l'utilisateur a accordé « Accès à l'utilisation » à PassGuard. */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isBatteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun areNotificationsEnabled(ctx: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** Vrai si la connexion active passe par les données mobiles (pas seulement par le Wi-Fi). Sert à la cadence F7. */
    fun isMobileDataActive(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    } catch (e: Exception) {
        true // en cas de doute, on garde le rythme rapide plutôt que de rater de la consommation
    }

    /** Total mobile calculé par Android : sert de repli si le compteur direct n'existe pas. */
    fun mobileUsed(ctx: Context, start: Long, end: Long): Long {
        if (end <= start) return 0L
        return try {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val bucket = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end)
            bucket.rxBytes + bucket.txBytes
        } catch (e: Exception) {
            0L
        }
    }

    /** Consommation mobile par application entre start et end, la plus gourmande en premier. */
    fun perApp(ctx: Context, start: Long, end: Long, lang: String): List<AppUsage> {
        if (end <= start) return emptyList()
        val total = HashMap<Int, Long>()
        val background = HashMap<Int, Long>()
        try {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, end)
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val bytes = bucket.rxBytes + bucket.txBytes
                    if (bytes <= 0L) continue
                    val key = groupUid(bucket.uid)
                    total[key] = (total[key] ?: 0L) + bytes
                    if (bucket.state == NetworkStats.Bucket.STATE_DEFAULT) {
                        background[key] = (background[key] ?: 0L) + bytes
                    }
                }
            } finally {
                stats.close()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        val pm = ctx.packageManager
        return total.entries
            .map { AppUsage(it.key, labelFor(pm, it.key, lang), it.value, background[it.key] ?: 0L) }
            .sortedByDescending { it.bytes }
            .take(25)
    }

    private fun groupUid(uid: Int): Int = when {
        uid == NetworkStats.Bucket.UID_REMOVED -> uid
        uid == NetworkStats.Bucket.UID_TETHERING -> uid
        uid >= 0 && uid % 100000 < 10000 -> SYSTEM_UID
        else -> uid
    }

    @Suppress("DEPRECATION")
    private fun labelFor(pm: PackageManager, uid: Int, lang: String): String {
        val en = lang == "en"
        if (uid == NetworkStats.Bucket.UID_TETHERING) return if (en) "Hotspot sharing" else "Partage de connexion"
        if (uid == NetworkStats.Bucket.UID_REMOVED) return if (en) "Uninstalled apps" else "Applications désinstallées"
        if (uid == SYSTEM_UID) return if (en) "Android System" else "Système Android"
        val packages = pm.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) return if (en) "Other app" else "Autre application"
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packages[0], 0)).toString()
        } catch (e: Exception) {
            packages[0]
        }
    }
}

/**
 * Compte exactement les octets mobiles depuis le début du forfait, à partir du compteur du téléphone.
 * Les relevés sont cumulés dans les préférences : le total survit à un arrêt de l'application ou à un redémarrage.
 */
object Tracker {

    /** Octets consommés depuis le début du forfait, ou -1 si le téléphone n'expose pas ce compteur. */
    @Synchronized
    fun update(ctx: Context, pass: Pass): Long {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var acc = p.getLong("acc", 0L)
        if (System.currentTimeMillis() >= pass.endMs) return acc // forfait terminé dans le temps : le total est figé
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx < 0L || tx < 0L) return -1L
        val counter = rx + tx
        val elapsed = SystemClock.elapsedRealtime()
        val lastCounter = p.getLong("ctr", -1L)
        val lastElapsed = p.getLong("elapsed", -1L)
        if (lastCounter < 0L) {
            p.edit().putLong("ctr", counter).putLong("elapsed", elapsed).apply()
            return acc
        }
        val rebooted = elapsed < lastElapsed || counter < lastCounter
        val delta = if (rebooted) counter else counter - lastCounter
        if (delta != 0L || rebooted) {
            acc += delta
            p.edit().putLong("acc", acc).putLong("ctr", counter).putLong("elapsed", elapsed).apply()
        }
        return acc
    }

    /** Repart de zéro : à appeler quand un nouveau forfait (pas un complément) est fixé. */
    @Synchronized
    fun reset(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        val editor = p.edit().putLong("acc", 0L)
        if (rx >= 0L && tx >= 0L) {
            editor.putLong("ctr", rx + tx).putLong("elapsed", SystemClock.elapsedRealtime())
        } else {
            editor.remove("ctr").remove("elapsed")
        }
        editor.apply()
    }
}

/** Mesure le débit mobile en moyenne glissante sur une fenêtre de quelques secondes (F7). */
class SpeedMeter(private val windowMs: Long = 4_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun sample(): Long {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx < 0L || tx < 0L) return 0L
        val bytes = rx + tx
        val now = SystemClock.elapsedRealtime()
        samples.addLast(now to bytes)
        while (samples.size > 1 && now - samples.first().first > windowMs) samples.removeFirst()
        val (t0, b0) = samples.first()
        val dt = now - t0
        return if (dt > 0L && bytes >= b0) (bytes - b0) * 1000L / dt else 0L
    }
}

/** Octets consommés depuis le début du forfait : compteur exact, ou total d'Android en secours. */
fun currentUsed(ctx: Context, pass: Pass): Long {
    val exact = Tracker.update(ctx, pass)
    if (exact >= 0L) return exact
    return UsageReader.mobileUsed(ctx, pass.startMs, minOf(System.currentTimeMillis(), pass.endMs))
}
