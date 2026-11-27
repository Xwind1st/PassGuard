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

    /** Supprime le forfait actif. Si keepSummary, garde un court résumé consultable dans l'espace personnel (F11). */
    fun clear(ctx: Context, keepSummary: Boolean, usedAtDeletion: Long) {
        val current = load(ctx)
        if (keepSummary && current != null) {
            LastSummary.save(ctx, usedAtDeletion, current.totalBytes, current.startMs, System.currentTimeMillis())
        } else {
            LastSummary.clear(ctx)
        }
        prefs(ctx).edit()
            .remove("total").remove("start").remove("end").remove("alert").remove("exhausted")
            .remove("acc").remove("ctr").remove("elapsed")
            .apply()
    }
}

/** Court résumé du dernier forfait supprimé (F11), si l'utilisateur a choisi de le garder. */
object LastSummary {
    fun save(ctx: Context, used: Long, total: Long, startMs: Long, endMs: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_summary", "$used|$total|$startMs|$endMs")
            .apply()
    }

    /** Retourne (utilisé, total, début, fin) ou null si rien n'a été gardé. */
    fun read(ctx: Context): LongArray? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_summary", null) ?: return null
        val parts = raw.split("|").mapNotNull { it.toLongOrNull() }
        return if (parts.size == 4) parts.toLongArray() else null
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last_summary").apply()
    }
}

/** Horodatage du dernier passage du service : permet à l'interface de savoir si le suivi s'est tu sans le vouloir. */
object Heartbeat {
    private const val KEY = "heartbeat"
    private const val STALE_MS = 20_000L

    fun touch(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, System.currentTimeMillis()).apply()
    }

    fun isStale(ctx: Context): Boolean {
        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)
        return System.currentTimeMillis() - last > STALE_MS
    }
}

/** Calcule un nouveau Pass après un complément (F2 bis) : volume ajouté, durée prolongée en option, historique conservé. */
fun completePass(current: Pass, addBytes: Long, addDurationMs: Long, usedNow: Long): Pass {
    val now = System.currentTimeMillis()
    val newTotal = current.totalBytes + addBytes.coerceAtLeast(0L)
    val newEnd = if (addDurationMs > 0L) maxOf(current.endMs, now) + addDurationMs else current.endMs
    val newPercent = if (newTotal > 0L) (usedNow * 100L / newTotal).toInt().coerceIn(0, 100) else 0
    return current.copy(
        totalBytes = newTotal,
        endMs = newEnd,
        lastAlert = rearmedAlertLevel(current.lastAlert, newPercent),
        exhausted = usedNow >= newTotal
    )
}

/** Ajoute une ligne « Non attribué / système » quand l'écart entre le total exact et la somme des applications est significatif. */
fun withUnattributed(exactTotal: Long, apps: List<AppUsage>, label: String): List<AppUsage> {
    val sum = apps.sumOf { it.bytes }
    val gap = (exactTotal - sum).coerceAtLeast(0L)
    val threshold = maxOf(200 * KB, exactTotal / 50) // au moins 200 Ko, ou 2 % du total, pour éviter le bruit
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
    return SimpleDateFormat("d MMM", locale).format(Date(ms))
}
