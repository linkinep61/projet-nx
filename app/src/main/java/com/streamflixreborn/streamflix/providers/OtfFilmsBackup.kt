package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.OtfTvService

/**
 * 2026-09-19 (user « il devait juste servir de source de serveur, il devait pas avoir son propre
 * truc ») — catalogue VOD d'OTF TV exposé UNIQUEMENT comme source de secours.
 *
 * ⚠ CE N'EST PAS UN PROVIDER NAVIGABLE, et ça ne doit pas le redevenir. Une première version
 * l'avait inscrit dans `Provider.providers` avec ses propres rayons : c'était un contresens.
 * Ici, OTF se comporte comme Moviebox depuis juillet — il n'apparaît QUE dans la liste des
 * serveurs d'un film ouvert depuis un autre fournisseur, via `BackupRegistry.emit("OTF TV")`.
 *
 * COÛT RÉSEAU NUL : le catalogue films arrive dans la MÊME réponse `authV4.php` que les chaînes
 * du TV Hub (cf. [OtfTvService.parserFilms]). Cette source ne fait donc aucune requête à elle.
 *
 * CORRESPONDANCE VOLONTAIREMENT STRICTE — le catalogue OTF donne un titre et un nom de fichier,
 * mais **pas d'année**. Impossible donc de départager deux homonymes (il existe un « Ballerina »
 * de 2016 et un de 2026). On n'accepte QUE l'égalité exacte du titre après normalisation, contre
 * les titres connus de l'œuvre (TMDB en fournit plusieurs : original, français…). On rate ainsi
 * des films dont le libellé diffère un peu, et c'est assumé : proposer le MAUVAIS film sous le
 * bon titre est le seul résultat vraiment inacceptable.
 *
 * FILMS SEULEMENT : l'API OTF n'expose aucune série (l'onglet Series de l'app officielle ne
 * fonctionne pas non plus), donc on rend une liste vide dès qu'on nous demande un épisode.
 */
object OtfFilmsBackup {

    const val NOM_SOURCE = "OTF TV"

    /** Fichiers `.mkv` H.264/AAC servis en HTTP avec Range : lisibles tels quels par Media3. */
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

    /**
     * @param titres   titres connus de l'œuvre (original, FR…) — au moins un doit correspondre
     *                 EXACTEMENT au titre OTF après normalisation.
     * @param estFilm  false pour un épisode ⇒ rien à proposer.
     */
    suspend fun fetchOtfBackupServers(titres: List<String>, estFilm: Boolean): List<Video.Server> {
        if (!estFilm) return emptyList()
        val cibles = titres.map { normaliser(it) }.filter { it.length >= 2 }.toSet()
        if (cibles.isEmpty()) return emptyList()

        val films = try { OtfTvService.fetchFilmsFrancais() } catch (_: Throwable) { emptyList() }
        if (films.isEmpty()) return emptyList()

        return films
            .filter { normaliser(it.titreAffiche) in cibles }
            .distinctBy { it.fichier }
            .map { f ->
                Video.Server(
                    id = f.fichier,                 // ex. « 96360.mkv » — stable côté OTF
                    name = "OTF TV (VF)",
                    src = f.url,
                )
            }
    }

    /** `src` EST déjà l'URL du fichier : rien à résoudre, on pose juste l'en-tête attendu. */
    fun video(server: Video.Server): Video = Video(
        source = server.src,
        headers = mapOf("User-Agent" to USER_AGENT),
    )

    /** Minuscules, accents et ponctuation retirés — pour comparer « Le Garçon » et « Le Garcon ». */
    private fun normaliser(s: String): String {
        val nfd = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }
}
