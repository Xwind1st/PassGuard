package com.passguard.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val PREFS = "passguard"
const val KB = 1_024L
const val MB = 1_024L * KB
const val GB = 1_024L * MB
const val SECOND_MS = 1_000L
const val MINUTE_MS = 60_000L
const val HOUR_MS = 3_600_000L
const val DAY_MS = 24 * HOUR_MS

/** Seuils d'alerte Heads-up, en pourcentage du forfait. 100 est géré à part (forfait épuisé, voir MonitorService). */
val ALERT_THRESHOLDS = intArrayOf(30, 50, 80, 90)

/** Palier déjà atteint pour un pourcentage donné (30/50/80/90), 0 si aucun. */
fun alertLevelFor(percent: Int): Int = ALERT_THRESHOLDS.lastOrNull { it <= percent } ?: 0

/** Après un complément (F2 bis) : ne réarme un seuil que si le nouveau pourcentage repasse en dessous. */
fun rearmedAlertLevel(oldLevel: Int, newPercent: Int): Int = minOf(oldLevel, alertLevelFor(newPercent))

/** Un forfait fixé par l'utilisateur. */
data class Pass(
    val totalBytes: Long,
    val startMs: Long,
    val endMs: Long,
    val lastAlert: Int = 0,
    val exhausted: Boolean = false
)

/** Consommation d'une application (ou d'une source spéciale) depuis le début du forfait. */
data class AppUsage(
    val uid: Int,
    val name: String,
    val bytes: Long,
    val bgBytes: Long
)

/** Photo de l'état du forfait à un instant donné. */
data class Snapshot(
    val used: Long,
    val speed: Long,
    val apps: List<AppUsage>
)

object PassStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Pass? {
        val p = prefs(ctx)
        if (!p.contains("total")) return null
        return Pass(
            totalBytes = p.getLong("total", 0L),
            startMs = p.getLong("start", 0L),
            endMs = p.getLong("end", 0L),
            lastAlert = p.getInt("alert", 0),
            exhausted = p.getBoolean("exhausted", false)
        )
    }

    fun save(ctx: Context, pass: Pass) {
        prefs(ctx).edit()
            .putLong("total", pass.totalBytes)
            .putLong("start", pass.startMs)
            .putLong("end", pass.endMs)
            .putInt("alert", pass.lastAlert)
            .putBoolean("exhausted", pass.exhausted)
            .apply()
    }

    fun setAlert(ctx: Context, level: Int) {
        prefs(ctx).edit().putInt("alert", level).apply()
    }

    fun setExhausted(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("exhausted", value).apply()
    }

    fun clear(ctx: Context, keepHistory: Boolean, usedNow: Long) {
        val current = load(ctx)
        val e = prefs(ctx).edit().clear()
        if (keepHistory && current != null) {
            e.putLong("hist_used", usedNow)
                .putLong("hist_total", current.totalBytes)
                .putLong("hist_start", current.startMs)
                .putLong("hist_end", current.endMs)
        }
        e.apply()
    }

    fun lastSummary(ctx: Context): Pass? {
        val p = prefs(ctx)
        if (!p.contains("hist_total")) return null
        return Pass(
            totalBytes = p.getLong("hist_total", 0L),
            startMs = p.getLong("hist_start", 0L),
            endMs = p.getLong("hist_end", 0L)
        ).let { it to p.getLong("hist_used", 0L) }.first
    }

    fun lastSummaryUsed(ctx: Context): Long =
        prefs(ctx).getLong("hist_used", 0L)
}

object Heartbeat {
    fun beat(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("heartbeat", System.currentTimeMillis()).apply()
    }

    fun isStale(ctx: Context): Boolean {
        val t = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("heartbeat", 0L)
        return t > 0L && System.currentTimeMillis() - t > 45_000L
    }
}

object Tracker {
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("base_rx")
            .remove("base_tx")
            .apply()
    }
}

fun currentUsed(ctx: Context, pass: Pass): Long =
    UsageReader.mobileUsedSince(ctx, pass.startMs)

fun completePass(current: Pass, addBytes: Long, addDurationMs: Long, usedNow: Long): Pass {
    val newTotal = current.totalBytes + addBytes
    val newEnd = if (addDurationMs > 0L) current.endMs + addDurationMs else current.endMs
    val percent = if (newTotal > 0L) ((usedNow * 100L) / newTotal).toInt().coerceIn(0, 100) else 0
    return current.copy(
        totalBytes = newTotal,
        endMs = newEnd,
        lastAlert = rearmedAlertLevel(current.lastAlert, percent),
        exhausted = false
    )
}

fun withUnattributed(exact: Long, apps: List<AppUsage>, label: String): List<AppUsage> {
    val attributed = apps.sumOf { it.bytes }
    val gap = exact - attributed
    val threshold = maxOf(32_768L, exact / 100L)
    if (gap < threshold) return apps
    return apps + AppUsage(uid = -999, name = label, bytes = gap, bgBytes = 0L)
}

fun bytesParts(bytes: Long, lang: String): Pair<String, String> {
    val b = bytes.coerceAtLeast(0L)
    val locale = if (lang == "en") Locale.US else Locale.FRANCE
    val go = if (lang == "en") "GB" else "Go"
    val mo = if (lang == "en") "MB" else "Mo"
    val ko = if (lang == "en") "KB" else "Ko"
    return when {
        b >= GB -> Pair(String.format(locale, "%.2f", b / GB.toDouble()), go)
        b >= 10 * MB -> Pair((b / MB).toString(), mo)
        b >= MB -> Pair(String.format(locale, "%.1f", b / MB.toDouble()), mo)
        else -> Pair((b / KB).toString(), ko)
    }
}

fun formatBytes(bytes: Long, lang: String): String {
    val (number, unit) = bytesParts(bytes, lang)
    return "$number $unit"
}

fun formatSpeed(bytesPerSecond: Long, lang: String): String = formatBytes(bytesPerSecond, lang) + "/s"

fun formatRemainingTime(ms: Long, lang: String): String {
    if (ms <= 0L) return if (lang == "en") "expired" else "expiré"
    val totalMinutes = ms / 60_000
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}${if (lang == "en") "d" else "j"} ${hours}h"
        hours > 0 -> "${hours}h ${minutes}min"
        else -> "${minutes}min"
    }
}

fun formatShortDate(ms: Long, lang: String): String {
    val locale = if (lang == "en") Locale.US else Locale.FRANCE
    return SimpleDateFormat("dd MMM yyyy", locale).format(Date(ms))
}
