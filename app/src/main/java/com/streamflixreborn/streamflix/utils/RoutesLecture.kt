package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 2026-10-25 (user : « un truc générique pour tout le monde, ça doit toucher d'autres chaînes ») —
 * hébergeurs dont le flux doit être lu par LE MÊME client réseau que celui qui a obtenu le lien
 * (OkHttp + DoH, cf. NetworkClient.default), et non par la pile du lecteur (DNS système / Cronet).
 *
 * Pourquoi : beaucoup de liens sont liés à l'adresse qui les a demandés (jeton signé sur l'IP ou la
 * route DNS). Si la lecture part d'une autre adresse — IPv4 au lieu d'IPv6, autre edge CDN — le
 * serveur répond 403 → écran noir, « ça marche sur l'Oppo et pas sur le Honor », « relancé, ça
 * lit ». On avait corrigé ça quatre fois à la main (Filemoon, Dailymotion, portails OLA, xlivetv)
 * en ajoutant des noms dans des listes. Désormais l'appli APPREND : au premier 403 d'un hébergeur
 * inconnu, elle relance une fois par le client d'extraction ; si la lecture part, l'hébergeur est
 * retenu ici et les fois suivantes passent directement par le bon chemin.
 *
 * Mémoire persistante (SharedPreferences) : apprise une fois, valable pour toutes les chaînes du
 * même hébergeur, tous lecteurs confondus (mini, grand mobile, grand TV).
 */
object RoutesLecture {

    private const val TAG = "RoutesLecture"
    private const val PREFS = "routes_lecture"
    private const val KEY_HOTES = "hotes_client_extraction"

    /** Hébergeurs connus d'avance (corrigés à la main avant cette règle). */
    private val CONNUS = listOf(
        "sprintcdn", "r66nv9ed.com", "cloudatacdn.com",
        "cdndirector.dailymotion.com", "dmcdn.net",
        // Vérifié le 2026-10-25 sur le Honor : avec xlivetv.com retiré d'ici, la règle l'a appris
        //   toute seule (1 seul 403, relance par le client d'extraction, lecture OK, hôte retenu).
        "xlivetv.com",
    )

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appris: Set<String> = emptySet()
    /** Essais en cours : hôte → heure du dernier essai (anti-boucle, 1 essai par hôte et par minute). */
    private val essais = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        appris = p.getStringSet(KEY_HOTES, emptySet())?.toSet() ?: emptySet()
        if (appris.isNotEmpty()) Log.i(TAG, "hébergeurs appris : $appris")
    }

    fun hote(url: String?): String? =
        runCatching { java.net.URI(url ?: return null).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** true → ce flux doit passer par le client d'extraction (connu d'avance ou appris). */
    fun exigeClientExtraction(url: String?): Boolean {
        val h = hote(url) ?: return false
        return CONNUS.any { h == it || h.endsWith(".$it") || h.contains(it) } ||
            appris.any { h == it || h.endsWith(".$it") }
    }

    /** Un 403 vient d'arriver sur cet hôte par la pile du lecteur : a-t-on le droit d'essayer
     *  l'autre chemin maintenant ? (une fois par hôte et par minute). */
    fun peutEssayer(url: String?): Boolean {
        val h = hote(url) ?: return false
        if (exigeClientExtraction(url)) return false   // déjà par le bon chemin → vrai refus
        val now = System.currentTimeMillis()
        val dernier = essais[h] ?: 0L
        if (now - dernier < 60_000L) return false
        essais[h] = now
        Log.w(TAG, "403 sur $h par la pile du lecteur → essai par le client d'extraction")
        return true
    }

    /** L'essai par le client d'extraction a RÉELLEMENT lu la vidéo → on retient l'hébergeur. */
    fun apprendre(url: String?) {
        val h = hote(url) ?: return
        if (exigeClientExtraction(url)) return
        // On retient le domaine « racine » (2 derniers labels) pour couvrir cdn1./cdn2. du même
        //   hébergeur, sauf si c'est trop court pour être sûr.
        val parties = h.split('.')
        val cle = if (parties.size >= 3 && parties.takeLast(2).joinToString(".").length > 6)
            parties.takeLast(2).joinToString(".") else h
        appris = appris + cle
        prefs?.edit()?.putStringSet(KEY_HOTES, appris)?.apply()
        Log.i(TAG, "hébergeur appris : $cle (flux lu par le client d'extraction après un 403)")
    }
}
