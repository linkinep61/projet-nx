package com.streamflixreborn.streamflix.utils

import android.util.Log
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
 * 2026-11-02 (user : « les serveurs rouges où la vidéo est vraiment HS […] ne pas les renvoyer en
 * automatique » ; « sur un film qui a 140 sources dont 90 HS, qu'on ne s'amuse pas à chercher »)
 *
 * LISTE NOIRE DES LIENS MORTS, partagée par la base (Worker onyx-index-test, table liens_morts).
 *  1. [signaler] : un extracteur a lu une VRAIE page « supprimée / introuvable / 404 » chez
 *     l'hébergeur (erreur classée « dead-content ») → le lien est envoyé à la base. Jamais pour un
 *     délai, un refus (403), une coupure ou un blocage de région : ceux-là ne sont pas « dead-content ».
 *  2. La base revérifie ELLE-MÊME la page, deux fois à 24 h d'intervalle (ou 3 appareils sur 2 jours
 *     si l'hébergeur ne se laisse pas relire) avant de confirmer.
 *  3. [estMort] : les liens confirmés ne sont plus proposés (filtre de BackupRegistry.pushServers).
 *     Un lien revenu à la vie sort de la liste au contrôle mensuel ; une vidéo remise en ligne a
 *     un nouveau lien, donc n'est jamais concernée.
 */
object LiensMorts {
    private const val TAG = "LiensMorts"
    private const val URL_BASE = "https://onyx-index-test.logami61250.workers.dev"
    private const val RAFRAICHIR_MS = 3 * 3600_000L

    @Volatile private var ensemble: Set<String> = emptySet()
    /** Liens signalés mais pas encore confirmés : si on en JOUE un, on le dit à la base (ça annule). */
    @Volatile private var attente: Set<String> = emptySet()
    private val annonces = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var chargeLe = 0L
    private val signales = ConcurrentHashMap.newKeySet<String>()
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Même normalisation que le Worker (normLien) : hôte sans « www. » + chemin + paramètres + ancre. */
    fun norm(lien: String): String? = runCatching {
        val u = java.net.URI(lien.trim())
        val hote = u.host?.lowercase()?.removePrefix("www.") ?: return null
        hote + (u.rawPath ?: "").trimEnd('/') + (u.rawQuery?.let { "?$it" } ?: "") + (u.rawFragment?.let { "#$it" } ?: "")
    }.getOrNull()?.take(400)

    /** Vrai si la base a confirmé ce lien comme supprimé chez l'hébergeur. */
    fun estMort(src: String): Boolean {
        rafraichirSiBesoin()
        if (ensemble.isEmpty() || !src.startsWith("http")) return false
        return norm(src)?.let { it in ensemble } == true
    }

    /** Recharge la liste (au plus toutes les 3 h, en arrière-plan, sans jamais bloquer). */
    fun rafraichirSiBesoin() {
        val maintenant = System.currentTimeMillis()
        if (maintenant - chargeLe < RAFRAICHIR_MS) return
        chargeLe = maintenant
        portee.launch {
            runCatching {
                client.newCall(Request.Builder().url("$URL_BASE/liens-morts").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val json = JSONObject(r.body?.string().orEmpty())
                    val liste = json.optJSONArray("liens") ?: return@use
                    ensemble = (0 until liste.length()).map { liste.optString(it) }.filter { it.isNotBlank() }.toHashSet()
                    val att = json.optJSONArray("attente")
                    attente = if (att == null) emptySet() else (0 until att.length()).map { att.optString(it) }.filter { it.isNotBlank() }.toHashSet()
                    Log.i(TAG, "${ensemble.size} lien(s) mort(s) confirmé(s) par la base")
                }
            }.onFailure { chargeLe = 0L; Log.w(TAG, "liste non chargée : ${it.message}") }
        }
    }

    /**
     * 2026-11-02 (user : « s'il y a des retours comme quoi ça marche, ça doit annuler ») - la vidéo
     * de ce lien a DÉMARRÉ à l'écran. Si la base l'avait en attente (signalé / suspect), on lui dit :
     * le signalement est annulé. Envoyé une fois par session et seulement pour les liens en attente.
     */
    fun jouee(lien: String) {
        if (!lien.startsWith("http") || attente.isEmpty()) return
        val n = norm(lien) ?: return
        if (n !in attente || !annonces.add(n)) return
        portee.launch {
            runCatching {
                val corps = JSONObject().put("lien", lien).toString()
                client.newCall(Request.Builder().url("$URL_BASE/lien-vivant")
                    .post(corps.toRequestBody("application/json".toMediaTypeOrNull())).build())
                    .execute().use { Log.i(TAG, "lien joué, signalement annulé : $n → HTTP ${it.code}") }
            }.onFailure { Log.w(TAG, "annulation non envoyée : ${it.message}") }
        }
    }

    /** Lien lu « supprimé » par un extracteur : envoyé une fois par session à la base (qui vérifie). */
    fun signaler(lien: String, preuve: String?) {
        if (!lien.startsWith("http")) return
        val n = norm(lien) ?: return
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}""").containsMatchIn(n)) return   // adresse IP : non contrôlable
        if (!signales.add(n)) return
        portee.launch {
            runCatching {
                val corps = JSONObject().put("lien", lien).put("preuve", (preuve ?: "").take(120)).toString()
                client.newCall(Request.Builder().url("$URL_BASE/lien-mort")
                    .post(corps.toRequestBody("application/json".toMediaTypeOrNull())).build())
                    .execute().use { Log.i(TAG, "lien mort signalé : $n → HTTP ${it.code}") }
            }.onFailure { Log.w(TAG, "signalement non envoyé : ${it.message}") }
        }
    }
}
