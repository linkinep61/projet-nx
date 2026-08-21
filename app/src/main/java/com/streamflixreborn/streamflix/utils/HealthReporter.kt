package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 2026-07-29 (demande user) — TÉLÉMÉTRIE DE SANTÉ DES EXTRACTEURS via Cloudflare D1.
 *
 * But : un extracteur peut être bloqué dans UNE région (FAI/géo) et marcher ailleurs. Le compteur
 * LOCAL par appareil ne peut pas faire la différence → faux positifs. On envoie donc chaque résultat
 * d'extraction (succès/échec) au Worker communautaire (`streamflix-api`, base D1). Le Worker agrège
 * TOUS les utilisateurs et ne crée une issue GitHub QUE si l'échec est confirmé sur plusieurs pays
 * ET appareils (le pays est déduit côté Worker via request.cf.country — rien de perso envoyé).
 *
 * Les signalements MANUELS (reportBadMatch) et les crashs restent directs — non concernés ici.
 */
object HealthReporter {

    private const val TAG = "HealthReporter"

    /**
     * ⚠ 2026-08-17 — TÉLÉMÉTRIE D1 COUPÉE (base Cloudflare saturée, mails d'alerte reçus).
     *
     * Cause : [record] était appelé à CHAQUE issue d'extraction, succès COMPRIS
     * (`noteExtractorOutcome` → `ok=true` sur chaque réussite, `noteProviderResult` sur chaque
     * provider). Mesuré sur une seule lecture de Spider-Man ce soir : 22 sources interrogées,
     * 48 serveurs affichés, plus les tentatives d'extraction — soit plusieurs dizaines
     * d'événements, donc autant d'écritures D1, POUR UNE SEULE LECTURE, et par utilisateur.
     * Le lot de 10 / 90 s regroupait les requêtes HTTP mais PAS les écritures en base.
     *
     * Décision user : « couper les requêtes envoyées par l'application pour la casse et
     * remettre la même chose directement sur GitHub ».
     *
     * 2026-08-21 : le relais GitHub qui avait pris la suite (BrokenSourceReporter) a lui
     * aussi été retiré, à la demande du user — l'application ne signale donc plus rien
     * vers l'extérieur. Les échecs restent visibles en local via ExtractorFailureTracker
     * et l'écran « Extracteurs ».
     *
     * Pour réactiver un jour : repasser cette constante à `true` — mais il faudra d'abord
     * n'envoyer QUE les échecs (jamais `ok=true`) et échantillonner, sinon la base ressaturera.
     */
    private const val TELEMETRIE_D1_ACTIVE = false

    private const val ENDPOINT = "https://streamflix-api.logami61250.workers.dev/health/event"
    private const val PREFS = "health_reporter"
    private const val KEY_DEVICE = "anon_device_id"
    private const val FLUSH_AT = 10          // envoie dès 10 événements en attente
    private const val FLUSH_INTERVAL_MS = 90_000L // ou au moins toutes les 90 s

    private val queue = ArrayList<JSONObject>()
    @Volatile private var lastFlush = 0L
    @Volatile private var deviceId: String? = null

    private fun deviceId(): String {
        deviceId?.let { return it }
        return try {
            val prefs = StreamFlixApp.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_DEVICE, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE, id).apply()
            }
            deviceId = id; id
        } catch (_: Throwable) { "anon" }
    }

    /**
     * Enregistre un résultat d'extraction/source.
     * @param source nom de l'extracteur/provider (ex "Filemoon", "Movix (natif)")
     * @param kind   catégorie : dead | blocked | streamdead | empty | source | provider
     * @param ok     true = succès, false = échec
     */
    fun record(source: String, kind: String, ok: Boolean, errorType: String? = null, host: String? = null) {
        // 2026-08-17 : coupé net — plus AUCUNE requête vers la base D1. cf. TELEMETRIE_D1_ACTIVE.
        if (!TELEMETRIE_D1_ACTIVE) return
        if (source.isBlank()) return
        val ev = JSONObject().apply {
            put("source", source); put("kind", kind); put("ok", ok)
            if (!errorType.isNullOrBlank()) put("errorType", errorType)
            if (!host.isNullOrBlank()) put("host", host)
            put("appVersion", runCatching { BuildConfig.VERSION_NAME }.getOrDefault("?"))
        }
        val toSend: List<JSONObject>?
        synchronized(queue) {
            queue.add(ev)
            val due = queue.size >= FLUSH_AT ||
                (queue.isNotEmpty() && System.currentTimeMillis() - lastFlush > FLUSH_INTERVAL_MS)
            toSend = if (due) ArrayList(queue).also { queue.clear() } else null
        }
        if (toSend != null) flush(toSend)
    }

    private fun flush(events: List<JSONObject>) {
        lastFlush = System.currentTimeMillis()
        Thread {
            try {
                val payload = JSONObject().apply {
                    put("deviceId", deviceId())
                    put("events", JSONArray().apply { events.forEach { put(it) } })
                }
                val conn = (java.net.URL(ENDPOINT).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "ONYX-HealthReporter")
                    doOutput = true; connectTimeout = 12_000; readTimeout = 12_000
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                Log.d(TAG, "flush ${events.size} événements → HTTP $code")
            } catch (e: Throwable) {
                Log.w(TAG, "flush KO: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }
}
