package com.passguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Relance le suivi après un redémarrage ou une mise à jour, sauf si le forfait est déjà épuisé ou expiré (F10). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pass = PassStore.load(context) ?: return
        if (pass.exhausted) return
        if (System.currentTimeMillis() >= pass.endMs) return
        if (!UsageReader.hasUsageAccess(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (e: Exception) {
            // Android a refusé le lancement : le suivi reprendra à la prochaine ouverture de l'application,
            // ou via le bouton de relance manuelle sur l'accueil.
        }
    }
}
