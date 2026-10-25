package com.streamflixreborn.streamflix.sport.providers

import android.util.Log
import kotlinx.coroutines.async
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.sport.SportEvent
import java.text.Normalizer

/**
 * Registre des sources web du module sport (brief § 9, « index.ts »).
 *
 * 2026-10-25 — après tests sur l'Oppo et la TCL, seule damitv est gardée (user : « on vire
 * celles qui posent problème », « la nouvelle source, tu la mets dans un petit dossier à part ») :
 *   • damitv : flux capté en arrière-plan et lu par le lecteur ONYX (vérifié : Athletic Bilbao –
 *     Getafe en direct). Matchs surtout commentés en anglais/espagnol ;
 *   • chainedirect (= witv.eu) : RETIRÉ — son lecteur charge en fait thedaddy (wideiptv.top/daddy.php) ;
 *   • thedaddy : RETIRÉ — flux trouvé mais segments déguisés, refusés par le lecteur
 *     (« Cannot find sync byte »).
 * Une source en panne n'empêche jamais le reste du dossier : l'appel est isolé et l'erreur
 * journalisée avec le nom de la source.
 *
 * Serveurs : id `sportweb::<source>::…`, lus par [video] (flux capté, lu par le lecteur ONYX).
 */
object SportProviders {

    private const val TAG = "SportProviders"
    const val PREFIXE_SERVEUR = "sportweb::"

    private suspend fun <T> isole(source: String, bloc: suspend () -> List<T>): List<T> =
        try { bloc() } catch (e: Exception) {
            Log.w(TAG, "[$source] en panne : ${e.message}")
            emptyList()
        }

    /** Événements normalisés, en direct d'abord puis par heure de début. */
    suspend fun evenements(): List<SportEvent> = kotlinx.coroutines.coroutineScope {
        val dami = async { isole(DamiTv.ID) { DamiTv.getStreams() } }
        // 2026-10-25 (liste du user, priorité 1) : streamed.pk.
        val streamed = async { isole(StreamedPk.ID) { StreamedPk.getStreams() } }
        // 2026-10-25 (liste du user, priorité 2) : planning thedaddy / DaddyLive.
        val daddy = async { isole(DaddyLive.ID) { DaddyLive.getStreams() } }
        dami.await() + streamed.await() + daddy.await()
    }.sortedWith(
            compareBy<SportEvent> { if (it.enDirect) 0 else 1 }.thenBy { it.startsAt ?: Long.MAX_VALUE }
        )

    // ───────────────────────────────────────────────────────────── lecture

    /** Dernière liste connue d'une source (pour retrouver un événement à la lecture). */
    fun derniereListe(provider: String): List<SportEvent> = when (provider) {
        StreamedPk.ID -> StreamedPk.derniereListe()
        DaddyLive.ID -> DaddyLive.derniereListe()
        ChaineDirect.ID -> ChaineDirect.derniereListe()
        else -> DamiTv.derniereListe()
    }

    /** Serveurs d'un événement (toutes ses sources). */
    suspend fun serveursEvenement(e: SportEvent): List<Video.Server> =
        if (e.provider == StreamedPk.ID) StreamedPk.serveurs(e)
        else if (e.provider == DaddyLive.ID) DaddyLive.serveurs(e)
        else e.sources.mapIndexed { i, s ->
            Video.Server(
                id = "$PREFIXE_SERVEUR${e.provider}::ev::${e.id}::$i",
                name = s.name,
                src = s.embedUrl,
            )
        }

    fun estServeurWeb(server: Video.Server) = server.id.startsWith(PREFIXE_SERVEUR)

    /**
     * Vidéo d'un serveur web. Null si la page est introuvable (embed damitv 404 sur les deux
     * hôtes) ou si aucun flux n'a pu être capté.
     */
    suspend fun video(server: Video.Server): Video? {
        val provider = server.id.removePrefix(PREFIXE_SERVEUR).substringBefore("::")
        val url = (if (provider == DamiTv.ID) DamiTv.embedJouable(server.src) else server.src) ?: run {
            Log.w(TAG, "[$provider] page introuvable pour ${server.name}")
            return null
        }
        // 2026-10-25 (user, sur la TCL : « tu m'as pas fait l'arrivée des sources comme d'habitude,
        //   tu as fait une webview » — pointeur et pubs à l'écran) : la page n'est plus MONTRÉE.
        //   Elle est chargée dans une WebView cachée (pubs bloquées, clic « play » simulé) le temps
        //   de capter le flux, puis c'est le lecteur ONYX qui le lit, comme les autres sources.
        //   Pas de flux capté → serveur en échec (le lecteur passe au suivant), jamais de page.
        val origine = Regex("^https?://[^/]+").find(url)?.value ?: return null
        val capte = com.streamflixreborn.streamflix.utils.WebViewStreamResolver.resolve(
            entryUrl = url,
            referer = "$origine/",
            timeoutMs = 20_000L,
            clickPlay = true,
            attach = true,
        ) ?: run {
            Log.w(TAG, "[$provider] ${server.name} : aucun flux capté")
            return null
        }
        Log.i(TAG, "[$provider] ${server.name} → flux capté (${capte.url.substringBefore('?').takeLast(60)})")
        return Video(
            source = capte.url,
            headers = capte.headers,
            type = if (capte.url.contains(".m3u8", ignoreCase = true)) "application/vnd.apple.mpegurl" else null,
        )
    }

    // ───────────────────────────────────────────────────────────── dédoublonnage

    /** Mots qui varient d'une source à l'autre (« Angers SCO » = « Angers », « AJ Auxerre » =
     *  « Auxerre », « Olympique Marseille » = « Marseille »). */
    private val MOTS_VIDES = setOf(
        "fc", "sc", "ac", "cf", "as", "afc", "club", "de", "the", "cd", "ud", "sv", "fk",
        "sco", "aj", "ogc", "losc", "rc", "us", "ssc", "olympique", "calcio", "and",
    )

    /** « Inter Milan vs Fiorentina » = « Fiorentina - Inter Milan » ; null si pas deux équipes. */
    // Compilées une fois : cleEquipes est appelée pour chacune des ~2 400 tuiles du dossier.
    private val SEPARATEUR_EQUIPES = Regex("\\s+(?:vs\\.?|v|-|–|@)\\s+", RegexOption.IGNORE_CASE)
    private val ACCENTS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** 2026-10-25 : streamed écrit « Paris Saint-Germain vs. Lyon », DaddyLive « PSG vs Olympique
     *  Lyonnais » — même match, une seule tuile. Noms après nettoyage (sans mots vides). */
    private val ALIAS_EQUIPES = mapOf(
        "psg" to "parissaintgermain", "parissg" to "parissaintgermain",
        "lyonnais" to "lyon", "ol" to "lyon", "om" to "marseille",
        "barca" to "barcelona", "barcelone" to "barcelona", "fcbarcelona" to "barcelona",
        "bayernmunchen" to "bayernmunich", "bayern" to "bayernmunich",
        "intermilan" to "inter", "internazionale" to "inter",
        "manutd" to "manchesterunited", "manunited" to "manchesterunited", "mancity" to "manchestercity",
        "realmadridcf" to "realmadrid", "atleticomadrid" to "atletico", "atleticodemadrid" to "atletico",
    )

    fun cleEquipes(titre: String): String? {
        val tete = titre.substringBefore(" · ").substringBefore(" | ")
        val parts = tete.split(SEPARATEUR_EQUIPES)
        if (parts.size != 2) return null
        val eq = parts.map { p ->
            Normalizer.normalize(p, Normalizer.Form.NFD).replace(ACCENTS, "").lowercase()
                .split(NON_ALNUM).filter { it.isNotBlank() && it !in MOTS_VIDES }.joinToString("")
                .let { ALIAS_EQUIPES[it] ?: it }
        }
        if (eq.any { it.length < 3 }) return null
        return eq.sorted().joinToString("_")
    }
}
