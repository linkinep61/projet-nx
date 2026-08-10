package com.streamflixreborn.streamflix.providers

import android.content.Context
import com.streamflixreborn.streamflix.models.TvShow

/**
 * RutubeFolder — la logique du DOSSIER Rutube du TV Hub (2026-08-13).
 *
 * Le dossier réutilise le dialog des AUTRES dossiers (`LiveHubFolderDialog.showPosterGrid`) :
 * grille de jaquettes, mini-lecteur qui reste ouvert au clic, ★ appui long, bouton RETOUR.
 * Seule différence : sa barre de recherche interroge RUTUBE (réseau) au lieu de filtrer une
 * liste locale — on y trouve n'importe quoi (« Inspecteur Colombo »), comme sur le site.
 *
 * Ici : l'HISTORIQUE des recherches (persistant) + la conversion clip → TvShow jouable.
 * Les clips portent l'id `livehub::rutube::<hex>` : le TV Hub les résout en serveur Rutube
 * (getServers → RutubeProvider), et l'appui long les met en favori via ReplayFavoritesStore
 * (= ils remontent dans « Favoris Replay » du TV Hub, donc la playlist du dossier).
 */
object RutubeFolder {

    private const val PREFS = "rutube_folder"
    private const val KEY_HISTORY = "search_history"
    private const val MAX_HISTORY = 12

    /** Recherches précédentes, la plus récente d'abord. */
    fun history(ctx: Context): List<String> = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "")
            .orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    private fun remember(ctx: Context, query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        try {
            val list = (listOf(q) + history(ctx).filterNot { it.equals(q, ignoreCase = true) })
                .take(MAX_HISTORY)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HISTORY, list.joinToString("\n"))
                .apply()
        } catch (_: Exception) {}
    }

    fun clearHistory(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_HISTORY).apply()
        } catch (_: Exception) {}
    }

    /** Derniers résultats affichés, gardés EN MÉMOIRE le temps qu'on reste dans le TV Hub.
     *  Vidés en quittant le provider (cf. `oublierResultats`). */
    @Volatile private var derniersResultats: List<TvShow> = emptyList()

    /** Derniers résultats, en lecture seule — file de secours quand un clip est lancé
     *  d'ailleurs que la grille du dossier (favoris ❤, reprise, plein écran). */
    val resultatsCourants: List<TvShow> get() = derniersResultats

    /** Appelé quand l'utilisateur quitte le provider : on repart propre à la prochaine entrée. */
    fun oublierResultats() {
        if (derniersResultats.isNotEmpty()) {
            derniersResultats = emptyList()
            android.util.Log.d("RutubeFolder", "provider quitté → résultats oubliés")
        }
    }

    /** Dernière panne de la source YouTube (affichée dans le dossier au lieu de crasher). */
    @Volatile var derniereErreurYoutube: String? = null
        private set

    // ── FILE DE LECTURE : la liste RÉELLEMENT AFFICHÉE (2026-08-14) ─────────────────
    //   user : « faut que ça fasse la différence entre la file avec les favoris et la file
    //   quand on vient de faire une recherche et qu'on a trouvé 150 musiques : faut que ça
    //   lise les 150, et pareil en aléatoire ». La file suit donc ce qui est à l'écran au
    //   moment du lancement. Publiée par la grille du dossier ET par la page ❤ de l'accueil.
    @Volatile private var listeAffichee: List<TvShow> = emptyList()

    val listeAfficheeCourante: List<TvShow> get() = listeAffichee

    fun publierListeAffichee(items: List<TvShow>) {
        val clips = items.filter { estClipDossier(it.id) }
        if (clips.isNotEmpty()) {
            listeAffichee = clips
            android.util.Log.d("RutubeFolder", "liste affichée → ${clips.size} clips (file de lecture)")
        }
    }

    /** Un clip DU DOSSIER (Rutube ou YouTube) — sert aux favoris et aux filtres d'affichage. */
    fun estClipDossier(id: String): Boolean =
        id.startsWith("livehub::rutube::") || id.startsWith("livehub::ytclip::")

    private fun clipToTvShow(c: RutubeProvider.RutubeClip): TvShow =
        TvShow(id = "livehub::rutube::${c.id}", title = c.title)
            .copy(poster = c.thumbnail.takeIf { it.isNotBlank() })
            .apply { providerName = "TV Hub" }

    private fun ytToTvShow(v: NewPipeAudio.VideoResult): TvShow =
        TvShow(id = "livehub::ytclip::${v.videoId}", title = v.title)
            .copy(poster = v.thumbnail.takeIf { it.isNotBlank() })
            .apply { providerName = "TV Hub" }

    // ── SOURCE DU DOSSIER (2026-08-13) ────────────────────────────────────────────────
    //   user : « fusionner les 2 pour avoir plus de contenu d'un coup » PUIS « ou encore
    //   mieux, le choix de changer avec un petit icône ». On fait les deux : LES_DEUX par
    //   défaut (contenu maximal d'un coup), et un bouton du dossier fait tourner la source.
    //   YouTube passe par NewPipe → flux direct, donc SANS les publicités du lecteur YouTube.
    enum class Source(val libelle: String, val icone: String) {
        LES_DEUX("Rutube + YouTube", "🌐"),
        RUTUBE("Rutube", "🔴"),
        YOUTUBE("YouTube", "▶"),
    }

    // 2026-08-14 (user : « comme il y a des problèmes avec YouTube, tu mets en priorité
    //   Rutube — comme ça tout le monde a quelque chose de fonctionnel au lancement ») :
    //   la source par DÉFAUT passe de « les deux » à Rutube seul. Rutube marche sur tous
    //   les appareils testés ; YouTube tombe sur certains (Bbox 4K, un Samsung, un vieil
    //   Oppo). Quelqu'un qui n'a jamais touché au bouton a donc un dossier qui fonctionne.
    //   Ce n'est qu'un DÉFAUT : le bouton de source fait toujours le tour des trois choix,
    //   et un réglage déjà enregistré par l'utilisateur n'est pas écrasé.

    private const val KEY_SOURCE = "source"

    fun source(ctx: Context): Source = try {
        Source.valueOf(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SOURCE, Source.RUTUBE.name) ?: Source.RUTUBE.name,
        )
    } catch (_: Exception) { Source.LES_DEUX }

    /** Fait tourner la source (les deux → Rutube → YouTube → …) et renvoie la nouvelle. */
    fun sourceSuivante(ctx: Context): Source {
        val suivante = when (source(ctx)) {
            Source.LES_DEUX -> Source.RUTUBE
            Source.RUTUBE -> Source.YOUTUBE
            Source.YOUTUBE -> Source.LES_DEUX
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SOURCE, suivante.name).apply()
        } catch (_: Exception) {}
        return suivante
    }

    /** Recherche RÉSEAU (appelée par la barre du dossier) + mémorise la requête.
     *  MULTI-PAGES côté Rutube (une seule page plafonnait à ~97, repéré par le user), et
     *  fusion avec YouTube quand la source le demande — les deux listes sont ENTRELACÉES
     *  pour qu'aucune ne soit reléguée en fin de grille. */
    suspend fun search(ctx: Context, query: String): List<TvShow> {
        remember(ctx, query)
        derniereErreurYoutube = null
        val src = source(ctx)
        // 2026-08-14 (user : « sur un Samsung, activer l'option YouTube fait crasher
        //   l'appli ; Rutube seul marche, et sur Honor/autres appareils tout va bien ») :
        //   on attrapait `Exception`, ce qui laisse passer les `Error` (NoSuchMethodError,
        //   NoClassDefFoundError, OutOfMemoryError…) — typiquement ce qui arrive quand une
        //   ROM/version d'Android n'offre pas ce qu'attend l'extracteur. Résultat : crash.
        //   On attrape donc `Throwable` : une source qui tombe rend une liste vide et
        //   l'autre source continue de s'afficher, l'appli ne meurt plus jamais ici.
        val rutube = if (src != Source.YOUTUBE) {
            try { RutubeProvider.searchClipsPaged(query).map(::clipToTvShow) }
            catch (t: Throwable) {
                android.util.Log.w("RutubeFolder", "recherche Rutube KO: ${t.javaClass.simpleName} ${t.message}")
                emptyList()
            }
        } else emptyList()
        val youtube = if (src != Source.RUTUBE) {
            try {
                val res = NewPipeAudio.searchVideos(query).map(::ytToTvShow)
                // Le secours a pu sauver la mise : on garde quand même la trace de la panne
                //   de l'extracteur pour la fiche de diagnostic (appui long sur la source).
                res
            } catch (t: Throwable) {
                android.util.Log.w("RutubeFolder", "recherche YouTube KO: ${t.javaClass.simpleName} ${t.message}")
                derniereErreurYoutube = "${t.javaClass.simpleName}: ${t.message}"
                emptyList()
            }
        } else emptyList()
        if (rutube.isEmpty()) return youtube.also { derniersResultats = it }
        if (youtube.isEmpty()) return rutube.also { derniersResultats = it }
        val fusion = ArrayList<TvShow>(rutube.size + youtube.size)
        var i = 0
        while (i < maxOf(rutube.size, youtube.size)) {
            rutube.getOrNull(i)?.let(fusion::add)
            youtube.getOrNull(i)?.let(fusion::add)
            i++
        }
        return fusion.distinctBy { it.id }.also { derniersResultats = it }
    }

    /**
     * Les clips Rutube mis en ★ — le « dossier à part À L'INTÉRIEUR » du dossier (bouton ★).
     * Ils vivent dans ReplayFavoritesStore (comme les autres favoris du hub) ; on ne garde ici
     * que ceux du dossier Rutube, pour qu'ils forment sa playlist et rien d'autre.
     */
    fun favorites(): List<TvShow> = try {
        com.streamflixreborn.streamflix.utils.ReplayFavoritesStore.all()
            .filter { estClipDossier(it.id) }
            .map { e ->
                TvShow(id = e.id, title = e.title)
                    .copy(poster = e.poster)
                    .apply { providerName = "TV Hub" }
            }
    } catch (_: Exception) { emptyList() }

    /**
     * Contenu affiché à l'OUVERTURE du dossier : les favoris Rutube déjà enregistrés (la
     * « playlist » du dossier), complétés par la dernière recherche de l'historique. Aucun
     * écran vide, et on retrouve ses clips sans retaper quoi que ce soit.
     */
    suspend fun initialItems(ctx: Context): List<TvShow> {
        // 2026-08-13 (user : « mes favoris s'affichent en même temps que la dernière recherche
        //   quand on rouvre Rutube ; ils doivent apparaître seulement quand on clique sur
        //   l'étoile ») : à l'ouverture on ne montre QUE la dernière recherche. Les ★ restent
        //   accessibles d'un clic sur le bouton étoile, qui remplace la grille par eux seuls.
        val favoris = emptyList<TvShow>()

        // 2026-08-13 (user : « ça devrait effacer À LA FERMETURE du TV Hub, pas avant ») :
        //   tant qu'on reste dans le TV Hub, rouvrir le dossier redonne les derniers
        //   résultats INSTANTANÉMENT — ils sont gardés en mémoire, aucune requête réseau
        //   n'est rejouée (c'était ça, la lenteur d'ouverture).
        //   Quand on QUITTE le provider, `oublierResultats()` les efface (appelé depuis
        //   ProviderChangeNotifier) → la prochaine entrée repart propre et immédiate.
        val recents = derniersResultats

        return (favoris + recents).distinctBy { it.id }
    }
}
