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
        // 2026-10-27 (user, Canal+ Sport 4 « ne marche pas ») : ChaineDirect sert la playlist par son
        //   relais `/player/source2/?vp=<chiffré>&vc=…` (Content-Type HLS, sans « .m3u8 » dans
        //   l'adresse) — vérifié dans Chrome : la chaîne joue, et le relais répond sans cookie ni
        //   Referer. La capture ne cherchait que « .m3u8 » → rien en 20 s.
        val relaisChaineDirect = provider == ChaineDirect.ID
        // 2026-10-28 (user : « Ce site copie le catalogue de WiTV.eu ») : WiTV détecte la WebView
        //   et lui sert un lecteur leurre → lecture DIRECTE d'abord (cf. ChaineDirect.fluxDirect).
        var entree = url
        var refererEntree = "$origine/"
        var cadreExterne = false
        if (relaisChaineDirect) {
            val r = ChaineDirect.fluxDirect(url)
            // 2026-10-28 (user : DAZN 1 France « fonctionnait tout à l'heure ») : lecteur externe thedaddy
            //   (`stream-<n>.php`) = même numéro de chaîne que DAMITV (cf. DaddyLive.serveurs) → lu en
            //   natif par DamiTvChaines, sans WebView.
            val numeroDaddy = r?.takeIf { it.startsWith(ChaineDirect.PREFIXE_CADRE) }
                ?.let { Regex("(?:thedaddy|dlhd)\\.[a-z]+/stream/stream-(\\d+)\\.php", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            if (numeroDaddy != null) {
                val srv = DamiTvChaines.serveur("${DamiTvChaines.PREFIX}$numeroDaddy", server.name, "WiTV")
                val v = runCatching { DamiTvChaines.video(srv) }.onFailure { Log.w(TAG, "[$provider] thedaddy $numeroDaddy : ${it.message}") }.getOrNull()
                if (v != null) {
                    Log.i(TAG, "[$provider] ${server.name} → thedaddy $numeroDaddy via DAMITV (natif)")
                    return v
                }
            }
            if (r == null || r.startsWith(ChaineDirect.PREFIXE_CADRE)) {
                // Lecteur externe (thedaddy…) ou lecture directe impossible : la page witv.eu dans la
                //   WebView, comme avant (user : « elle fonctionnait tout à l'heure »), mais la capture
                //   IGNORE le leurre (relais source2 sans jeton `tk`) et attend le vrai flux.
                cadreExterne = true
            } else {
                Log.i(TAG, "[$provider] ${server.name} → flux WiTV direct (sans WebView)")
                return Video(source = r, headers = ChaineDirect.ENTETES_FLUX, type = "application/vnd.apple.mpegurl")
            }
        }
        val capte = com.streamflixreborn.streamflix.utils.WebViewStreamResolver.resolve(
            entryUrl = entree,
            referer = refererEntree,
            timeoutMs = 20_000L,
            clickPlay = true,
            attach = true,
            // Relais WiTV : seulement l'adresse AVEC jeton `tk` (le leurre n'en a pas) ; les .m3u8
            //   (thedaddy…) restent captés par défaut.
            motifsUrlFlux = if (relaisChaineDirect) listOf(Regex("/player/source2/\\?(?=[^#]*[?&]tk=)")) else emptyList(),
        ) ?: run {
            Log.w(TAG, "[$provider] ${server.name} : aucun flux capté")
            return null
        }
        // 2026-10-28 (user : « pourquoi on a toujours le logo DAZN qui renvoie vers WiTV ») : dans la
        //   WebView, WiTV sert son LEURRE (playlist sans jeton `tk`, écran « Ce site copie le catalogue
        //   de WiTV.eu »). Plutôt échouer (serveur en rouge, le lecteur passe au suivant) que montrer ça.
        if (relaisChaineDirect && capte.url.contains("/player/source2/") && !Regex("[?&]tk=").containsMatchIn(capte.url)) {
            Log.w(TAG, "[$provider] ${server.name} : leurre WiTV capté (sans jeton) → écarté")
            return null
        }
        Log.i(TAG, "[$provider] ${server.name} → flux capté (${capte.url.substringBefore('?').takeLast(60)})")
        return Video(
            source = capte.url,
            headers = capte.headers,
            type = if (capte.url.contains(".m3u8", ignoreCase = true) ||
                (relaisChaineDirect && capte.url.contains("/player/source2/"))) "application/vnd.apple.mpegurl" else null,
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
