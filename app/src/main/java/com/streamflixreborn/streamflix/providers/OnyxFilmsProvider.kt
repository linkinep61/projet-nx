package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.OlaVod
import com.streamflixreborn.streamflix.utils.VegetaVod
import com.streamflixreborn.streamflix.utils.VidaraCommunaute
import com.streamflixreborn.streamflix.utils.VidaraLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 2026-10-26 (user : « faire un profil ou un nouveau provider avec tout ce genre de films pour
 *   pas qu'ils soient perdus et pour éviter l'ouverture du TV Hub à chaque fois » — choix
 *   « un ONYX maison ») : catalogue films / séries de l'appli, côté Films / Séries.
 *
 * Regroupe, sous un seul nom et sans dire d'où ça vient :
 *   · la bibliothèque Vidara perso (index publié, rangée = dossier Vidara) ;
 *   · le Partage de la communauté (Vidara des amis) ;
 *   · Ciné Films : Vegeta VOD + films / séries des portails OLA.
 *
 * Un même film présent dans plusieurs sources n'a qu'UNE tuile (clé = identifiant TMDB, sinon
 * titre normalisé) ; toutes ses sources deviennent des serveurs « ONYX 1, ONYX 2… ».
 * En version debug seulement, l'origine est indiquée (titre et serveurs) pour s'y retrouver.
 *
 * Lecture : on délègue à LiveTvHubProvider, qui sait déjà lire toutes ces sources. Les
 * identifiants sont préfixés « onyx:: » pour que le lecteur les traite en VOD (reprise,
 * historique) et non comme des chaînes en direct (« livehub:: »).
 */
object OnyxFilmsProvider : Provider, ProgressiveServersProvider {

    /** 2026-10-26 (user : séries + replays TF1+/M6+… en serveurs de secours) : chemin progressif
     *  du lecteur → ses serveurs d'abord, puis les backups du registre (replays TV Hub, Movix,
     *  Cloudstream… par TMDB ou titre) s'ajoutent au fil de l'eau, comme sur les autres providers. */
    override fun getServersProgressive(id: String, videoType: Video.Type): kotlinx.coroutines.flow.Flow<List<Video.Server>> =
        kotlinx.coroutines.flow.flow {
            val l = getServers(id, videoType)
            if (l.isNotEmpty()) emit(l)
        }

    private const val TAG = "OnyxFilms"

    /** 2026-10-26 (user) : ONYX Films REMPLACE dans le TV Hub les dossiers Ciné Films,
     *  Partage de la communauté et le sous-dossier « Films » de « Film / série ».
     *  false = les remettre tels qu'avant. */
    const val REMPLACE_TV_HUB = true
    /** Mesure du tas Java (GC forcés, ~0,5 s) : seulement pour un diagnostic ponctuel. */
    private const val MESURE_MEMOIRE = false
    const val PREFIX = "onyx::"
    private const val P_FILM = "onyx::f::"
    private const val P_SERIE = "onyx::s::"
    private const val TTL_MS = 30 * 60 * 1000L
    private const val PAR_RANGEE = 40
    private const val PAR_PAGE = 30
    private const val MAX_RANGEES = 30

    override val baseUrl = "https://onyx.films/"
    override val name = "ONYX Films"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/logo_onyxfilms"
    override val language = "fr"

    private enum class Origine(val libelle: String, val rang: Int) {
        PERSO("Vidara perso", 0), COMMU("Communauté", 1), VEGETA("Vegeta", 2), OLA("OLA", 3), OTF("OTF", 4)
    }
    private data class Source(val id: String, val origine: Origine)
    private class Entree(val id: String, var titre: String, var poster: String?, val serie: Boolean) {
        val sources = mutableListOf<Source>()
        /** Titre normalisé, calculé une seule fois (recherche). */
        val cleRecherche: String by lazy { norm(titre) }
    }
    private class Catalogue(
        val entrees: Map<String, Entree>,
        val rangees: List<Pair<String, List<String>>>,
    )

    @Volatile private var cache: Catalogue? = null
    @Volatile private var cacheTs = 0L
    private val verrou = Mutex()

    // Regex compilées une fois : norm() est appelée ~80 000 fois à la construction.
    private val RE_ACCENTS = Regex("\\p{Mn}+")
    private val RE_ANNEE = Regex("\\(\\d{4}\\)")
    private val RE_NON_ALNUM = Regex("[^a-z0-9]+")
    /** Préfixe de langue des catalogues IPTV : « FR ▎ », « FR| », « EN - »… */
    private val RE_PREFIXE_LANGUE = Regex("^[A-Z]{2,3}\\s*[▎|│:\\-★☆•]+\\s*")

    /** Titre réduit à [a-z0-9], sans accents ni « (2024) ». 2026-10-26 (user : « le dossier est
     *  long à ouvrir ») : appelée ~100 000 fois → chemin rapide en un seul passage pour le cas
     *  courant (ASCII) ; Normalizer + regex seulement quand il y a des accents. */
    private fun norm(s: String): String {
        var ascii = true
        for (ch in s) if (ch.code > 127) { ascii = false; break }
        if (!ascii) return java.text.Normalizer
            .normalize(s, java.text.Normalizer.Form.NFD)
            .replace(RE_ACCENTS, "").lowercase()
            .replace(RE_ANNEE, "")
            .replace(RE_NON_ALNUM, "")
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // « (2024) » : sauté d'un bloc
            if (c == '(' && i + 5 < s.length && s[i + 5] == ')' &&
                s[i + 1].isDigit() && s[i + 2].isDigit() && s[i + 3].isDigit() && s[i + 4].isDigit()) { i += 6; continue }
            when (c) {
                in 'a'..'z', in '0'..'9' -> sb.append(c)
                in 'A'..'Z' -> sb.append(c + 32)
            }
            i++
        }
        return sb.toString()
    }

    /** Retire « FR ▎ » & co. Regex seulement si le titre commence par 2 majuscules (rare). */
    private fun propre(titre: String): String {
        var t = titre
        if (t.length > 3 && t[0] in 'A'..'Z' && t[1] in 'A'..'Z') t = t.replace(RE_PREFIXE_LANGUE, "")
        // 2026-10-26 (vu sur la télé : « OpÃ©ration Condor », « Cap.Farewell.2026.French… ») :
        //   accents mal décodés et noms de fichiers bruts venus des catalogues IPTV.
        if (t.indexOf('Ã') >= 0 || t.indexOf('Â') >= 0) t = reparerAccents(t)
        if (t.indexOf('.') >= 0 || t.indexOf('_') >= 0) t = depuisNomDeFichier(t)
        // « Opération Condor {edition longue} » : balise d'édition entre accolades.
        if (t.indexOf('{') >= 0) t = t.replace(RE_ACCOLADES, " ").replace(RE_ESPACES, " ")
        return t.trim()
    }

    /** « OpÃ©ration » (UTF-8 lu en Latin-1) → « Opération ». Laissé tel quel si douteux. */
    private fun reparerAccents(s: String): String {
        if (s.any { it.code > 255 }) return s
        val r = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        return if (r.indexOf('�') >= 0) s else r
    }

    private val RE_ACCOLADES = Regex("\\{[^}]*\\}?")
    private val RE_ESPACES = Regex("\\s{2,}")
    private val RE_ANNEE_FIN = Regex("\\s*\\((\\d{4})\\)\\s*$")
    private val RE_BALISE = Regex("(?i)multi|french|truefrench|vff|vfq|vfi|vf2|vf|vostfr|subfrench|" +
        "1080p|720p|2160p|480p|4k|uhd|hdr|webrip|web|webdl|bluray|bdrip|brrip|dvdrip|hdtv|hdrip|" +
        "x264|x265|h264|h265|hevc|aac|ac3|dts|hdlight|mkv|mp4|avi|repack|proper")

    /** « Cap.Farewell.2026.French.1080p » → « Cap Farewell (2026) ». Seulement pour un titre
     *  SANS espace (hors année finale) fait de mots séparés par des points. */
    private fun depuisNomDeFichier(t0: String): String {
        var annee = RE_ANNEE_FIN.find(t0)?.groupValues?.get(1)
        val base = RE_ANNEE_FIN.replace(t0, "")
        if (base.indexOf(' ') >= 0) return t0
        val mots = base.split('.', '_').filter { it.isNotBlank() }
        if (mots.size < 2 || mots.all { it.length <= 2 }) return t0   // « S.W.A.T. »
        val garde = ArrayList<String>(mots.size)
        for ((i, m) in mots.withIndex()) {
            if (i > 0 && m.length == 4 && m.all { it.isDigit() } && m.toInt() in 1900..2099) {
                if (annee == null) annee = m
                break
            }
            if (i > 0 && RE_BALISE.matches(m)) break
            garde += m
        }
        if (garde.isEmpty()) return t0
        val titre = garde.joinToString(" ")
        return if (annee != null) "$titre ($annee)" else titre
    }

    /** « Titre (2025) » sans doubler l'année quand le titre la porte déjà. */
    private fun avecAnnee(titre: String, annee: Int): String =
        if (annee <= 0 || titre.contains("($annee)")) titre else "$titre ($annee)"

    private val jolis = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** « ACTION » → « Action » (mémorisé : ~80 catégories pour ~80 000 appels). */
    private fun joli(s: String): String =
        jolis.getOrPut(s) { s.trim().lowercase().replaceFirstChar { it.titlecase() } }

    /** Nom de rangée pour un dossier Vidara : dernier segment ; les clips sont réunis. */
    private fun rangeeVidara(dossier: String): String {
        val n = norm(dossier)
        if (n.contains("clip") || n.contains("musique")) return "Clips musique"
        return dossier.split("/").map { it.trim() }.lastOrNull { it.isNotBlank() } ?: "Divers"
    }

    private suspend fun catalogue(forcer: Boolean = false): Catalogue = withContext(Dispatchers.IO) {
        cache?.takeIf { !forcer && System.currentTimeMillis() - cacheTs < TTL_MS }?.let { return@withContext it }
        verrou.withLock {
            cache?.takeIf { !forcer && System.currentTimeMillis() - cacheTs < TTL_MS }?.let { return@withLock it }
            val t0 = System.currentTimeMillis()
            fun tasMo(): Long { System.gc(); Thread.sleep(150); System.gc()
                return Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1_048_576 } }
            val avant = if (MESURE_MEMOIRE) tasMo() else 0L
            val s = chargerSources()
            val tIndex = System.currentTimeMillis() - t0
            val sources = if (MESURE_MEMOIRE) tasMo() else 0L
            val tC = System.currentTimeMillis()
            val c = construire(s)
            Log.i(TAG, "catalogue : ${c.entrees.size} titres, ${c.rangees.size} rangées en " +
                "${System.currentTimeMillis() - t0} ms — index $tIndex ms, construction " +
                "${System.currentTimeMillis() - tC} ms (perso ${s.perso.size}, commu ${s.commu.size}, " +
                "vegeta ${s.vegetaOk}, ola ${s.olaOk})")
            if (c.entrees.isNotEmpty()) { cache = c; cacheTs = System.currentTimeMillis(); surveillerMemoire() }
            if (MESURE_MEMOIRE) Log.i(TAG, "mémoire (Mo, tas Java) : avant $avant, index chargés $sources, " +
                "catalogue construit ${tasMo()} (max ${Runtime.getRuntime().maxMemory() / 1_048_576})")
            c
        }
    }

    private class Sources(
        val perso: List<VidaraLibrary.Fichier>,
        val commu: List<VidaraCommunaute.Fichier>,
        val vegetaOk: Boolean,
        val olaOk: Boolean,
        val otf: List<com.streamflixreborn.streamflix.utils.OtfTvService.OtfMovie>,
        val persoPrep: List<Prep>,
        val commuPrep: List<Prep>,
    )

    /** Fichier perso / communauté déjà prêt (titre, clé, affiche, rangée). */
    private class Prep(val f: VidaraLibrary.Fichier, val titre: String, val cle: String, val poster: String?, val rangee: String)

    /** Clé d'un film : « t » + TMDB film, sinon « n » + titre normalisé. */
    private fun cleTitre(tmdb: String?, titre: String): String =
        if (!tmdb.isNullOrBlank() && !tmdb.startsWith("tv")) "t$tmdb" else "n" + norm(propre(titre))

    /** 2026-10-26 (télé : 2,8 s) : préparé PENDANT le téléchargement des gros index. Dossier
     *  évalué une seule fois ; clips et dossier « Série » (restés dans le TV Hub) exclus. */
    private fun preparerPerso(perso: List<VidaraLibrary.Fichier>): List<Prep> {
        val rangeeDe = HashMap<String, String>()   // dossier → rangée ("" = exclu)
        return perso.mapNotNull { f ->
            if (f.estClip) return@mapNotNull null
            val r = rangeeDe.getOrPut(f.dossier) {
                val rg = rangeeVidara(f.dossier)
                if (rg == "Clips musique" || norm(f.dossier).startsWith("serie")) "" else rg
            }
            if (r.isEmpty()) return@mapNotNull null
            // propre() : un fichier déposé sous son nom brut (« Cap.Farewell.2026.French… »).
            val titre = propre(VidaraLibrary.titrePour(f))
            Prep(f, titre, cleTitre(runCatching { VidaraLibrary.idTmdbDe(f) }.getOrNull(), titre),
                VidaraLibrary.posterPour(f), r)
        }
            // Du plus récent au plus ancien (date de dépôt, puis numéro interne) : c'est l'ordre
            //   « Derniers ajouts » des onglets Films / Séries.
            .sortedWith(compareByDescending<Prep> { it.f.ajout }.thenByDescending { it.f.vid })
    }

    private fun preparerCommu(commu: List<VidaraCommunaute.Fichier>): List<Prep> = commu.map { c ->
        val b = c.enBibliotheque()
        val titre = propre(VidaraLibrary.titrePour(b))
        Prep(b, titre, cleTitre(runCatching { VidaraLibrary.idTmdbDe(b) }.getOrNull(), titre),
            VidaraLibrary.posterPour(b), "Partage de la communauté")
    }

    @Volatile private var sourcesMemo: Sources? = null
    @Volatile private var sourcesMemoTs = 0L

    /** Les cinq index (mémorisés 30 min s'ils sont tous là : la préparation de la bibliothèque
     *  perso coûtait ~3 s sur la télé à CHAQUE appel). */
    private suspend fun chargerSources(): Sources {
        // Les index Ciné Films peuvent avoir été allégés entre-temps (box très faibles, cf.
        //   VegetaVod.alleger) : on ne réutilise la mémoire que s'ils sont toujours là.
        sourcesMemo?.takeIf {
            System.currentTimeMillis() - sourcesMemoTs < TTL_MS &&
                VegetaVod.indexSiCharge() != null && OlaVod.indexSiCharge() != null
        }?.let { return it }
        val s = chargerSourcesReseau()
        if (s.vegetaOk && s.olaOk && s.perso.isNotEmpty()) { sourcesMemo = s; sourcesMemoTs = System.currentTimeMillis() }
        return s
    }

    /** Les cinq index, en parallèle (chacun garde son propre cache : un 2ᵉ appel est immédiat). */
    private suspend fun chargerSourcesReseau(): Sources = coroutineScope {
        val perso = async { withTimeoutOrNull(25_000) { runCatching { VidaraLibrary.toutNav() }.getOrNull() }.orEmpty() }
        val commu = async {
            if (!VidaraCommunaute.disponible) emptyList()
            else withTimeoutOrNull(25_000) { runCatching { VidaraCommunaute.tout() }.getOrNull() }.orEmpty()
        }
        val vegeta = async { withTimeoutOrNull(30_000) { runCatching { VegetaVod.index() }.getOrNull() } }
        val ola = async { withTimeoutOrNull(30_000) { runCatching { OlaVod.index() }.getOrNull() } }
        // 2026-10-26 (user : « les films OTF devraient avoir leur place à cet endroit-là,
        //   avec une catégorie OTF, il n'y a que du français dedans »).
        val otf = async {
            withTimeoutOrNull(30_000) {
                runCatching { com.streamflixreborn.streamflix.utils.OtfTvService.fetchFilmsFrancais() }.getOrNull()
            }.orEmpty()
        }
        // Préparation perso / communauté dès que leurs (petits) index sont là, en parallèle
        //   des gros index Ciné Films encore en cours.
        val persoPrep = async(Dispatchers.Default) { preparerPerso(perso.await()) }
        val commuPrep = async(Dispatchers.Default) { preparerCommu(commu.await()) }
        Sources(perso.await(), commu.await(), vegeta.await() != null, ola.await() != null, otf.await(),
            persoPrep.await(), commuPrep.await())
    }

    /** Version accueil (40 titres par rangée), gardée pour retrouver les tuiles affichées. */
    @Volatile private var accueil: Catalogue? = null

    /** Entrée connue sans rien construire : catalogue complet s'il existe, sinon accueil. */
    private fun entree(id: String): Entree? = cache?.entrees?.get(id) ?: accueil?.entrees?.get(id)

    /** Regroupe par catégorie en un passage. [n] = Int.MAX_VALUE → tout, trié ; sinon seuls
     *  les [n] premiers selon [cmp] sont gardés (tas borné : pas de tri complet). Rend aussi
     *  le nombre réel de titres par catégorie (ordre des rangées). */
    private fun <T> grouper(
        items: List<T>, cats: (T) -> Collection<String>, cmp: Comparator<T>, n: Int,
    ): Pair<Map<String, List<T>>, Map<String, Int>> {
        val totaux = HashMap<String, Int>()
        if (n == Int.MAX_VALUE) {
            val g = HashMap<String, MutableList<T>>()
            for (x in items) for (c in cats(x)) {
                g.getOrPut(c) { ArrayList() }.add(x); totaux[c] = (totaux[c] ?: 0) + 1
            }
            return g.mapValues { it.value.sortedWith(cmp) } to totaux
        }
        val inverse = cmp.reversed()   // tête du tas = le moins bon des gardés
        val tas = HashMap<String, java.util.PriorityQueue<T>>()
        for (x in items) for (c in cats(x)) {
            totaux[c] = (totaux[c] ?: 0) + 1
            val q = tas.getOrPut(c) { java.util.PriorityQueue(n + 1, inverse) }
            if (q.size < n) q.add(x) else if (cmp.compare(x, q.peek()) < 0) { q.poll(); q.add(x) }
        }
        return tas.mapValues { it.value.sortedWith(cmp) } to totaux
    }

    /**
     * 2026-10-26 (télé TCL : un clic juste après l'ouverture attendait le catalogue complet,
     * 15-30 s) : sources d'un film retrouvées DIRECTEMENT dans chaque index, comme le font les
     * serveurs de secours (BackupRegistry) — sans construire les 80 000 titres.
     * Même règle de clé que construire() : « t » + TMDB, sinon « n » + titre normalisé.
     */
    private suspend fun sourcesDirectes(id: String): List<Source> = withContext(Dispatchers.IO) {
        val cle = id.removePrefix(P_FILM)
        val s = chargerSources()
        val out = ArrayList<Source>()
        if (cle.startsWith("t")) {
            val tmdb = cle.substring(1)
            val n = tmdb.toIntOrNull()
            for (f in s.perso) if (!f.estClip && runCatching { VidaraLibrary.idTmdbDe(f) }.getOrNull() == tmdb)
                out += Source("livehub::vidara::${f.code}", Origine.PERSO)
            for (f in s.commu) if (runCatching { VidaraLibrary.idTmdbDe(f.enBibliotheque()) }.getOrNull() == tmdb)
                out += Source("livehub::vidaracom::${f.code}", Origine.COMMU)
            if (n != null) {
                VegetaVod.indexSiCharge()?.films?.forEach { if (it.tmdb == n) out += Source(VegetaVod.PREFIX_FILM + it.id, Origine.VEGETA) }
                OlaVod.indexSiCharge()?.parTmdb?.get(n)?.forEach { out += Source(OlaVod.PREFIX_FILM + it.id, Origine.OLA) }
            }
        } else {
            val nt = cle.substring(1)
            fun pareil(titre: String) = norm(propre(titre)) == nt
            // Même règle que cleDe : un identifiant de série (« tv… ») ne compte pas comme TMDB film.
            fun sansTmdbFilm(t: String?) = t.isNullOrBlank() || t.startsWith("tv")
            for (f in s.perso) if (!f.estClip && sansTmdbFilm(VidaraLibrary.idTmdbDe(f)) && pareil(VidaraLibrary.titrePour(f)))
                out += Source("livehub::vidara::${f.code}", Origine.PERSO)
            for (f in s.commu) { val b = f.enBibliotheque()
                if (sansTmdbFilm(VidaraLibrary.idTmdbDe(b)) && pareil(VidaraLibrary.titrePour(b)))
                    out += Source("livehub::vidaracom::${f.code}", Origine.COMMU) }
            VegetaVod.indexSiCharge()?.films?.forEach { if (it.tmdb <= 0 && pareil(it.titre)) out += Source(VegetaVod.PREFIX_FILM + it.id, Origine.VEGETA) }
            OlaVod.indexSiCharge()?.films?.forEach { if (it.tmdb <= 0 && pareil(it.titre)) out += Source(OlaVod.PREFIX_FILM + it.id, Origine.OLA) }
            for (f in s.otf) if (norm(f.titreAffiche) == nt) out += Source("livehub::otffilm::${f.fichier}", Origine.OTF)
        }
        out.distinctBy { it.id }.sortedBy { it.origine.rang }
    }

    /** Titre + affiche d'un film inconnu des catalogues en mémoire (reprise, favori…). */
    private suspend fun infosDe(id: String): Pair<String, String?> {
        entree(id)?.let { return it.titre to it.poster }
        for (src in sourcesDirectes(id)) {
            val b = src.id
            when {
                b.startsWith("livehub::vidara::") -> VidaraLibrary.fichierNavDe(b.removePrefix("livehub::vidara::"))
                    ?.let { return VidaraLibrary.titrePour(it) to VidaraLibrary.posterPour(it) }
                b.startsWith(VegetaVod.PREFIX_FILM) -> VegetaVod.filmDe(b.removePrefix(VegetaVod.PREFIX_FILM))
                    ?.let { return propre(VegetaVod.titreFilm(it)) to it.img }
                b.startsWith(OlaVod.PREFIX_FILM) -> OlaVod.filmDe(b.removePrefix(OlaVod.PREFIX_FILM))
                    ?.let { return propre(OlaVod.titreFilm(it)) to it.img }
            }
        }
        // 2026-10-26 : plus de titre « ONYX Films » par défaut — la réparation des jaquettes
        //   cherchait alors « ONYX Films » sur TMDB. Film connu de TMDB → sa fiche ; sinon titre
        //   vide (l'appli garde alors le titre de la tuile).
        id.removePrefix(P_FILM).takeIf { it.startsWith("t") }?.drop(1)?.toIntOrNull()?.let { tid ->
            runCatching { com.streamflixreborn.streamflix.utils.TmdbUtils.getMovieById(tid, "fr-FR") }
                .getOrNull()?.let { return it.title to it.poster }
        }
        return "" to null
    }

    // 2026-10-26 (user : « vérifie que le chargement ne va pas faire exploser des box ») :
    //   mesuré sur l'Oppo, le catalogue pèse ~31 Mo de tas Java (80 000 titres), en plus des
    //   index Ciné Films / OTF (~48 Mo, déjà chargés par le TV Hub). Sur une box à petite
    //   mémoire, on le LIBÈRE dès qu'Android le demande (appli en arrière-plan ou mémoire
    //   basse) ; il se reconstruit à la prochaine ouverture.
    private val callbacksPoses = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun surveillerMemoire() {
        if (!callbacksPoses.compareAndSet(false, true)) return
        runCatching {
            com.streamflixreborn.streamflix.StreamFlixApp.instance.registerComponentCallbacks(
                object : android.content.ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        // 10 = RUNNING_LOW, 15 = RUNNING_CRITICAL (mémoire basse au premier plan),
                        //   ≥ 40 = BACKGROUND et au-delà (appli en arrière-plan, liste LRU).
                        //   PAS 20 (UI_HIDDEN, simple passage en arrière-plan) : un aller-retour
                        //   rapide ne doit pas coûter 20 s de rechargement.
                        if (level == 10 || level == 15 || level >= 40) liberer("trim $level")
                    }
                    override fun onLowMemory() = liberer("lowMemory")
                    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
                })
        }
    }
    private fun liberer(raison: String) {
        if (cache == null) return
        cache = null; cacheTs = 0L
        Log.i(TAG, "catalogue libéré ($raison)")
    }

    /**
     * [limite] : nombre de titres max par rangée. Int.MAX_VALUE = catalogue complet ; 40 =
     * version ACCUEIL (2026-10-26, user : « le dossier est long à ouvrir ») — seuls les titres
     * affichés sont construits, le reste est sauté avant tout calcul ; le catalogue complet se
     * termine ensuite en fond. Les rangées gardent leur taille réelle pour l'ordre d'affichage.
     */
    private fun construire(src: Sources, limite: Int = Int.MAX_VALUE): Catalogue {
        val vegetaOk = src.vegetaOk
        val olaOk = src.olaOk; val otf = src.otf
        val t0c = System.currentTimeMillis()
        val entrees = LinkedHashMap<String, Entree>()
        // clé de rangée normalisée -> (libellé, ids)
        val rangees = LinkedHashMap<String, Pair<String, LinkedHashSet<String>>>()
        val clesRangees = HashMap<String, String>()   // libellé → clé normalisée (calculée une fois)
        val totaux = HashMap<String, Int>()            // clé de rangée → nombre réel de titres
        fun cleRangee(rangee: String) = clesRangees.getOrPut(rangee) { norm(rangee).ifBlank { "divers" } }
        fun ranger(rangee: String, id: String) {
            val k = cleRangee(rangee)
            rangees.getOrPut(k) { rangee to LinkedHashSet() }.second += id
        }
        /** true = rangée déjà pleine (mode accueil) : on compte le titre et on le saute. */
        fun plein(rangee: String): Boolean {
            val k = cleRangee(rangee)
            totaux[k] = (totaux[k] ?: 0) + 1
            return limite != Int.MAX_VALUE && (rangees[k]?.second?.size ?: 0) >= limite
        }
        fun film(cle: String, titreBrut: String, poster: String?, src: Source, rangee: String): String? {
            val titre = propre(titreBrut)
            // Titre vide une fois le préfixe de langue retiré (« FR ▎ (2029) ») : entrée inutilisable.
            //   (cle = « n » + titre normalisé, ou « t » + identifiant TMDB : longueur ≥ 3 suffit.)
            if (cle.length < 3) return null
            val id = P_FILM + cle
            val e = entrees.getOrPut(id) { Entree(id, titre, poster, serie = false) }
            if (e.poster.isNullOrBlank() && !poster.isNullOrBlank()) e.poster = poster
            if (e.sources.none { it.id == src.id }) e.sources += src
            ranger(rangee, id)
            return id
        }
        fun cleDe(tmdb: String?, titre: String): String =
            if (!tmdb.isNullOrBlank() && !tmdb.startsWith("tv")) "t$tmdb" else "n" + norm(propre(titre))

        // 1. bibliothèque perso (dossiers Vidara = rangées) — titres/clés déjà préparés pendant
        //   le téléchargement des gros index (cf. preparerPerso), clips et « Série » exclus.
        val recents = mutableListOf<Pair<VidaraLibrary.Fichier, String>>()
        for (p in src.persoPrep) {
            plein(p.rangee)   // compte seulement : la bibliothèque perso est toujours construite en entier
            val id = film(p.cle, p.titre, p.poster, Source("livehub::vidara::${p.f.code}", Origine.PERSO), p.rangee)
            if (id != null) recents += p.f to id
        }
        // 2026-10-26 (user : « les dernières ajoutées apparaissent automatiquement ») : rangée
        //   « Derniers ajouts » = films perso du plus récent au plus ancien, quel que soit leur
        //   dossier (date de dépôt Vidara, puis numéro interne pour un même jour).
        if (recents.any { it.first.vid > 0 || it.first.ajout.isNotBlank() }) {
            val k = "derniersajouts"
            val ids = LinkedHashSet<String>()
            recents.sortedWith(compareByDescending<Pair<VidaraLibrary.Fichier, String>> { it.first.ajout }
                .thenByDescending { it.first.vid })
                .forEach { ids += it.second }
            rangees[k] = "Derniers ajouts" to ids
        }
        val t1 = System.currentTimeMillis()
        // 2. partage de la communauté
        for (p in src.commuPrep) {
            plein(p.rangee)
            film(p.cle, p.titre, p.poster, Source("livehub::vidaracom::${p.f.code}", Origine.COMMU), p.rangee)
        }
        val t2 = System.currentTimeMillis()
        // 3. Ciné Films — Vegeta
        // 2026-10-26 (user : « le dossier est long à ouvrir », mesuré 24 s sur la télé TCL) :
        //   regroupement par catégorie en UN passage ; en mode accueil, seuls les [limite]
        //   meilleurs de chaque catégorie sont gardés (tas borné) — plus de tri des 56 000 films.
        fun compter(rangee: String, n: Int) {
            val k = cleRangee(rangee); totaux[k] = (totaux[k] ?: 0) + n
        }
        // Tuiles sans affiche (grises) en fin de rangée : l'accueil ne montre que les 40 premières.
        val parAnnee = compareBy<VegetaVod.Film> { it.img.isNullOrBlank() }.thenByDescending { it.annee }.thenBy { it.titre }
        val idxV = if (vegetaOk) VegetaVod.indexSiCharge() else null
        if (idxV != null) {
            val ordre = VegetaVod.categoriesFilms().map { it.first }
            val (g, tot) = grouper(idxV.films, { listOf(it.cat) }, parAnnee, limite)
            for (cat in ordre + (g.keys - ordre.toSet())) {
                val r = joli(cat)
                compter(r, tot[cat] ?: 0)
                for (f in g[cat].orEmpty())
                    film(cleDe(f.tmdb.takeIf { it > 0 }?.toString(), f.titre), VegetaVod.titreFilm(f), f.img,
                        Source(VegetaVod.PREFIX_FILM + f.id, Origine.VEGETA), r)
            }
            val ordreS = VegetaVod.categoriesSeries().map { it.first }
            val (gs, totS) = grouper(idxV.series, { listOf(it.cat) },
                compareBy<VegetaVod.Serie> { it.img.isNullOrBlank() }.thenByDescending { it.annee }.thenBy { it.titre }, limite)
            for (cat in ordreS + (gs.keys - ordreS.toSet())) {
                val r = "Séries · ${joli(cat)}"
                compter(r, totS[cat] ?: 0)
                for (s in gs[cat].orEmpty()) {
                    val id = P_SERIE + VegetaVod.PREFIX_SERIE + s.id
                    entrees.getOrPut(id) {
                        Entree(id, propre(avecAnnee(s.titre, s.annee)), s.img, serie = true)
                            .also { it.sources += Source(VegetaVod.PREFIX_SERIE + s.id, Origine.VEGETA) }
                    }
                    ranger(r, id)
                }
            }
        }
        val t3 = System.currentTimeMillis()
        // 4. Ciné Films — portails OLA (même principe ; un titre peut porter plusieurs catégories)
        val idxO = if (olaOk) OlaVod.indexSiCharge() else null
        if (idxO != null) {
            val gardees = OlaVod.categories().map { it.first }
            val gardeesSet = gardees.toHashSet()   // catégories ≥ 5 films
            val (g, tot) = grouper(idxO.films, { f -> f.cats.filter { it in gardeesSet } },
                compareBy<OlaVod.Film> { it.img.isNullOrBlank() }.thenByDescending { it.annee }.thenBy { it.titre }, limite)
            for (cat in gardees) {
                val r = joli(cat)
                compter(r, tot[cat] ?: 0)
                for (f in g[cat].orEmpty())
                    film(cleDe(f.tmdb.takeIf { it > 0 }?.toString(), f.titre), OlaVod.titreFilm(f), f.img,
                        Source(OlaVod.PREFIX_FILM + f.id, Origine.OLA), r)
            }
            val gardeesS = OlaVod.categoriesSeries().map { it.first }
            val gardeesSSet = gardeesS.toHashSet()
            val (gs, totS) = grouper(idxO.series, { s -> s.cats.filter { it in gardeesSSet } },
                compareBy<OlaVod.Serie> { it.img.isNullOrBlank() }.thenByDescending { it.annee }.thenBy { it.titre }, limite)
            for (cat in gardeesS) {
                val r = "Séries · ${joli(cat)}"
                compter(r, totS[cat] ?: 0)
                for (s in gs[cat].orEmpty()) {
                    val id = P_SERIE + OlaVod.PREFIX_SERIE + s.id
                    entrees.getOrPut(id) {
                        Entree(id, propre(avecAnnee(s.titre, s.annee)), s.img, serie = true)
                            .also { it.sources += Source(OlaVod.PREFIX_SERIE + s.id, Origine.OLA) }
                    }
                    ranger(r, id)
                }
            }
        }
        val t4 = System.currentTimeMillis()
        // 5. Films OTF (VF) : une catégorie à eux, ajouts récents en tête (ordre de l'API).
        for (f in otf) {
            if (plein("Films OTF (VF)")) continue
            film("n" + norm(f.titreAffiche), f.titreAffiche, f.poster,
                Source("livehub::otffilm::${f.fichier}", Origine.OTF), "Films OTF (VF)")
        }
        // 2026-10-26 (vu sur la télé : « Colony » en tuile grise dans « Derniers ajouts ») : film
        //   perso sans affiche (fiche TMDB pas encore téléchargée) → l'image du MÊME film (même
        //   identifiant TMDB) dans Ciné Films, même quand il n'est pas retenu dans ses rangées.
        val sansAffiche = HashMap<Int, Entree>()
        for (e in entrees.values)
            if (!e.serie && e.poster.isNullOrBlank() && e.id.startsWith(P_FILM + "t"))
                e.id.removePrefix(P_FILM + "t").toIntOrNull()?.let { sansAffiche[it] = e }
        if (sansAffiche.isNotEmpty()) {
            idxO?.parTmdb?.let { m ->
                for ((tid, e) in sansAffiche)
                    m[tid]?.firstOrNull { !it.img.isNullOrBlank() }?.let { e.poster = it.img }
            }
            if (idxV != null && sansAffiche.values.any { it.poster.isNullOrBlank() })
                for (f in idxV.films)
                    if (f.tmdb > 0 && !f.img.isNullOrBlank())
                        sansAffiche[f.tmdb]?.let { if (it.poster.isNullOrBlank()) it.poster = f.img }
        }
        // Ordre : Nouveaux films, Communauté, OTF, genres de films (les plus fournis d'abord),
        //   séries, clips en dernier.
        fun priorite(libelle: String): Int {
            val n = norm(libelle)
            return when {
                n.startsWith("derniersajouts") -> -2
                n.startsWith("nouveauxfilms") -> -1
                n.startsWith("nouveau") -> 0
                n.startsWith("partage") -> 1
                n.startsWith("filmsotf") -> 2
                n.startsWith("series") -> 4
                n.startsWith("clips") -> 5
                else -> 3
            }
        }
        val t5 = System.currentTimeMillis()
        Log.d(TAG, "construction : perso+récents ${t1 - t0c} ms, communauté ${t2 - t1}, Vegeta ${t3 - t2}, OLA ${t4 - t3}, OTF ${t5 - t4}")
        val triees = rangees.values
            .sortedWith(compareBy<Pair<String, LinkedHashSet<String>>> { priorite(it.first) }.thenByDescending { totaux[cleRangee(it.first)] ?: it.second.size })
            .map { it.first to it.second.toList() }
        return Catalogue(entrees, triees)
    }

    /** 2026-10-26 (user : « ça fait bizarre » sur les affiches) : plus de repère d'origine
     *  dans les titres ; il ne reste que dans les noms de serveurs, et en debug seulement. */
    @Suppress("UNUSED_PARAMETER")
    private fun origines(e: Entree): String = ""

    /** 2026-10-26 (user : « il devrait avoir un dossier film et un dossier série ») : un film est
     *  un Movie (onglet Films, fiche film), une série un TvShow (onglet Séries). */
    private fun tuile(e: Entree): AppAdapter.Item =
        if (e.serie) tuileSerie(e)
        else Movie(id = e.id, title = e.titre + origines(e), poster = e.poster, banner = e.poster)
            .apply { providerName = name }

    private fun tuileSerie(e: Entree): TvShow =
        TvShow(id = e.id, title = e.titre + origines(e))
            .copy(poster = e.poster, banner = e.poster)
            .apply { providerName = name }

    private val verrouLeger = Mutex()

    /**
     * 2026-10-26 (télé TCL : le catalogue complet coûtait 17 s de processeur, demandé dès
     * l'ouverture par le préchargement des onglets) : version LÉGÈRE = 40 titres par rangée
     * (~6 000 titres). Sert à l'accueil, aux onglets Films / Séries, aux catégories et au filtre
     * par source. Le catalogue complet n'est plus jamais construit d'office.
     */
    /** Accueil construit alors qu'une source n'avait pas répondu : servi 1 min au plus, puis
     *  retenté (2026-10-26, télé TCL : index en « timeout » → accueil réduit aux seuls films
     *  OTF, qui restait ensuite en mémoire pour de bon). */
    @Volatile private var accueilIncomplet: Catalogue? = null
    @Volatile private var accueilIncompletTs = 0L

    private suspend fun leger(): Catalogue {
        cache?.takeIf { System.currentTimeMillis() - cacheTs < TTL_MS }?.let { return it }
        accueil?.let { return it }
        accueilIncomplet?.takeIf { System.currentTimeMillis() - accueilIncompletTs < 60_000L }?.let { return it }
        return withContext(Dispatchers.IO) {
            verrouLeger.withLock {
                accueil?.let { return@withLock it }
                accueilIncomplet?.takeIf { System.currentTimeMillis() - accueilIncompletTs < 60_000L }?.let { return@withLock it }
                val t0 = System.currentTimeMillis()
                val s = chargerSources()
                val t1 = System.currentTimeMillis()
                val partiel = construire(s, limite = PAR_RANGEE)
                val complet = s.vegetaOk && s.olaOk && s.perso.isNotEmpty()
                Log.i(TAG, "accueil${if (complet) "" else " INCOMPLET"} : ${partiel.entrees.size} titres en " +
                    "${System.currentTimeMillis() - t0} ms (index ${t1 - t0} ms, construction " +
                    "${System.currentTimeMillis() - t1} ms ; perso ${s.perso.size}, vegeta ${s.vegetaOk}, ola ${s.olaOk})")
                if (complet) { accueil = partiel; accueilIncomplet = null }
                else { accueilIncomplet = partiel; accueilIncompletTs = System.currentTimeMillis() }
                surveillerMemoire()
                partiel
            }
        }
    }

    // ─────────────────────────────────────────── recherche (index de titres léger)
    private class Ligne(val n: String, val id: String, val titre: String, val poster: String?, val serie: Boolean, val origine: Origine)
    @Volatile private var lignes: List<Ligne>? = null
    private val verrouLignes = Mutex()

    /** Titres d'une source, produits À LA DEMANDE (séquence paresseuse) : les onglets Films /
     *  Séries ne calculent que ce que la page affichée consomme. Ordre : bibliothèque perso,
     *  communauté, OTF, puis Ciné Films. [filtre] = clé de groupe (cf. groupe()), null = tout. */
    private fun lignesDe(s: Sources, serie: Boolean, filtre: String?): Sequence<Ligne> = sequence {
        fun ok(o: Origine) = filtre == null || groupe(o, serie).first == filtre
        if (!serie) {
            if (ok(Origine.PERSO)) for (p in s.persoPrep) yield(Ligne(norm(p.titre), P_FILM + p.cle, p.titre, p.poster, false, Origine.PERSO))
            if (ok(Origine.COMMU)) for (p in s.commuPrep) yield(Ligne(norm(p.titre), P_FILM + p.cle, p.titre, p.poster, false, Origine.COMMU))
            if (ok(Origine.OTF)) for (f in s.otf) { val n = norm(f.titreAffiche)
                yield(Ligne(n, P_FILM + "n" + n, propre(f.titreAffiche), f.poster, false, Origine.OTF)) }
            if (ok(Origine.VEGETA)) VegetaVod.indexSiCharge()?.let { idx ->
                for (f in idx.films) { val n = norm(propre(f.titre))
                    yield(Ligne(n, P_FILM + (if (f.tmdb > 0) "t${f.tmdb}" else "n$n"), propre(VegetaVod.titreFilm(f)), f.img, false, Origine.VEGETA)) } }
            if (ok(Origine.OLA)) OlaVod.indexSiCharge()?.let { idx ->
                for (f in idx.films) { val n = norm(propre(f.titre))
                    yield(Ligne(n, P_FILM + (if (f.tmdb > 0) "t${f.tmdb}" else "n$n"), propre(OlaVod.titreFilm(f)), f.img, false, Origine.OLA)) } }
        } else {
            if (ok(Origine.VEGETA)) VegetaVod.indexSiCharge()?.let { idx ->
                for (se in idx.series) yield(Ligne(norm(se.titre), P_SERIE + VegetaVod.PREFIX_SERIE + se.id,
                    propre(avecAnnee(se.titre, se.annee)), se.img, true, Origine.VEGETA)) }
            if (ok(Origine.OLA)) OlaVod.indexSiCharge()?.let { idx ->
                for (se in idx.series) yield(Ligne(norm(se.titre), P_SERIE + OlaVod.PREFIX_SERIE + se.id,
                    propre(avecAnnee(se.titre, se.annee)), se.img, true, Origine.OLA)) }
        }
    }

    /** Tous les titres (~80 000), réduits à « titre normalisé → tuile » : sans sources ni
     *  rangées, bien plus léger que le catalogue complet. Construit à la 1ʳᵉ recherche. */
    private suspend fun lignesRecherche(): List<Ligne> {
        lignes?.let { return it }
        return withContext(Dispatchers.Default) {
            verrouLignes.withLock {
                lignes?.let { return@withLock it }
                val t0 = System.currentTimeMillis()
                val s = chargerSources()
                // PAS de dédoublonnage ici : un film présent dans deux sources doit apparaître
                //   dans le filtre de chacune ; l'affichage dédoublonne à la lecture.
                val res: List<Ligne> = (lignesDe(s, false, null) + lignesDe(s, true, null)).toList()
                Log.i(TAG, "index de recherche : ${res.size} titres en ${System.currentTimeMillis() - t0} ms")
                lignes = res
                res
            }
        }
    }

    override suspend fun getHome(): List<Category> {
        // 2026-10-26 (user : « le dossier est long à ouvrir ») : version légère (cf. leger()).
        val c = leger()
        // 30 rangées au plus sur l'accueil (il y en a ~150 avec les catégories IPTV) ;
        //   le reste se retrouve par la recherche.
        return c.rangees.take(MAX_RANGEES).mapNotNull { (libelle, ids) ->
            val tuiles = ids.asSequence().mapNotNull { c.entrees[it] }.take(PAR_RANGEE).map { tuile(it) }.toList()
            if (tuiles.isEmpty()) null else Category(name = libelle, list = tuiles)
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val q = norm(query)
        // 2026-10-26 (user : « améliorer la recherche pour qu'il y ait les plusieurs genres en
        //   dessous ») : recherche vide = la liste de TOUTES les catégories (y compris celles
        //   qui ne tiennent pas sur l'accueil) ; un clic ouvre la catégorie (getGenre).
        if (query.isBlank()) {
            return leger().rangees.map { (libelle, _) -> Genre(id = libelle, name = libelle) }
        }
        if (q.isBlank()) return emptyList()
        return lignesRecherche().asSequence()
            .filter { it.n.contains(q) }.distinctBy { it.id }
            .sortedBy { if (it.n.startsWith(q)) 0 else 1 }
            .take(100)
            .map { l ->
                if (l.serie) TvShow(id = l.id, title = l.titre).copy(poster = l.poster, banner = l.poster)
                    .apply { providerName = name } as AppAdapter.Item
                else Movie(id = l.id, title = l.titre, poster = l.poster, banner = l.poster).apply { providerName = name }
            }.toList()
    }

    // ─────────────────────────────────────────── filtre par source (onglets Films / Séries)
    // 2026-10-26 (user : « quand on reste appuyé sur le dossier film ou le dossier série, on
    //   puisse trier… je choisis que OTF, que partage de la communauté » — « et pareil pour les
    //   séries ») : un filtre par source pour chaque onglet. null = tout.
    @Volatile var filtreFilms: String? = null
    @Volatile var filtreSeries: String? = null

    // 2026-10-26 (user : « quand on affiche tous les films et les séries, on n'a aucun moyen
    //   de les trier, on est obligé de passer par la recherche ») : tri des onglets, choisi
    //   dans la même feuille que la source. « recent » = ordre naturel (bibliothèque du plus
    //   récent au plus ancien, puis communauté, OTF, Ciné Films) ; les deux autres trient
    //   TOUTE la liste une fois (mémorisée), puis la servent page par page.
    val TRIS = listOf("recent" to "Derniers ajouts", "annee" to "Année (récent d'abord)", "az" to "A → Z")
    @Volatile var triFilms: String = "recent"
    @Volatile var triSeries: String = "recent"
    private val RE_ANNEE_TITRE = Regex("\\((\\d{4})\\)")
    private class Trie(val cle: String, val liste: List<Ligne>, val ts: Long)
    @Volatile private var trie: Trie? = null
    private val verrouTri = Mutex()

    private fun anneeDe(titre: String): Int =
        RE_ANNEE_TITRE.find(titre)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun groupe(o: Origine, serie: Boolean): Pair<String, String> = when (o) {
        Origine.PERSO -> "perso" to "ONYX"   // 2026-10-26 (user : « je l'aurais juste appelé Onyx »)
        Origine.COMMU -> "commu" to "Partage de la communauté"
        Origine.OTF -> "otf" to "Films OTF (VF)"
        Origine.VEGETA -> "cine" to "Ciné Films"
        Origine.OLA -> "plus" to (if (serie) "Plus de séries" else "Plus de films")
    }

    /** Choix proposés pour un onglet : « Tout » puis les sources réellement présentes. */
    suspend fun choixFiltre(series: Boolean): List<Pair<String, String?>> {
        // Sans rien construire : une source est proposée si son index a des titres de ce type.
        val s = chargerSources()
        val v = VegetaVod.indexSiCharge(); val o = OlaVod.indexSiCharge()
        val presentes = if (series) listOfNotNull(
            Origine.VEGETA.takeIf { v?.series?.isNotEmpty() == true },
            Origine.OLA.takeIf { o?.series?.isNotEmpty() == true },
        ) else listOfNotNull(
            Origine.PERSO.takeIf { s.persoPrep.isNotEmpty() },
            Origine.COMMU.takeIf { s.commuPrep.isNotEmpty() },
            Origine.VEGETA.takeIf { v?.films?.isNotEmpty() == true },
            Origine.OLA.takeIf { o?.films?.isNotEmpty() == true },
            Origine.OTF.takeIf { s.otf.isNotEmpty() },
        )
        return listOf<Pair<String, String?>>("Tout" to null) +
            presentes.map { groupe(it, series) }.distinctBy { it.first }.map { it.second to it.first }
    }

    /**
     * 2026-10-26 (user : « quand on affiche tout, faut que ça soit progressif, sinon le home
     * il va exploser ; pareil pour les séries ») : onglets Films / Séries lus PAGE PAR PAGE
     * dans l'index léger des titres (bibliothèque perso, communauté et OTF en tête, puis
     * Ciné Films). Rien n'est construit au-delà de la page demandée ; « Tout » dédoublonne
     * à la volée, un filtre ne garde que les titres de SA source.
     */
    private suspend fun page(serie: Boolean, filtre: String?, page: Int): List<Ligne> =
        withContext(Dispatchers.Default) {
            // Index de recherche déjà construit → on le lit ; sinon séquence paresseuse :
            //   seuls les titres jusqu'à la page demandée sont calculés.
            suspend fun base() = lignes?.asSequence()
                ?.filter { it.serie == serie && (filtre == null || groupe(it.origine, serie).first == filtre) }
                ?: lignesDe(chargerSources(), serie, filtre)
            val tri = if (serie) triSeries else triFilms
            if (tri == "recent")
                return@withContext base().distinctBy { it.id }.drop((page - 1) * PAR_PAGE).take(PAR_PAGE).toList()
            // Tri demandé : la liste entière de CETTE source, triée une fois puis mémorisée
            //   (on ne garde que la dernière combinaison onglet/source/tri).
            val cle = "$serie|$filtre|$tri"
            val liste = trie?.takeIf { it.cle == cle && System.currentTimeMillis() - it.ts < TTL_MS }?.liste
                ?: verrouTri.withLock {
                    trie?.takeIf { it.cle == cle && System.currentTimeMillis() - it.ts < TTL_MS }?.liste
                        ?: run {
                            val t0 = System.currentTimeMillis()
                            val tout = base().distinctBy { it.id }.toList()
                            // A → Z (vu sur l'Oppo : la liste démarrait par des titres en hébreu puis
                            //   « 07 », « 08 »…) : lettres d'abord, puis chiffres, puis les titres
                            //   en alphabet non latin (titre normalisé vide).
                            fun classe(n: String) = when { n.isEmpty() -> 2; n[0].isDigit() -> 1; else -> 0 }
                            val l = if (tri == "az") tout.sortedWith(compareBy<Ligne> { classe(it.n) }.thenBy { it.n })
                                else tout.map { it to anneeDe(it.titre) }   // année lue UNE fois par titre
                                    .sortedWith(compareByDescending<Pair<Ligne, Int>> { it.second }
                                        .thenBy { classe(it.first.n) }.thenBy { it.first.n })
                                    .map { it.first }
                            Log.i(TAG, "tri $tri (${if (serie) "séries" else "films"}, source=${filtre ?: "tout"}) : " +
                                "${l.size} titres en ${System.currentTimeMillis() - t0} ms")
                            trie = Trie(cle, l, System.currentTimeMillis())
                            l
                        }
                }
            liste.drop((page - 1) * PAR_PAGE).take(PAR_PAGE)
        }

    override suspend fun getMovies(page: Int): List<Movie> =
        page(false, filtreFilms, page).also {
            Log.d(TAG, "films page $page, filtre=$filtreFilms → ${it.size} (${it.firstOrNull()?.titre})")
        }.map { l ->
            Movie(id = l.id, title = l.titre, poster = l.poster, banner = l.poster).apply { providerName = name }
        }

    override suspend fun getTvShows(page: Int): List<TvShow> =
        page(true, filtreSeries, page).map { l ->
            TvShow(id = l.id, title = l.titre).copy(poster = l.poster, banner = l.poster).apply { providerName = name }
        }

    // 2026-10-26 : fiches et serveurs SANS construire le catalogue complet (cf. sourcesDirectes).
    override suspend fun getMovie(id: String): Movie {
        val (titre, poster) = infosDe(id)
        return Movie(id = id, title = titre, poster = poster, banner = poster)
            .apply { providerName = name }
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (id.startsWith(P_FILM)) {
            val (titre, poster) = infosDe(id)
            return TvShow(id = id, title = titre).copy(
                poster = poster, banner = poster,
                seasons = listOf(
                    Season(
                        id = id, number = 1, title = "Film",
                        episodes = listOf(Episode(id = id, number = 1, title = "Lire", poster = poster)),
                    ),
                ),
            ).apply { providerName = name }
        }
        val origine = id.removePrefix(P_SERIE).removePrefix(PREFIX)
        val s = LiveTvHubProvider.getTvShow(origine)
        val e = entree(id)
        // Saison sans vignette (tuile grise) → affiche de la série.
        val affiche = s.poster ?: e?.poster
        // Copies : on ne touche pas aux objets mis en cache par le TV Hub.
        val saisons = s.seasons.map { sa ->
            Season(
                id = PREFIX + sa.id, number = sa.number, title = sa.title, poster = sa.poster ?: affiche,
                episodes = sa.episodes.map { ep ->
                    Episode(id = PREFIX + ep.id, number = ep.number, title = ep.title, poster = ep.poster, overview = ep.overview)
                },
            )
        }
        // Titre NETTOYÉ (« FR ★ Motorheads » → « Motorheads ») : c'est lui que les serveurs de
        //   secours (replays TF1+/M6+, Movix…) cherchent ; avec le préfixe, rien ne correspondait.
        return TvShow(id = id, title = propre(e?.titre ?: s.title) + (e?.let { origines(it) } ?: "")).copy(
            poster = s.poster ?: e?.poster, banner = s.banner ?: s.poster ?: e?.poster, seasons = saisons,
        ).apply { providerName = name }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        LiveTvHubProvider.getEpisodesBySeason(seasonId.removePrefix(PREFIX)).map { ep ->
            Episode(id = PREFIX + ep.id, number = ep.number, title = ep.title, poster = ep.poster, overview = ep.overview)
        }

    /** Une catégorie complète, page par page (id = libellé de la rangée). */
    override suspend fun getGenre(id: String, page: Int): Genre {
        val c = leger()
        val ids = c.rangees.firstOrNull { it.first == id }?.second.orEmpty()
        val shows: List<com.streamflixreborn.streamflix.models.Show> = ids.asSequence().drop((page - 1) * PAR_PAGE).take(PAR_PAGE)
            .mapNotNull { c.entrees[it] }
            .map { e -> if (e.serie) tuileSerie(e) else tuile(e) as Movie }
            .toList()
        return Genre(id = id, name = id, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val sources: List<Source> = if (id.startsWith(P_FILM)) {
            // Catalogue complet déjà construit → ses sources ; sinon recherche directe (rapide).
            cache?.entrees?.get(id)?.sources?.sortedBy { it.origine.rang } ?: sourcesDirectes(id)
        } else {
            val brut = id.removePrefix(P_SERIE).removePrefix(PREFIX)
            listOf(Source(brut, Origine.VEGETA.takeIf { brut.contains("vegeta") } ?: Origine.OLA))
        }
        if (sources.isEmpty()) return emptyList()
        val parSource = coroutineScope {
            sources.map { s ->
                async {
                    val l = withTimeoutOrNull(20_000) {
                        runCatching { LiveTvHubProvider.getServers(s.id, videoType) }
                            .onFailure { Log.w(TAG, "serveurs ${s.id} KO : ${it.message}") }
                            .getOrNull()
                    }.orEmpty()
                    l.map { it to s.origine }
                }
            }.awaitAll()
        }
        // 2026-10-26 (user : « les Vegeta ne devraient pas être marqués », puis « tu l'as appelé
        //   ONYX 1, mais il est censé venir de Ciné Films ») : chaque serveur porte le NOM PUBLIC
        //   de sa source, numéroté — ONYX (bibliothèque perso), Communauté, Ciné Films (Vegeta
        //   et portails OLA confondus), OTF. Jamais de nom interne. Seule la langue VOSTFR est
        //   gardée (le lecteur s'en sert pour activer les sous-titres français).
        val compteurs = HashMap<String, Int>()
        return parSource.flatten().map { (srv, origine) ->
            val nom = nomPublic(origine)
            val n = (compteurs[nom] ?: 0) + 1
            compteurs[nom] = n
            val vost = RE_VOST.containsMatchIn(srv.name)
            srv.copy(name = "$nom $n" + (if (vost) " [VOSTFR]" else ""))
        }
    }

    private val RE_VOST = Regex("vostfr|subfrench|\\bvost\\b", RegexOption.IGNORE_CASE)

    private fun nomPublic(o: Origine): String = when (o) {
        Origine.PERSO -> "ONYX"
        Origine.COMMU -> "Communauté"
        Origine.VEGETA, Origine.OLA -> "Ciné Films"
        Origine.OTF -> "OTF"
    }

    override suspend fun getVideo(server: Video.Server): Video = LiveTvHubProvider.getVideo(server)

    /** Préchargement discret (appelé au démarrage) : l'accueil du provider s'ouvre ensuite tout de suite. */
    fun prechauffer() {
        if (cache != null) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { leger() }
        }
    }
}
