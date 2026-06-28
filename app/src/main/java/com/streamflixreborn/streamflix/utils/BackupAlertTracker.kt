package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 2026-06-21 (user "il faudrait créer une alerte extracteur quand il y en a un
 *   qui fonctionne plus comme ça, et que l'URL a vraiment changé et que lui ne
 *   peut pas la réparer Il faudrait que ça soit marqué dans nos log de
 *   l'application") :
 *
 * Détecte quand un BACKUP cross-provider (FrenchStream, Wiflix, Coflix,
 * Moiflix, Cloudstream, Moviebox, etc.) cesse de fonctionner — typiquement
 * parce que son URL/domaine a changé. Log une alerte visible dans logcat,
 * tag "BackupAlert", facile à filtrer.
 *
 * Usage côté backup :
 *
 *   try {
 *       val sources = fetchXxxBackup(...)
 *       BackupAlertTracker.recordSuccess("Xxx", sources.size)
 *   } catch (e: Throwable) {
 *       BackupAlertTracker.recordFailure("Xxx", e)
 *   }
 *
 * Persisté en SharedPreferences donc résiste aux restarts. Un succès reset
 * le compteur d'échecs consécutifs.
 */
object BackupAlertTracker {

    private const val TAG = "BackupAlert"
    private const val PREFS = "backup_alerts"

    /** Nombre d'échecs consécutifs avant déclenchement d'une alerte critique. */
    private const val CRITICAL_THRESHOLD = 5

    /** Suspicious-empty consécutifs avant alerte "search/index cassé". */
    private const val SUSPICIOUS_EMPTY_THRESHOLD = 10

    /** Famille de causes — sert à classifier l'erreur pour message clair. */
    enum class Reason(val label: String) {
        HOST_DOWN("HÔTE INJOIGNABLE (NXDOMAIN/ConnectException — domaine changé ou serveur HS)"),
        TIMEOUT("TIMEOUT (le serveur ne répond pas dans le temps imparti)"),
        SSL_ERROR("ERREUR SSL/TLS (certificat expiré ou hostname mismatch)"),
        HTTP_4XX("HTTP 4xx (URL changée ou endpoint supprimé)"),
        HTTP_5XX("HTTP 5xx (serveur en erreur)"),
        RATE_LIMITED("HTTP 429 (rate limit — temporaire)"),
        PARSE_FAILED("PARSE FAILED (structure HTML/JSON changée)"),
        SUSPICIOUS_EMPTY("RÉSULTAT VIDE SUSPECT (search trouve 0 — domaine probablement changé)"),
        UNKNOWN("ERREUR INCONNUE"),
    }

    private fun prefs(): android.content.SharedPreferences =
        StreamFlixApp.instance.applicationContext
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun keyLastSuccess(name: String) = "${name}_last_success"
    private fun keyConsecutive(name: String) = "${name}_consecutive"
    private fun keyLastReason(name: String) = "${name}_last_reason"
    private fun keyAlertedAt(name: String) = "${name}_alerted_at"

    /**
     * Enregistre un appel réussi (= au moins 1 source ramenée).
     * Reset le compteur d'échecs consécutifs.
     *
     * @param backupName nom court du backup (ex "FrenchStream", "Coflix", "Moiflix")
     * @param sourcesCount nombre de sources ramenées (pour log informatif)
     */
    fun recordSuccess(backupName: String, sourcesCount: Int = 0) {
        try {
            val p = prefs()
            val prevConsec = p.getInt(keyConsecutive(backupName), 0)
            p.edit()
                .putLong(keyLastSuccess(backupName), System.currentTimeMillis())
                .putInt(keyConsecutive(backupName), 0)
                .remove(keyLastReason(backupName))
                .remove(keyAlertedAt(backupName))
                .apply()
            if (prevConsec >= CRITICAL_THRESHOLD) {
                Log.i(TAG, "✅ BACKUP RESTORED: $backupName de nouveau OK après $prevConsec échecs consécutifs (sources=$sourcesCount)")
            }
        } catch (_: Throwable) {
            // silent
        }
    }

    /**
     * Enregistre un appel raté. Classifie automatiquement l'exception et
     * log une alerte si le seuil critique est atteint.
     *
     * @param backupName nom court du backup
     * @param error Throwable levé pendant le fetch (= classification auto)
     */
    fun recordFailure(backupName: String, error: Throwable) {
        recordFailureWithReason(backupName, classify(error), error.message ?: error::class.java.simpleName)
    }

    /**
     * Cas spécial : appel "réussi" (no exception) mais résultat VIDE alors qu'on
     * sait que du contenu existe (= search bien formée d'un titre populaire qui
     * ramène 0). Suggère un index cassé ou un endpoint redirect.
     */
    fun recordSuspiciousEmpty(backupName: String, context: String = "") {
        recordFailureWithReason(backupName, Reason.SUSPICIOUS_EMPTY, context)
    }

    private fun recordFailureWithReason(backupName: String, reason: Reason, detail: String) {
        try {
            val p = prefs()
            val consec = p.getInt(keyConsecutive(backupName), 0) + 1
            p.edit()
                .putInt(keyConsecutive(backupName), consec)
                .putString(keyLastReason(backupName), reason.name)
                .apply()
            val threshold = if (reason == Reason.SUSPICIOUS_EMPTY)
                SUSPICIOUS_EMPTY_THRESHOLD else CRITICAL_THRESHOLD
            if (consec == threshold) {
                // Premier passage au seuil → alerte critique.
                val lastSuccess = p.getLong(keyLastSuccess(backupName), 0L)
                val sinceMs = if (lastSuccess > 0L) System.currentTimeMillis() - lastSuccess else -1L
                val sinceStr = if (sinceMs > 0) " (dernier succès: ${formatDuration(sinceMs)})"
                    else " (aucun succès enregistré)"
                Log.e(TAG, "🚨 BACKUP ALERT: $backupName est CASSÉ — $consec échecs consécutifs$sinceStr — Cause: ${reason.label} — Détail: $detail")
                Log.e(TAG, "🚨 → Action requise: vérifier si le domaine du backup '$backupName' a migré ou si la structure de la page a changé")
                p.edit().putLong(keyAlertedAt(backupName), System.currentTimeMillis()).apply()
            } else if (consec > threshold && consec % 5 == 0) {
                // Re-rappel toutes les 5 échecs après l'alerte initiale.
                Log.w(TAG, "⚠️ BACKUP STILL DOWN: $backupName ($consec échecs cumulés) — Cause: ${reason.label}")
            }
        } catch (_: Throwable) {
            // silent
        }
    }

    /** Classifie une exception → Reason. */
    private fun classify(e: Throwable): Reason {
        // Walk cause chain
        var cur: Throwable? = e
        while (cur != null) {
            when (cur) {
                is UnknownHostException -> return Reason.HOST_DOWN
                is java.net.ConnectException -> return Reason.HOST_DOWN
                is SocketTimeoutException -> return Reason.TIMEOUT
                is SSLException -> return Reason.SSL_ERROR
            }
            cur = cur.cause
        }
        val msg = e.message ?: ""
        return when {
            msg.contains("429") -> Reason.RATE_LIMITED
            Regex("""\b4\d{2}\b""").containsMatchIn(msg) -> Reason.HTTP_4XX
            Regex("""\b5\d{2}\b""").containsMatchIn(msg) -> Reason.HTTP_5XX
            msg.contains("parse", ignoreCase = true) -> Reason.PARSE_FAILED
            msg.contains("timeout", ignoreCase = true) -> Reason.TIMEOUT
            else -> Reason.UNKNOWN
        }
    }

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}min"
            sec < 86400 -> "${sec / 3600}h"
            else -> "${sec / 86400}j"
        }
    }

    /** Snapshot pour debug / UI éventuelle. */
    data class BackupStatus(
        val name: String,
        val consecutiveFailures: Int,
        val lastReason: String?,
        val lastSuccessAt: Long,
        val alertedAt: Long,
    )

    /** Retourne le statut de tous les backups trackés (clés trouvées en prefs). */
    fun snapshot(): List<BackupStatus> {
        return try {
            val p = prefs()
            val all = p.all
            val names = all.keys.mapNotNull { k ->
                when {
                    k.endsWith("_last_success") -> k.removeSuffix("_last_success")
                    k.endsWith("_consecutive") -> k.removeSuffix("_consecutive")
                    else -> null
                }
            }.distinct()
            names.map { name ->
                BackupStatus(
                    name = name,
                    consecutiveFailures = p.getInt(keyConsecutive(name), 0),
                    lastReason = p.getString(keyLastReason(name), null),
                    lastSuccessAt = p.getLong(keyLastSuccess(name), 0L),
                    alertedAt = p.getLong(keyAlertedAt(name), 0L),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Pour debug — clear tout l'historique. */
    fun clearAll() {
        prefs().edit().clear().apply()
    }
}
