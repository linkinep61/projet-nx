package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 2026-11-01 — SANTÉ DES SOURCES + SIGNALEMENT DES FICHIERS MORTS (plan « base auto-entretenue »,
 * volets 2 et 3).
 *
 * 1. Bilan de santé : compteurs ANONYMES par source (recherches, lectures, types d'erreur),
 *    envoyés groupés au plus toutes les 10 min, et à la fermeture du lecteur. Aucune adresse,
 *    aucun titre. La base en déduit les sources ou extracteurs cassés (user : « ça pourrait nous
 *    faire des choses à réparer gratuitement »).
 * 2. Fichier mort : une lecture qui échoue avec une VRAIE réponse « n'existe plus » (HTTP 404/410,
 *    ou contenu supprimé détecté par l'extracteur) d'un serveur mémorisé est signalée à la base.
 *    ⚠ Jamais pour un délai, une coupure, un 5xx ou une erreur du décodeur (user : « quelqu'un qui
 *    a une mauvaise connexion, on va vider la base de données »). Et un signalement ne retire
 *    RIEN : c'est le Worker qui contrôle lui-même, deux fois à 24 h d'intervalle.
 *
 * Désactivé en mode léger (petites box). Mon IPTV n'est jamais concerné (pas dans la base).
 */
object SanteSources {
    private const val TAG = "SanteSources"
    private const val URL_BASE = "https://onyx-index-test.logami61250.workers.dev"
    private const val INTERVALLE_MS = 10 * 60_000L

    // Index des compteurs : recherches, recherches avec serveurs, lectures tentées, lectures
    //   démarrées, puis erreurs introuvable / refusé / délai / extracteur / décodeur.
    private val CHAMPS = arrayOf("r", "rok", "l", "lok", "introuvable", "refuse", "delai", "extracteur", "decodeur")
    private val compteurs = ConcurrentHashMap<String, IntArray>()
    @Volatile private var dernierEnvoi = System.currentTimeMillis()
    /** Serveurs déjà comptés comme « démarrés » (un seul succès par serveur et par lecture). */
    private val demarres = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val signales = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 2026-11-02 : vrai pendant le banc de test des serveurs (version de test) - ses recherches et
     *  lectures d'essai ne doivent pas fausser le bilan partagé. */
    @Volatile var horsBilan = false

    private fun actif() = !UserPreferences.modeLeger && !horsBilan

    /** Source d'un serveur de secours (`bkreg::<source>::<id>`), sinon null. */
    private fun sourceDe(s: Video.Server): Pair<String, String>? {
        if (!s.id.startsWith(BackupRegistry.PREFIX)) return null
        val reste = s.id.removePrefix(BackupRegistry.PREFIX)
        val i = reste.indexOf("::").takeIf { it > 0 } ?: return null
        return reste.substring(0, i) to reste.substring(i + 2)
    }

    private fun ajouter(source: String, champ: Int) {
        // ⚠ Mon IPTV : JAMAIS dans la base partagée, même un simple compteur (règle user).
        if (!actif() || source.equals("Mon IPTV", ignoreCase = true)) return
        compteurs.getOrPut(source) { IntArray(CHAMPS.size) }.let { synchronized(it) { it[champ]++ } }
        if (System.currentTimeMillis() - dernierEnvoi >= INTERVALLE_MS) envoyer()
    }

    // ── Recherches ────────────────────────────────────────────────────────────────
    fun recherche(source: String, nbServeurs: Int) {
        ajouter(source, 0)
        if (nbServeurs > 0) ajouter(source, 1)
    }

    // ── Lectures ──────────────────────────────────────────────────────────────────
    fun essai(s: Video.Server) { sourceDe(s)?.let { demarres.remove(s.id); ajouter(it.first, 2) } }

    fun demarre(s: Video.Server?) {
        // 2026-11-02 : la vidéo a démarré → si la base avait ce lien en attente de retrait, on l'annule.
        s?.src?.let { LiensMorts.jouee(it) }
        val src = s?.let { sourceDe(it) } ?: return
        if (demarres.add(s.id)) ajouter(src.first, 3)
    }

    /** Échec d'extraction (avant la lecture). [classe] = Extractor.classifyError. */
    fun echecExtraction(s: Video.Server, e: Throwable, classe: String) {
        val src = sourceDe(s) ?: return
        val msg = (e.message ?: "").lowercase()
        val motif = when {
            classe == "dead-content" -> "introuvable"
            msg.contains("timeout") || e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException -> "delai"
            e is java.net.UnknownHostException || e is java.net.ConnectException -> "delai"
            else -> "extracteur"
        }
        ajouter(src.first, CHAMPS.indexOf(motif))
        if (motif == "introuvable") signalerMort(src.first, src.second, s.src, "contenu supprimé")
    }

    /** Échec du lecteur (après extraction). */
    fun echecLecture(s: Video.Server?, error: PlaybackException) {
        val src = s?.let { sourceDe(it) } ?: return
        val http = generateSequence(error as Throwable) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val motif = when {
            http == 404 || http == 410 -> "introuvable"
            http == 401 || http == 403 -> "refuse"
            http != null && http >= 500 -> "delai"
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> "delai"
            error.errorCode in 3000..4999 -> "decodeur"
            else -> "extracteur"
        }
        ajouter(src.first, CHAMPS.indexOf(motif))
        if (motif == "introuvable") signalerMort(src.first, src.second, s.src, "HTTP $http")
    }

    /** Fichier reconnu mort AVANT d'être proposé (cf. FileSearchProvider.filtrerMorts). [sid] = id d'origine. */
    fun mortVu(source: String, sid: String, adresse: String) = signalerMort(source, sid, adresse, "vérifié avant affichage")

    // ── Signalement d'un fichier mort ───────────────────────────────────────────────
    private fun signalerMort(source: String, sid: String, adresse: String, preuve: String) {
        if (!actif() || source !in IndexOnyx.SERVEURS_STABLES) return
        val f = IndexOnyx.ficheCourante() ?: return
        if (!signales.add("$source|$sid")) return
        portee.launch {
            runCatching {
                val corps = JSONObject().put("site", source).put("tmdb", f.first.toInt())
                    .put("type", if (f.second) "movie" else "tv").put("s", f.third.first).put("e", f.third.second)
                    .put("sid", sid).put("src", adresse).put("preuve", preuve).toString()
                client.newCall(Request.Builder().url("$URL_BASE/mort")
                    .post(corps.toRequestBody("application/json".toMediaTypeOrNull())).build())
                    .execute().use { Log.i(TAG, "fichier mort signalé : $source ($preuve) → HTTP ${it.code}") }
            }.onFailure { Log.w(TAG, "signalement échoué : ${it.message}") }
        }
    }

    // ── Envoi groupé du bilan ───────────────────────────────────────────────────────
    fun envoyer() {
        if (!actif() || compteurs.isEmpty()) return
        dernierEnvoi = System.currentTimeMillis()
        val stats = JSONObject()
        for (k in compteurs.keys.toList()) {
            val c = compteurs.remove(k) ?: continue
            val o = JSONObject()
            synchronized(c) { CHAMPS.forEachIndexed { i, n -> if (c[i] > 0) o.put(n, c[i]) } }
            if (o.length() > 0) stats.put(k, o)
        }
        if (stats.length() == 0) return
        portee.launch {
            runCatching {
                val corps = JSONObject().put("stats", stats).toString()
                client.newCall(Request.Builder().url("$URL_BASE/sante")
                    .post(corps.toRequestBody("application/json".toMediaTypeOrNull())).build())
                    .execute().use { Log.i(TAG, "bilan envoyé (${stats.length()} sources) → HTTP ${it.code}") }
            }.onFailure { Log.w(TAG, "bilan non envoyé : ${it.message}") }
        }
    }
}
