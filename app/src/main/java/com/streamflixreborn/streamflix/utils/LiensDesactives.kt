package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Liens (serveurs) désactivés à la main par l'utilisateur.
 *
 * ── 2026-08-06 — demande d'un testeur, amendée par le user ───────────────────────────────
 *   L'appui long sur un serveur le **désactive** : il n'est plus proposé, et reste
 *   récupérable depuis « Paramètres › Liens désactivés ».
 *
 * ── 2026-08-11 — DEUX CORRECTIONS, dans l'ordre où le user les a demandées ───────────────
 *
 *   1. « ça devrait pas être caché, ça devrait être désactivé ; on a mis ces options pour
 *      les désactiver complètement, pour éviter que ce soit recherché »
 *      → le filtre n'était appliqué qu'à l'affichage de la liste du lecteur. Il est
 *        désormais posé dans [ExtractorRanker.rankServers], par où PlayerViewModel fait
 *        passer chaque lot avant de l'exposer : un lien désactivé n'est ni affiché, ni
 *        pré-extrait, ni jouable.
 *
 *   2. « je veux juste être sûr que chaque lien vraiment désactivé ne revienne pas »
 *      → il revenait. La clé était l'`id` du serveur, or beaucoup de providers le
 *        fabriquent avec la POSITION dans la liste :
 *
 *            id = "tmdbmovix-${list.size}"        (MovixProvider)
 *            id = "wiflix-$lang-$index"
 *
 *        `tmdbmovix-1` ne désigne donc pas un lien mais « le 2ᵉ que le site a renvoyé
 *        cette fois-là ». Au scrape suivant, un lien de moins ou un ordre différent et :
 *          • le lien désactivé revient sous un autre numéro ;
 *          • un lien INNOCENT hérite du numéro et se retrouve désactivé à sa place.
 *
 *        La clé est maintenant l'**URL** du lien (`Video.Server.src`), qui identifie
 *        physiquement la ressource et ne dépend d'aucun ordre. L'`id` reste mémorisé et
 *        continue d'être comparé, pour que les liens désactivés AVANT cette correction —
 *        qui n'ont pas d'URL enregistrée — restent désactivés.
 *
 *   On mémorise donc trois champs : `url` (la clé), `id` (compatibilité) et `nom`
 *   (l'affichage — un id comme `bkreg::Coflix Boston::coflix_14` ne dit rien à personne).
 */
object LiensDesactives {

    private const val CLE = "liens_desactives_v1"

    /** Une entrée désactivée. `url` est vide pour les entrées d'avant le 2026-08-11. */
    data class Lien(val id: String, val nom: String, val url: String)

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun liens(ctx: Context): List<Lien> {
        val brut = prefs(ctx).getString(CLE, null) ?: return emptyList()
        return try {
            val tableau = JSONArray(brut)
            buildList {
                for (i in 0 until tableau.length()) {
                    val o = tableau.getJSONObject(i)
                    val id = o.optString("id")
                    add(
                        Lien(
                            id = id,
                            nom = o.optString("nom", id),
                            url = o.optString("url", ""),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** id → nom lisible. Conservé pour les écrans de réactivation existants. */
    fun tous(ctx: Context): Map<String, String> =
        liens(ctx).associate { it.id to it.nom }

    /**
     * Un serveur est-il désactivé ?
     *
     * On teste l'URL D'ABORD (clé stable), puis l'id (entrées anciennes). Une URL vide
     * n'est jamais comparée, sinon toutes les entrées d'avant la correction feraient
     * correspondre n'importe quel serveur sans URL.
     */
    fun estDesactive(ctx: Context, id: String, url: String?): Boolean {
        val liens = liens(ctx)
        if (liens.isEmpty()) return false
        if (!url.isNullOrBlank() && liens.any { it.url.isNotBlank() && it.url == url }) return true
        return id.isNotBlank() && liens.any { it.id == id }
    }

    /** Ancienne signature (id seul) — gardée pour les appels qui n'ont pas l'URL sous la main. */
    fun estDesactive(ctx: Context, id: String): Boolean = estDesactive(ctx, id, null)

    fun desactiver(ctx: Context, id: String, nom: String, url: String?) {
        if (id.isBlank() && url.isNullOrBlank()) return
        val restants = liens(ctx).filterNot {
            it.id == id || (!url.isNullOrBlank() && it.url == url)
        }
        ecrire(ctx, restants + Lien(id = id, nom = nom, url = url.orEmpty()))
    }

    /**
     * Réactive par id. Retire AUSSI toute entrée partageant la même URL : sans ça, un lien
     * désactivé deux fois (avant/après la correction, donc sous deux ids) resterait bloqué
     * par son doublon après avoir été « réactivé ».
     */
    fun reactiver(ctx: Context, id: String) {
        val liens = liens(ctx)
        val cible = liens.firstOrNull { it.id == id }
        val url = cible?.url.orEmpty()
        ecrire(
            ctx,
            liens.filterNot { it.id == id || (url.isNotBlank() && it.url == url) },
        )
    }

    fun toutReactiver(ctx: Context) = ecrire(ctx, emptyList())

    private fun ecrire(ctx: Context, liste: List<Lien>) {
        val tableau = JSONArray()
        liste.forEach { l ->
            tableau.put(
                JSONObject()
                    .put("id", l.id)
                    .put("nom", l.nom)
                    .put("url", l.url),
            )
        }
        prefs(ctx).edit().putString(CLE, tableau.toString()).apply()
    }
}
