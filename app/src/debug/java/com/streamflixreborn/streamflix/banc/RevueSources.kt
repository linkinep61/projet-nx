package com.streamflixreborn.streamflix.banc

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 2026-11-12 (user : « il va falloir tout vérifier, un par un et minutieusement », après deux
 * sources trouvées cassées par hasard) — REVUE DES SOURCES, version de test uniquement.
 * Pour chaque source de l'appli : accueil, liste films (pages 1 et 2), liste séries (pages 1 et 2),
 * recherche, une fiche film + ses serveurs, une fiche série + épisodes + serveurs, et un essai
 * d'extraction. Chaque anomalie repérable par le code est notée « ALERTE ».
 * Lancement : adb … BancTestActivity --ez revue true [--es seul "nom de source"].
 * Résultat : logcat (étiquette REVUE) et files/banc/revue.txt.
 */
object RevueSources {
    private const val TAG = "REVUE"

    private suspend fun <T> essai(ms: Long, bloc: suspend () -> T): Result<T> = try {
        Result.success(withTimeout(ms) { withContext(Dispatchers.IO) { bloc() } })
    } catch (e: Throwable) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        Result.failure(e)
    }

    private fun err(e: Throwable?): String =
        if (e is TimeoutCancellationException) "délai dépassé" else "${e?.javaClass?.simpleName}: ${e?.message?.take(110)}"

    private fun titre(i: AppAdapter.Item): String = when (i) {
        is Movie -> i.title
        is TvShow -> i.title
        else -> "(${i.javaClass.simpleName})"
    }

    private fun norme(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun hote(u: String) = runCatching { android.net.Uri.parse(u).host }.getOrNull() ?: u.take(30)

    suspend fun lancer(seul: String?, fichier: File, affiche: (String) -> Unit) {
        fichier.parentFile?.mkdirs()
        fichier.writeText("")
        fun out(l: String) {
            Log.i(TAG, l)
            runCatching { fichier.appendText(l + "\n") }
            affiche(l)
        }
        val avant = runCatching { UserPreferences.currentProvider }.getOrNull()
        val sources = Provider.providers.filter { seul.isNullOrBlank() || it.key.name.contains(seul, ignoreCase = true) }
        out("DEBUT revue de ${sources.size} source(s)")
        for ((p, sup) in sources) {
            out("== ${p.name} [${sup.group}] films=${sup.movies} series=${sup.tvShows}")
            runCatching { UserPreferences.currentProvider = p }
            try {
                une(p, sup, ::out)
            } catch (e: Throwable) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                out("  ALERTE revue interrompue : ${err(e)}")
            }
            out("-- fin ${p.name}")
        }
        runCatching { UserPreferences.currentProvider = avant }
        out("FIN revue")
    }

    private suspend fun une(p: Provider, sup: Provider.Companion.ProviderSupport, out: (String) -> Unit) {
        // ── Accueil ──
        var premierFilm: Movie? = null
        var premiereSerie: TvShow? = null
        val accueil = essai(40_000L) { p.getHome() }
        accueil.onFailure { out("  ALERTE accueil : ${err(it)}") }
        accueil.onSuccess { cats ->
            if (cats.isEmpty()) out("  ALERTE accueil vide")
            for (c in cats) {
                val f = c.list.count { it is Movie }
                val s = c.list.count { it is TvShow }
                out("  accueil « ${c.name.take(40)} » : ${c.list.size} ($f films, $s séries) : " +
                    c.list.take(3).joinToString(" | ") { titre(it).take(28) })
                if (c.list.isEmpty()) out("  ALERTE rubrique vide : ${c.name}")
            }
            // Deux rubriques au contenu quasi identique (le cas FrenchStream du 2026-11-12).
            val jeux = cats.filter { it.list.size >= 5 }.map { c -> c.name to c.list.map { norme(titre(it)) }.toSet() }
            for (i in jeux.indices) for (j in i + 1 until jeux.size) {
                val commun = jeux[i].second.intersect(jeux[j].second).size
                val petit = minOf(jeux[i].second.size, jeux[j].second.size)
                if (petit > 0 && commun * 100 / petit >= 60)
                    out("  ALERTE rubriques presque identiques : « ${jeux[i].first} » et « ${jeux[j].first} » ($commun titres communs sur $petit)")
            }
            premierFilm = cats.flatMap { it.list }.filterIsInstance<Movie>().firstOrNull()
            premiereSerie = cats.flatMap { it.list }.filterIsInstance<TvShow>().firstOrNull()
        }

        // ── Listes films / séries, pages 1 et 2 ──
        var films: List<Movie> = emptyList()
        var series: List<TvShow> = emptyList()
        if (sup.movies) {
            val r1 = essai(40_000L) { p.getMovies(1) }
            r1.onFailure { out("  ALERTE films page 1 : ${err(it)}") }
            films = r1.getOrDefault(emptyList())
            if (r1.isSuccess) out("  films p1 : ${films.size} : " + films.take(5).joinToString(" | ") { it.title.take(26) })
            if (r1.isSuccess && films.isEmpty()) out("  ALERTE liste films vide")
            if (films.isNotEmpty()) {
                val r2 = essai(40_000L) { p.getMovies(2) }
                r2.onFailure { out("  ALERTE films page 2 : ${err(it)}") }
                r2.onSuccess { l ->
                    out("  films p2 : ${l.size} : " + l.take(3).joinToString(" | ") { it.title.take(26) })
                    if (l.isNotEmpty() && l.map { it.id } == films.map { it.id }) out("  ALERTE films : page 2 identique à la page 1")
                }
            }
        }
        if (sup.tvShows) {
            val r1 = essai(40_000L) { p.getTvShows(1) }
            r1.onFailure { out("  ALERTE séries page 1 : ${err(it)}") }
            series = r1.getOrDefault(emptyList())
            if (r1.isSuccess) out("  séries p1 : ${series.size} : " + series.take(5).joinToString(" | ") { it.title.take(26) })
            if (r1.isSuccess && series.isEmpty()) out("  ALERTE liste séries vide")
            if (series.isNotEmpty()) {
                val r2 = essai(40_000L) { p.getTvShows(2) }
                r2.onFailure { out("  ALERTE séries page 2 : ${err(it)}") }
                r2.onSuccess { l ->
                    out("  séries p2 : ${l.size} : " + l.take(3).joinToString(" | ") { it.title.take(26) })
                    if (l.isNotEmpty() && l.map { it.id } == series.map { it.id }) out("  ALERTE séries : page 2 identique à la page 1")
                }
            }
        }
        if (films.isNotEmpty() && series.isNotEmpty()) {
            val commun = films.map { norme(it.title) }.toSet().intersect(series.map { norme(it.title) }.toSet()).size
            if (commun * 100 / minOf(films.size, series.size) >= 30)
                out("  ALERTE films et séries se recouvrent : $commun titres communs")
        }
        val film = films.firstOrNull() ?: premierFilm
        val serie = series.firstOrNull() ?: premiereSerie

        // ── Recherche ──
        // Préfixes ajoutés par l'appli (« (FR) », « ┃FR┃ ») retirés : personne ne les tape.
        val mot = (film?.title ?: serie?.title)?.replace(Regex("^\\s*(\\([^)]*\\)|┃[^┃]*┃|\\|[^|]*\\|)\\s*"), "")
            ?.split(Regex("[\\s:\\-–]+"))?.filter { it.length >= 3 }?.take(2)?.joinToString(" ")
        if (!mot.isNullOrBlank()) {
            val r = essai(40_000L) { p.search(mot, 1) }
            r.onFailure { out("  ALERTE recherche « $mot » : ${err(it)}") }
            r.onSuccess { l ->
                out("  recherche « $mot » : ${l.size} : " + l.take(3).joinToString(" | ") { titre(it).take(26) })
                if (l.isEmpty()) out("  ALERTE recherche sans résultat pour un titre de la source")
            }
        }

        // ── Fiche film + serveurs + extraction ──
        // Dans les onglets « FR » des sources d'animés, un élément « film » peut être une série
        //   (isSeries) : l'appli l'ouvre alors comme série. On ne le teste donc pas comme un film.
        if (film != null && film.isSeries) out("  (premier « film » marqué série par la source : ouvert comme série dans l'appli)")
        if (film != null && !film.isSeries) {
            val fiche = essai(40_000L) { p.getMovie(film.id) }
            fiche.onFailure { out("  ALERTE fiche film « ${film.title.take(30)} » : ${err(it)}") }
            val m = fiche.getOrNull()
            if (m != null) {
                out("  fiche film « ${m.title.take(30)} » : résumé ${m.overview?.length ?: 0} car., affiche ${if (m.poster.isNullOrBlank()) "NON" else "oui"}")
                if (m.title.isBlank()) out("  ALERTE fiche film sans titre")
            }
            val annee = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format((m ?: film).released!!.time) }.getOrDefault("")
            val type = Video.Type.Movie(id = film.id, title = m?.title?.ifBlank { null } ?: film.title, releaseDate = annee,
                poster = film.poster ?: "", imdbId = m?.imdbId)
            serveurs(p, film.id, type, "film", out)
        }
        if (film == null && sup.movies) out("  ALERTE aucun film à ouvrir")

        // ── Fiche série + épisodes + serveurs + extraction ──
        if (serie != null) {
            val fiche = essai(45_000L) { p.getTvShow(serie.id) }
            fiche.onFailure { out("  ALERTE fiche série « ${serie.title.take(30)} » : ${err(it)}") }
            fiche.onSuccess { t ->
                out("  fiche série « ${t.title.take(30)} » : ${t.seasons.size} saison(s), résumé ${t.overview?.length ?: 0} car.")
                val saison = t.seasons.firstOrNull { it.number == 1 } ?: t.seasons.firstOrNull()
                if (saison == null) { out("  ALERTE série sans saison"); return@onSuccess }
                val eps = essai(45_000L) { p.getEpisodesBySeason(saison.id) }
                eps.onFailure { out("  ALERTE épisodes (saison ${saison.number}) : ${err(it)}") }
                eps.onSuccess { l ->
                    out("  épisodes saison ${saison.number} : ${l.size}")
                    val ep = l.firstOrNull()
                    if (ep == null) { out("  ALERTE saison sans épisode"); return@onSuccess }
                    val type = Video.Type.Episode(
                        id = ep.id, number = ep.number, title = ep.title, poster = ep.poster, overview = ep.overview,
                        // Comme l'appli : l'année de la série est transmise (Cloudstream s'en sert pour
                        //   choisir la bonne fiche ; sans elle, « aucun candidat d'année proche »).
                        tvShow = Video.Type.Episode.TvShow(id = t.id.ifBlank { serie.id }, title = t.title.ifBlank { serie.title },
                            poster = t.poster, banner = t.banner,
                            releaseDate = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(t.released!!.time) }.getOrNull(),
                            imdbId = t.imdbId),
                        season = Video.Type.Episode.Season(number = saison.number, title = saison.title),
                    )
                    serveurs(p, ep.id, type, "épisode", out)
                }
            }
        } else if (sup.tvShows) out("  ALERTE aucune série à ouvrir")
    }

    /** Liste les serveurs puis tente l'extraction sur les 3 premiers (sans lecture). */
    private suspend fun serveurs(p: Provider, id: String, type: Video.Type, quoi: String, out: (String) -> Unit) {
        val r = essai(60_000L) { p.getServers(id, type) }
        r.onFailure { out("  ALERTE serveurs $quoi : ${err(it)}") }
        val l = r.getOrNull() ?: return
        out("  serveurs $quoi : ${l.size} : " + l.take(8).joinToString(" | ") { it.name.take(24) })
        if (l.isEmpty()) { out("  ALERTE aucun serveur pour ce $quoi"); return }
        var bon = false
        for (srv in l.take(3)) {
            val v = essai(45_000L) { p.getVideo(srv) }
            v.onSuccess { vid ->
                val mode = if (vid.needsWebViewClick) "lecteur intégré (clic)" else "flux ${hote(vid.source)}"
                out("    extraction « ${srv.name.take(26)} » : OK, $mode")
                if (vid.source.isNotBlank()) bon = true
            }
            v.onFailure { out("    extraction « ${srv.name.take(26)} » : KO ${err(it)}") }
            if (bon) break
        }
        if (!bon) out("  ALERTE aucun des ${minOf(3, l.size)} premiers serveurs ($quoi) ne s'extrait")
    }
}
