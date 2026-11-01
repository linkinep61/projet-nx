package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import java.text.Normalizer

/**
 * 2026-10-23 (user : « que Pluto et Plex servent de serveur, comme Wiflix », « bien garder le
 *   filtre pour avoir que du français ») — serveurs de secours tirés des catalogues GRATUITS
 *   À LA DEMANDE de Pluto TV et de Plex (ceux du TV Hub), interrogés par leur moteur de
 *   recherche : une requête par titre, pas de catalogue complet chargé.
 *
 *   Garde-fous :
 *   - TITRE identique (après normalisation) à l'un des titres connus de l'œuvre, ET année
 *     à ±1 an quand les deux sont connues → pas de faux film homonyme.
 *   - FRANÇAIS UNIQUEMENT :
 *       · Plex : piste audio FR CONFIRMÉE dans les métadonnées (film ou épisode) ;
 *       · Pluto : catalogue de la session France ; les films portent « -fr- » dans leur
 *         identifiant de version ; tout titre marqué VO/VOST est écarté.
 *   - La lecture passe par les résolveurs du TV Hub (plexvod:// et plutovod://).
 */
object PlexPlutoBackup {
    private const val TAG = "PlexPlutoBackup"

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun titreCorrespond(candidat: String, titres: Collection<String>): Boolean {
        val c = norm(candidat)
        return c.isNotEmpty() && titres.any { it.isNotBlank() && norm(it) == c }
    }

    private fun anneeCorrespond(candidat: Int?, voulue: Int?): Boolean =
        candidat == null || candidat <= 0 || voulue == null || voulue <= 0 ||
            kotlin.math.abs(candidat - voulue) <= 1

    private val RE_VO = Regex("\\b(vost|vostfr|vo)\\b", RegexOption.IGNORE_CASE)

    // ─────────────────────────────── Pluto ───────────────────────────────
    suspend fun pluto(
        titres: List<String>, annee: Int?, isMovie: Boolean, saison: Int, episode: Int,
    ): List<Video.Server> {
        val vus = HashSet<String>()
        for (t in titres.filter { it.isNotBlank() }.distinct().take(3)) {
            for (r in PlutoTvResolver.rechercher(t)) {
                if (!vus.add(r.id)) continue
                if (isMovie != (r.type == "movie")) continue
                // 2026-11-01 (user : « tu retires le filtrage de la langue ») : les VO/VOST ne sont
                //   plus écartées, elles arrivent étiquetées (cf. libelle).
                val estVo = RE_VO.containsMatchIn(r.nom)
                // le slug d'un film porte la version et l'année : « titre-fr-2002-1-1 »
                val anneeSlug = Regex("-(\\d{4})-\\d+-\\d+$").find(r.slug)?.groupValues?.get(1)?.toIntOrNull()
                val slugTitre = r.slug.substringBefore("-fr-").replace('-', ' ')
                if (!titreCorrespond(r.nom, titres) && !titreCorrespond(slugTitre, titres)) continue
                if (isMovie) {
                    if (!anneeCorrespond(anneeSlug, annee)) continue
                    // Version française = « -fr- » dans l'identifiant ; sinon VO (gardée, étiquetée).
                    val libelle = if (estVo) "VOSTFR" else if (r.slug.contains("-fr-")) "VF" else "VO"
                    Log.i(TAG, "Pluto film « ${r.nom} » ($anneeSlug, $libelle) → ${r.id}")
                    return listOf(Video.Server(id = "pluto-m-${r.id}", name = libelle, src = "plutovod://m/${r.id}"))
                }
                val ep = PlutoTvResolver.getSeasons(r.id)
                    .firstOrNull { it.number == saison }?.episodes?.firstOrNull { it.episode == episode }
                    ?: continue
                Log.i(TAG, "Pluto série « ${r.nom} » S${saison}E$episode → ${ep.id}")
                return listOf(Video.Server(id = "pluto-e-${ep.id}", name = if (estVo) "VOSTFR" else "VF", src = "plutovod://e/${ep.id}"))
            }
        }
        return emptyList()
    }

    // ─────────────────────────────── Plex ────────────────────────────────
    suspend fun plex(
        titres: List<String>, annee: Int?, isMovie: Boolean, saison: Int, episode: Int,
    ): List<Video.Server> {
        val vus = HashSet<String>()
        for (t in titres.filter { it.isNotBlank() }.distinct().take(3)) {
            for (r in PlexTvResolver.rechercherVod(t)) {
                if (!vus.add(r.rk)) continue
                if (isMovie != (r.type == "movie")) continue
                if (!isMovie && r.type != "show") continue
                val slugTitre = r.slug.replace('-', ' ')
                if (!titreCorrespond(r.titre, titres) && !titreCorrespond(slugTitre, titres)) continue
                if (!anneeCorrespond(r.annee, annee)) continue
                val rkLecture = if (isMovie) r.rk else {
                    val s = PlexTvResolver.getSeasons(r.rk).firstOrNull { it.index == saison } ?: continue
                    PlexTvResolver.getEpisodes(s.id, saison).firstOrNull { it.episode == episode }?.id ?: continue
                }
                // 2026-11-01 (user : « tu retires le filtrage de la langue ») : sans VF confirmée,
                //   le serveur n'est plus écarté — il arrive étiqueté « VO ».
                val vf = PlexTvResolver.audioFrancaisConfirme(rkLecture) == true
                Log.i(TAG, "Plex « ${r.titre} » (${r.annee}, ${if (vf) "VF" else "VO"}) → $rkLecture")
                return listOf(Video.Server(id = "plex-$rkLecture", name = if (vf) "VF" else "VO", src = "plexvod://$rkLecture"))
            }
        }
        return emptyList()
    }
}
