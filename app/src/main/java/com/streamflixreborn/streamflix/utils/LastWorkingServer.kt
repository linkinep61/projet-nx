package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Mémorise le dernier serveur qui a réellement joué pour un contenu donné.
 * Clé = contentId (movie id ou episode id). Valeur = server.id (String).
 *
 * Au resume, si le serveur est toujours dans la liste, on le met en premier
 * pour que l'auto-play reprenne dessus directement sans refaire tout le tri.
 *
 * Store persisté (SharedPrefs) pour survivre aux kills de process.
 * Max 200 entrées (LRU implicite via ordre d'insertion).
 */
object LastWorkingServer {

    private const val PREFS_NAME = "last_working_server"
    private const val MAX_ENTRIES = 200

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Enregistre le serveur qui a joué avec succès pour ce contenu. */
    fun save(context: Context, contentId: String, serverId: String) {
        if (contentId.isBlank() || serverId.isBlank()) return
        val p = prefs(context)
        val editor = p.edit()
        editor.putString(contentId, serverId)
        // Nettoyage LRU simple : si trop d'entrées, on vide tout (rare)
        if (p.all.size > MAX_ENTRIES) {
            editor.clear()
            editor.putString(contentId, serverId)
        }
        editor.apply()
    }

    /** Récupère le serverId du dernier serveur fonctionnel pour ce contenu, ou null. */
    fun get(context: Context, contentId: String): String? {
        if (contentId.isBlank()) return null
        return prefs(context).getString(contentId, null)
    }
}

/**
 * 2026-08-09 (user « les liens géobloqués, soit tu les mets en 2e, soit on les vire ») :
 * mémoire des HÔTES qui viennent de refuser.
 *
 * Origine : cinq flux RTP différents, tous servis par `streaming-live.rtp.pt`, échouaient
 * en bloc, tandis qu'un sixième sur un autre hôte lisait parfaitement. Quand un serveur
 * ferme la porte, il la ferme pour tous ses flux — retenir le lien fautif ne suffit donc
 * pas, il faut retenir l'hôte.
 *
 * Un hôte est déclaré fautif après [SEUIL] échecs, et le reste pendant [OUBLI_MS]. Ses liens
 * passent alors EN DERNIER dans la liste des serveurs, sans jamais être supprimés : un
 * blocage est presque toujours temporaire (quota, géo, VPN qu'on rallume), et supprimer
 * priverait l'utilisateur d'un lien qui redeviendra bon.
 *
 * Mémoire volatile, en RAM : tout est oublié au redémarrage, ce qui donne une seconde chance
 * gratuite à chaque lancement.
 */
object HotesEnEchec {
    private const val SEUIL = 2
    private const val OUBLI_MS = 15 * 60 * 1000L
    private val echecs = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    // 2026-09-06 : RénéVéo — un seul hôte pour toutes ses sources, chaque proxy compte à part
    //   (cf. ReneveoTv.cleHoteSiReneveo).
    private fun hote(url: String): String? = ReneveoTv.cleHoteSiReneveo(url) ?: try {
        java.net.URI(url).host?.lowercase()
    } catch (_: Throwable) { null }

    /** À appeler quand un serveur échoue. */
    fun signaler(url: String) {
        val h = hote(url) ?: return
        val maintenant = System.currentTimeMillis()
        val (n, t) = echecs[h] ?: (0 to maintenant)
        val compte = if (maintenant - t > OUBLI_MS) 1 else n + 1
        echecs[h] = compte to maintenant
    }

    /** Vrai si cet hôte a assez échoué récemment pour être relégué en fin de liste. */
    fun estFautif(url: String): Boolean {
        val h = hote(url) ?: return false
        val (n, t) = echecs[h] ?: return false
        if (System.currentTimeMillis() - t > OUBLI_MS) { echecs.remove(h); return false }
        return n >= SEUIL
    }
}
