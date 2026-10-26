package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.LiveTvHubProvider
import com.streamflixreborn.streamflix.providers.OlaTvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.VavooProvider
import com.streamflixreborn.streamflix.providers.VegetaTvProvider
import com.streamflixreborn.streamflix.sport.SportEvent
import com.streamflixreborn.streamflix.sport.SportSelfTest
import com.streamflixreborn.streamflix.sport.providers.DamiTv
import com.streamflixreborn.streamflix.sport.providers.DamiTvChaines
import com.streamflixreborn.streamflix.sport.providers.SportProviders
import com.streamflixreborn.streamflix.sport.providers.XonaFlix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer

/**
 * Dossier « Sport » du TV Hub — toutes les chaînes et tous les matchs de sport, d'où qu'ils
 * viennent, rangés au même endroit.
 *
 * 2026-10-25 (demande d'un utilisateur : la Liga et la Serie A en français existent chez OLA et
 * chez Vegeta, mais il faut savoir où chercher ; user : « si tu arrives à regrouper toutes les
 * chaînes de sport dans un seul dossier bien rangé… au pire utilise mon dossier RénéVéo pour le
 * transformer en dossier Sport, il y a pas beaucoup de contenu »).
 *   • La carte RénéVéo devient « Sport » (même clé `reneveo` : verrous et ordre des cartes
 *     conservés). Les chaînes RénéVéo non sportives restent au bout, dans leur propre rubrique.
 *   • Sources : OLA, Vegeta, Vavoo (registres en mémoire, comme la recherche), RénéVéo (rubrique
 *     sport du site) et Mix FR (data.m3u). World Live est exclu (trop large, peu fiable).
 *   • Une même chaîne trouvée chez plusieurs sources = UNE tuile, avec les serveurs de toutes
 *     (« OLA · … », « Vegeta · … », …). Chaque serveur est lu par sa source d'origine.
 *   • Rangement : d'abord les matchs du moment, par championnat (Ligue 1, Liga, Serie A…), puis
 *     les chaînes fixes par bouquet (beIN, Canal+, RMC, Eurosport, DAZN…).
 *
 * Identifiants : tuile `livehub::sport::<clé>` ; les serveurs gardent l'id de leur source.
 */
object SportHub {

    private const val TAG = "SportHub"
    const val PREFIX = "livehub::sport::"
    /** La carte du TV Hub réutilise la clé de l'ancien dossier RénéVéo. */
    const val FOLDER_KEY = ReneveoTv.FOLDER_KEY
    // 2026-10-27 (user, choix « S9 ») : télé jaune avec ballon et « LIVE »
    //   (avant : 857/857455, trois ballons seuls).
    const val LOGO = "https://cdn-icons-png.flaticon.com/512/3363/3363444.png"
    private const val TTL_MS = 20 * 60 * 1000L
    private const val DELAI_SOURCE_MS = 20_000L
    /** 2026-10-28 (user, cf. RELAIS « CHAÎNES TV xonaflix ») : une catégorie XonaFlix va
     *  jusqu'à ~21 pages (IPTV ≈ 421 chaînes) — plus de marge que les 20 s habituelles. */
    private const val DELAI_SOURCE_TV_MS = 45_000L
    /** Une source lente ne doit pas retarder le lancement : mesuré 10 s d'attente sur
     *  beIN Sports 1 (Vegeta qui complète sa liste), alors qu'OLA avait répondu. */
    private const val DELAI_SERVEURS_MS = 6_000L

    /** proprio : ola / vegeta / vavoo / reneveo / mixfr. */
    data class Source(val proprio: String, val id: String)

    data class Entree(
        val cle: String,
        val nom: String,
        val logo: String,
        val rubrique: String,
        val sources: List<Source>,
        /** Événement en direct (sources web) : rangé en tête de sa rubrique. */
        val live: Boolean = false,
    )

    /**
     * Expressions régulières compilées UNE fois. Mesuré sur la TCL (25/10) : le tri des
     * ~2 400 chaînes prenait 17 s, car chaque nom recompilait une trentaine d'expressions
     * (et le tri des tuiles en recompilait à chaque comparaison). DOIT rester déclaré avant
     * toute propriété qui l'utilise (ordre d'initialisation de l'objet).
     */
    private val cacheRx = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun rx(motif: String, vararg options: RegexOption): Regex =
        cacheRx.getOrPut(if (options.isEmpty()) motif else motif + options.joinToString { it.name }) {
            Regex(motif, options.toSet())
        }

    /** Sous-dossiers du dossier Sport : `livehub::folder::sportdos_<id>`. */
    const val PREFIXE_SOUS_DOSSIER = "sportdos_"
    private const val NOM_DOSSIER_WEB = "🌐 Nouvelles sources"
    private const val NOM_DOSSIER_CDIRECT = "📡 ChaineDirect"

    /**
     * 2026-10-27 (user : « les chaînes hors sport n'ont rien à faire dans Sport ») : dossier
     * « Chaînes TV » au premier niveau du TV Hub — tout le direct hors sport, rangé par thème
     * (mêmes rubriques que chainedirect.com) + un sous-dossier Live MIX (chaînes ajoutées avec la
     * communauté). Même moteur que Sport : une chaîne trouvée chez plusieurs sources = une tuile,
     * serveurs de toutes les sources. C'est un RACCOURCI : Mix FR et les autres dossiers ne perdent rien.
     */
    const val FOLDER_KEY_TV = "chainestv"
    const val LOGO_TV = "https://cdn-icons-png.flaticon.com/512/10184/10184009.png" // télé + télécommande, choisie par le user
    private const val LIVE_MIX = "📡 Live MIX"
    const val SOUS_DOSSIER_RAKUTEN = "rakuten"
    const val SOUS_DOSSIER_SONY = "sony"
    /** 2026-10-28 (user, cf. RELAIS « CHAÎNES TV xonaflix ») : plateforme XonaFlix du dossier
     *  Chaînes TV — ses sous-dossiers sont `xona_<catégorie du site>` (chargés au clic). */
    const val SOUS_DOSSIER_XONA = "xona"
    private val IDS_TV = setOf("tv", "livemix")
    private val THEMES_TV: List<String> by lazy {
        listOf("generaliste", "info", "cinema", "documentaire", "enfants", "musique", "belgique")
            .mapNotNull { com.streamflixreborn.streamflix.sport.providers.ChaineDirect.RUBRIQUES[it] }
    }
    private fun themeTv(cle: String) = com.streamflixreborn.streamflix.sport.providers.ChaineDirect.RUBRIQUES[cle]!!

    /** Thème d'une catégorie (TV Hub, RénéVéo) ou d'un groupe Mix FR ; null = hors Chaînes TV. */
    private fun themeDe(categorie: String?): String? {
        val g = plat(categorie.orEmpty().substringBefore(" - ").trim())
        return when {
            g.isEmpty() -> null
            g.startsWith("tnt") || g.startsWith("generalist") || g == "locale" || g.startsWith("divertissement") ->
                themeTv("generaliste")
            g == "info" || g.startsWith("politique") || g.startsWith("legislative") || g.startsWith("business") ->
                themeTv("info")
            g.startsWith("cinema") || g.startsWith("serie") || g.startsWith("canal+") || g.startsWith("live canal") ->
                themeTv("cinema")
            g.startsWith("culture") || g.startsWith("documentaire") -> themeTv("documentaire")
            g.startsWith("jeunesse") || g.startsWith("enfant") -> themeTv("enfants")
            g.startsWith("musique") -> themeTv("musique")
            g.startsWith("belgique") || g.startsWith("suisse") -> themeTv("belgique")
            else -> null
        }
    }

    /** Clé de tuile d'une chaîne TV (même moulinette pour les tuiles et pour l'ordre ci-dessous). */
    private fun cleTuileTv(nom: String): String = "t_" + cleChaine(plat(nom).replace(rx("\\s+france$"), ""))

    /**
     * 2026-10-27 (user : « trier chaque dossier dans un ordre digne de la TNT ») : ordre d'affichage
     * des chaînes dans Chaînes TV. Généralistes dans l'ordre des canaux TNT (1 TF1 … 27 franceinfo),
     * puis, thème par thème, les grandes chaînes d'abord. Écrit en NOMS lisibles : chacun passe par
     * [cleTuileTv], comme les tuiles. Une chaîne absente de la liste vient après, par ordre alphabétique.
     */
    private val ORDRE_TV: Map<String, Int> by lazy {
        val noms = listOf(
            // TNT (numéros de canal)
            "TF1", "France 2", "France 3", "Canal+", "France 5", "M6", "Arte", "C8", "W9", "TMC", "TFX",
            "NRJ 12", "LCP", "Public Sénat", "France 4", "BFM TV", "CNews", "CStar", "Gulli",
            "TF1 Séries Films", "L'Équipe", "6ter", "RMC Story", "RMC Découverte", "Chérie 25", "LCI", "Franceinfo",
            // Généralistes hors TNT
            "Paris Première", "Téva", "RTL9", "AB1", "Numéro 23", "T18", "Novo19", "France Ô", "TV5 Monde",
            // Info
            "France 24", "BFM Business", "Euronews", "i24 News", "BFM Paris Île-de-France", "BFM Régions",
            // Cinéma
            "Canal+ Cinéma", "Canal+ Séries", "Canal+ Grand Écran", "Canal+ Box Office", "Canal+ Family",
            "Canal+ Docs", "Ciné+ Premier", "Ciné+ Frisson", "Ciné+ Émotion", "Ciné+ Famiz", "Ciné+ Club",
            "Ciné+ Classic", "OCS Max", "OCS City", "OCS Choc", "OCS Géants", "TCM Cinéma", "Paramount Channel",
            "Warner TV", "13ème Rue", "Syfy", "Polar+", "Action",
            // Documentaire
            "Planète+", "Planète+ Crime", "Planète+ Aventure", "Ushuaïa TV", "Histoire TV", "National Geographic",
            "Nat Geo Wild", "Discovery Channel", "Discovery Science", "Discovery Investigation", "Discovery Family",
            "Science et Vie TV", "Animaux", "Voyage", "Seasons", "Chasse et Pêche",
            // Enfants
            "Disney Channel", "Disney Junior", "Cartoon Network", "Boomerang", "Nickelodeon", "Nick Jr.",
            "Canal J", "Tiji", "Piwi+", "Tfou Max", "Toonami", "Boing", "Télétoon+",
            // Musique
            "M6 Music", "MTV", "MCM", "NRJ Hits", "Trace Urban", "Trace Africa", "Trace Latina", "Trace Tropical",
            "Mezzo", "Fun Radio TV", "RFM TV",
            // Belgique et Suisse
            "La Une", "La Deux", "La Trois", "RTL TVI", "Club RTL", "Plug RTL", "LN24", "RTS 1", "RTS 2",
        )
        val m = LinkedHashMap<String, Int>()
        for (n in noms) { val k = cleTuileTv(n); if (k !in m) m[k] = m.size }
        m
    }
    private fun rangTv(cle: String): Int = ORDRE_TV[cle] ?: Int.MAX_VALUE

    /** Nom affiché d'une chaîne TV : sans préfixe pays ni étiquette de qualité (« TF1 HD » → « TF1 »). */
    private fun nomTv(brut: String): String =
        propre(brut).replace(rx("\\s*[\\[(][^\\])]*[\\])]"), " ")
            .replace(rx("\\s+(fhd|uhd|hd|sd|4k|hevc|h265|1080p|720p|backup)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(rx("\\s+"), " ").trim().ifEmpty { brut.trim() }

    /** Tuiles déjà construites, clé « <sous-dossier>::<clé> » (cf. [tuile]). */
    @Volatile private var entrees: Map<String, Entree> = emptyMap()

    // ────────────────────────────────────────────────────────── Reconnaissance

    /** Minuscules, sans accents, apostrophes retirées. */
    private fun plat(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(rx("\\p{M}+"), "")
            .lowercase().replace("’", "").replace("'", "")

    /** Préfixe pays des listes IPTV (« FR| », « [FR] », « FR: », « FR - »…). */
    private val PREFIXE_PAYS = rx("^\\s*[\\[(]?(fr|france|fra|be|ch|ca|qc)[\\])]?\\s*[|:\\-]\\s*", RegexOption.IGNORE_CASE)

    private val MOTS_SPORT = rx(
        "bein|sport|dazn|eurosport|equipe|golf|ligue ?1|\\bfoot|automoto|motors? ?tv|espn|setanta|equidia|" +
        "\\bnba\\b|\\bnfl\\b|\\bnhl\\b|\\bufc\\b|\\bwwe\\b|rugby|tennis|\\bf1\\b|formule ?1|moto ?gp|cyclisme|" +
        "\\bboxe|premier league|serie a\\b|laliga|la liga|bundesliga|champions league|ligue des champions|europa league|" +
        "multiplex|olympi|" +
        // 2026-10-25 : chaînes foot ajoutées à data.m3u dont le nom ne contient pas de mot-clé sport évident.
        "real madrid|\\bspor\\b|\\bssc\\b|arryadia|arriadia|mutv|" +
        // 2026-10-28 (relais) : chaînes auto-moto que rien ne reconnaissait comme sport.
        "motorvision|motor ?racing|motorsport|speedvision|speed sport|\\bturbo\\b"
    )
    private val FAUX_SPORT = rx("transport|passeport|sportif du dimanche")

    fun estSport(nom: String): Boolean {
        val p = plat(nom)
        return MOTS_SPORT.containsMatchIn(p) && !FAUX_SPORT.containsMatchIn(p)
    }

    /** Nom affiché : sans le préfixe pays. */
    private fun propre(nom: String): String = nom.replace(PREFIXE_PAYS, "").trim().ifEmpty { nom.trim() }

    // Étiquettes de tête des listes IPTV (« FR| », « |C+CAR| », « Next | », « Europe: »…).
    private val TAG_BARRES = rx("^\\|([^|]{1,10})\\|\\s*")
    private val TAG_BARRE = rx("^([A-Za-z0-9+ ]{1,8}?)\\s*\\|\\s*")
    private val TAG_DEUX_POINTS = rx("^([A-Za-zÀ-ÿ\\- ]{2,20}):\\s*")
    /** Étiquettes gardées : France, statut du match, bouquets français d'outre-mer. */
    private val TAGS_OK = setOf("fr", "france", "fra", "sp", "sport", "sports", "prime", "c+car", "c+", "next", "end", "live", "canal", "vip")
    /** « Tennis: … », « Cyclisme: … » : ce n'est pas un pays, on garde tel quel. */
    private val TAGS_DISCIPLINE = rx("^(tennis|cyclisme|football|foot|basket|basketball|rugby|golf|f1|formule 1|moto|motogp|boxe|ufc|nba|nfl|handball|volley|athletisme|natation|ski|snooker|flechettes|darts)$")
    private val TETE_ETRANGERE = rx("^(esp ?d?|eur|uk|us|usa|de|it|ar|ara|arab|pt|tr|pl|nl|af|afk|ex ?yu|alb|gr)\\s*[-|:]", RegexOption.IGNORE_CASE)
    private val HORS_SPORT = rx(
        "\\bbfm\\b|rmc (alerte|mecanic|mystere|decouverte|story|life|j ?irai)|" +
        // épisodes de séries (« Avengers L'équipe des super héros S01 E01 »)
        "\\bs\\d{1,2} ?e\\d{1,3}\\b|" +
        // variantes étrangères et canaux vides
        "paises|bajos|\\bcee\\b|\\brs\\b|polska|deutsch|nederland|\\bturk|\\busa\\b|no event|arabe|arabic|english$"
    )
    /** beIN : seulement le sport (le bouquet MENA a aussi Anime, Box Office, Baraem…). */
    private val BEIN_SPORT = rx("bein ?(sports?|max|xtra|premium|asian|\\d)|^bein$")
    private val PARTIES_INUTILES = rx("^(fr|hd|fhd|uhd|sd|4k|hevc|lq|raw|backup|8k exclusive|4k exclusive|all|\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2}-\\d{4}|fr ?: .*|diffusion en cours.*)$")
    private val MOIS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Date écrite dans le nom d'un match (« 2026-10-25 », « 25-10-2026 », « @ Sep 6 »), sinon null. */
    private fun dateDans(nom: String): java.time.LocalDate? = runCatching {
        rx("(\\d{4})-(\\d{2})-(\\d{2})").find(nom)?.destructured?.let { (a, m, j) ->
            return@runCatching java.time.LocalDate.of(a.toInt(), m.toInt(), j.toInt())
        }
        rx("\\b(\\d{2})-(\\d{2})-(\\d{4})\\b").find(nom)?.destructured?.let { (j, m, a) ->
            return@runCatching java.time.LocalDate.of(a.toInt(), m.toInt(), j.toInt())
        }
        rx("@ ([A-Za-z]{3})[a-z]* (\\d{1,2})\\b").find(nom)?.destructured?.let { (m, j) ->
            val mois = MOIS.indexOf(m.lowercase()) + 1
            if (mois > 0) return@runCatching java.time.LocalDate.now().withMonth(mois).withDayOfMonth(j.toInt())
        }
        // « Sat 18 May 12:00 »
        //   (mois anglais abrégé, mot entier : « 1 Marseille » n'est pas une date)
        rx("\\b(\\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b").find(nom)?.destructured?.let { (j, m) ->
            val mois = MOIS.indexOf(m.lowercase().take(3)) + 1
            if (mois > 0) return@runCatching java.time.LocalDate.now().withMonth(mois).withDayOfMonth(j.toInt())
        }
        null
    }.getOrNull()

    /**
     * Nom lisible, ou null si la chaîne est à écarter (autre pays, séparateur de liste, match
     * d'un autre jour, chaîne beIN Cinéma…). Mesuré le 25/10 : sans ce tri, la moitié du
     * dossier était bulgare, espagnole, arabe ou des matchs de tennis de septembre.
     */
    private fun nettoyer(brut: String): String? {
        var n = brut.trim()
        if (n.isEmpty() || n.first() in "=#*-~") return null
        if (TETE_ETRANGERE.containsMatchIn(n)) return null
        n = n.replace("•", " ").replace("ᴿᴬᵂ", " ").replace(rx("\\s\\.[a-z]{1,2}\\b"), " ")
            .replace(rx("^\\d{1,4}\\.\\s+"), "").replace(rx("\\s+"), " ").trim()
        // Étiquettes de tête, plusieurs possibles (« Next | FR| … »).
        repeat(3) {
            val m = TAG_BARRES.find(n) ?: TAG_BARRE.find(n)
            if (m != null) {
                val reste = n.substring(m.range.last + 1).trim()
                // « AUTOMOTO || » : la « étiquette » est en fait le nom.
                if (reste.replace("|", "").isBlank()) { n = m.groupValues[1].trim(); return@repeat }
                if (plat(m.groupValues[1]).trim().replace(" ", "") !in TAGS_OK) return null
                n = reste
                return@repeat
            }
            val d = TAG_DEUX_POINTS.find(n) ?: return@repeat
            val tag = plat(d.groupValues[1]).trim()
            when {
                TAGS_DISCIPLINE.matches(tag) -> return@repeat
                tag in setOf("fr", "france") -> n = n.substring(d.range.last + 1).trim()
                tag == "europe" && n.contains("(FR)", ignoreCase = true) ->
                    n = n.substring(d.range.last + 1).replace(rx("\\(fr\\)", RegexOption.IGNORE_CASE), "").trim()
                else -> return null
            }
        }
        if (n.isEmpty() || HORS_SPORT.containsMatchIn(plat(n))) return null
        if (plat(n).contains("bein") && !BEIN_SPORT.containsMatchIn(plat(n).trim())) return null
        // Numéro de canal du portail devant un match (« DAZN FR 017| », « D+ FR 001| ») : le
        //   même match est souvent sur deux canaux, on les fusionne sous un seul nom.
        val canal = rx("^[A-Za-z+ ]{1,8} FR \\d{1,3}\\s*\\|\\s*").find(n)
        if (canal != null && n.length > canal.range.last + 4) n = n.substring(canal.range.last + 1).trim()
        // « (2026-10-25 14:00:00) » → « · 14:00 »
        n = n.replace(rx("\\(\\d{4}-\\d{2}-\\d{2} (\\d{2}:\\d{2}):\\d{2}\\)"), "| $1")
        // Symboles décoratifs et ponctuation en bout de nom.
        n = n.replace(rx("[●✬◉★☆✪▶►◄]|\\(\\s*\\)"), " ").replace(rx("\\s+"), " ").trim()
            .trim('|', '-', '·', ' ')
        if (n.isEmpty() || n.first() in "=#*-~") return null
        // Match d'un autre jour (liste pas à jour) : on n'en montre que la veille → le lendemain.
        dateDans(brut)?.let { d ->
            val jours = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), d))
            if (jours > 1) return null
        }
        // « A vs. B | Ligue 1+ | 2026-10-25 | 16:15 | 8K EXCLUSIVE | FR: DAZN 93 » → « A vs. B · Ligue 1+ · 16:15 ».
        val parties = n.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parties.size >= 2) {
            n = parties.filter { !PARTIES_INUTILES.matches(plat(it)) }.joinToString(" · ").ifEmpty { n }
        }
        return n.trim().ifEmpty { null }
    }

    /** Un match (chaîne événementielle) plutôt qu'une chaîne fixe. */
    private val MARQUES_MATCH = rx("\\bvs?\\.?\\s|\\s[-–]\\s.*\\s[-–]\\s|\\b\\d{1,2}[:h]\\d{2}\\b")
    private fun estMatch(nomPropre: String): Boolean {
        val p = plat(nomPropre)
        if (MARQUES_MATCH.containsMatchIn("$p ")) return true
        // « Atlético de Madrid - Real Madrid · Spanish LALIGA » : deux équipes + une compétition.
        val equipes = p.substringBefore('·').split(" - ")
        if (equipes.size == 2 && equipes.all { e -> e.count { it.isLetter() } >= 3 } &&
            COMPETITIONS.any { it.second.containsMatchIn(p) }) return true
        // « DAZN 3 | Lens Brest » : texte après la barre = un match ; « beIN 1 | FHD » = non.
        val apres = p.substringAfter('|', "").replace(BRUIT, " ").replace(rx("[^a-z]+"), " ").trim()
        return apres.length >= 4
    }

    private val BRUIT = rx(
        "\\b(hd|fhd|fihd|uhd|sd|lq|hdr|4k|8k|hevc|h26[45]|1080p?|720p?|50 ?fps|60 ?fps|backup|vip|raw|multi|direct|live|fr|france|tv)\\b"
    )

    /** Clé d'une chaîne fixe : « beIN SPORTS 1 HD » = « BEIN SPORT 1 » = « FR| beIN Sports 1 ». */
    private fun cleChaine(nomPropre: String): String {
        var p = plat(nomPropre).replace(rx("[\\[(].*?[\\])]"), " ")
            .replace(rx("-\\d{3,4}p?\\b"), " ")          // « Bein sport 1-540 »
            .replace(rx("\\b0+(\\d)"), "$1")              // « beIN MAX 01 » = « beIN MAX 1 »
            .replace(rx("\\bhd ?(\\d)"), " $1")            // « Bein Sport HD1 » = « beIN Sport 1 »
        p = p.replace("sports", "sport").replace("la chaine ", "").replace("canal plus", "canal+")
            // 2026-10-25 : damitv « Canal+ Formula 1 » = Mix FR « Canal+ F1 » ; « Sport360 » = « Sport 360 ».
            .replace(rx("\\bformula ?1\\b|\\bformule ?1\\b"), "f1").replace("sport360", "sport 360")
            // 2026-10-27 : Dric4rTV « BEIN MAX 4 » = « beIN Sports MAX 4 » (tuiles en double sinon).
            .replace(rx("\\bbein max\\b"), "bein sport max")
        p = p.replace(BRUIT, " ")
        return p.replace(rx("[^a-z0-9+]"), "").ifEmpty { plat(nomPropre).replace(rx("[^a-z0-9+]"), "") }
    }

    /** Clé d'un match, SANS l'heure : « Inter vs. Fiorentina · Serie A » = « … · 12:30 ». */
    private fun cleMatch(nomPropre: String): String =
        plat(nomPropre).replace(rx("\\b\\d{1,2}[:h]\\d{2}\\b"), " ")
            .replace(rx("[^a-z0-9+]+"), "_").trim('_').take(80)

    // ────────────────────────────────────────────────────────── Rubriques

    /** Matchs : rubrique par compétition. Ordre d'AFFICHAGE : le foot des grands championnats d'abord. */
    private val COMPETITIONS = listOf(
        "⚽ Ligue 1" to rx("ligue ?1|\\bl1\\b|ligue ?2|\\bl2\\b|coupe de france|trophee des champions"),
        "⚽ Liga" to rx("\\bliga\\b|laliga|espagne|copa del rey"),
        "⚽ Serie A" to rx("serie ?a\\b|calcio|italie|coppa italia"),
        "⚽ Premier League" to rx("premier league|\\bepl\\b|angleterre|fa cup|carabao"),
        "⚽ Bundesliga" to rx("bundesliga|allemagne|dfb"),
        "⚽ Coupes d'Europe" to rx("champions league|ligue des champions|\\bucl\\b|europa league|conference league|\\buel\\b|\\buecl\\b|\\bldc\\b"),
        "🏉 Rugby" to rx("rugby|top ?14|pro ?d2|six nations|6 nations|champions cup"),
        "🎾 Tennis" to rx("tennis|\\batp\\b|\\bwta\\b|roland|wimbledon|us open|masters 1000"),
        "🏀 Basket" to rx("basket|\\bnba\\b|euroleague|betclic elite|wnba|liga endesa|\\bacb\\b"),
        "🏈 Sports US" to rx("\\bnfl\\b|\\bnhl\\b|\\bmlb\\b|hockey|baseball|super bowl"),
        "🏎 Sports mécaniques" to rx("\\bf1\\b|formule|formula|moto ?gp|nascar|rally|\\bwrc\\b|indycar|\\bgt open|\\bgt cup|euroformula|\\btcr\\b"),
        "🥊 Sports de combat" to rx("\\bufc\\b|\\bmma\\b|boxe|boxing|\\bwwe\\b|catch|\\bpfl\\b|kickboxing|judo"),
        "🚴 Cyclisme" to rx("cyclisme|cycling|tour de france|vuelta|giro"),
        "⛳ Golf" to rx("golf|\\bpga\\b|ryder"),
        "🤾 Hand & volley" to rx("handball|\\bhand\\b|volley"),
    )
    /** Ordre de RECONNAISSANCE : les autres sports avant le foot (« Liga Endesa » = basket,
     *  « Champions Cup » = rugby), puis le foot. */
    private val COMPETITIONS_TEST = COMPETITIONS.drop(6) + COMPETITIONS.take(6)
    /** Foot féminin, jeunes ou d'un autre pays (« Ligue 1 - Burkina Faso », « Lesotho Premier
     *  League ») : rien à voir avec le championnat français/européen du même nom. */
    private val FOOT_AILLEURS = rx(
        "women|femminile|frauen|feminine|\\bu-?\\d{2}\\b|youth|reserve|burkina|niger|senegal|cameroun|cote d.?ivoire|" +
        "algerie|algeria|tunisie|tunisia|maroc|morocco|benin|\\bmali\\b|congo|gabon|guinee|guinea|togo|lesotho|sierra leone|" +
        "eswatini|cayman|kitts|tanzania|kenya|ghana|egypt|zambia|uganda|rwanda|ethiopia|jamaica|haiti|bolivia|ecuador|" +
        "peru|chile|colombia|venezuela|paraguay|uruguay|honduras|guatemala|panama|costa rica|salvador|india|bangladesh|" +
        "nepal|malaysia|indonesia|vietnam|thailand|singapore|hong kong|kuwait|qatar|bahrain|oman|jordan|lebanon|iraq|" +
        "iran|armenia|georgia|azerbaijan|kazakhstan|uzbekistan|belarus|ukraine|russia|moldova|estonia|latvia|lithuania|" +
        "wales|scotland|northern ireland|iceland|faroe|malta|cyprus|gibraltar|andorra|san marino|luxembourg|montenegro|" +
        "albania|kosovo|macedonia|bosnia|serbia|croatia|slovenia|slovakia|hungary|romania|bulgaria|greece|israel|" +
        "australia|new zealand|japan|korea|china|mexico|brazil|argentina|egypte|nigeria|" +
        // ligues étrangères qui ne nomment pas leur pays (« Liga MX », « Primeira Liga »…)
        "liga mx|primeira|portugal|betplay|\\bmls\\b|saudi|super lig|eredivisie|jupiler|scottish|allsvenskan|eliteserien|superliga|" +
        // 2026-10-25 (planning DaddyLive : « Czech Republic - 1. liga », « Germany - 3. Liga », « Poland - II Liga »)
        "czech|poland|austria|switzerland|denmark|sweden|norway|finland|turkey|belgium|netherlands|ireland|" +
        "\\b[2-9]\\. ?(liga|bundesliga)|\\bii liga|\\b2\\. bundesliga|segunda|serie b\\b|serie c\\b|efl\\b|league one|league two"
    )
    private const val AUTRES_MATCHS = "🏟 Autres matchs"

    /** Chaînes de sport mécanique (nom passé par [plat]). */
    private val MECA = rx("\\bf1\\b|formule ?1|formula ?1|moto ?gp|automoto|motorvision|motor ?racing|motorsport|" +
        "speedvision|speed sport|\\bturbo\\b|nascar|\\bwrc\\b")

    private val BOUQUETS = listOf(
        "📺 beIN SPORTS" to rx("bein"),
        "📺 Canal+ Sport" to rx("canal ?\\+|canal plus|\\bc\\+"),
        "📺 RMC Sport" to rx("rmc"),
        "📺 Eurosport" to rx("eurosport"),
        "📺 DAZN" to rx("dazn"),
        "📺 Ligue 1+" to rx("ligue ?1"),
        // 2026-10-28 (user + relais « Sports mécaniques quasi vide ») : les chaînes auto/moto
        //   (Canal+ F1, Automoto, F1 Channel, Motorvision…) étaient éparpillées entre Canal+ et
        //   « L'Équipe et autres ». ⚠ Toujours EN DERNIER : l'index sert d'identifiant (« b6 »).
        "📺 Chaînes auto-moto" to MECA,
        // 2026-10-25 : « Disney+, Prime, Max… » retiré — dossier vide (ses 14 chaînes étaient des
        //   « beIN SPORTS MAX », déjà rangées dans beIN), il ne s'ouvrait pas.
    )
    private const val AUTRES_CHAINES = "📺 L'Équipe et autres chaînes"
    private const val RENEVEO_AUTRES = "📡 RénéVéo : chaînes TV"

    private fun rubriqueMatch(nomPropre: String): String {
        val p = plat(nomPropre)
        val r = COMPETITIONS_TEST.firstOrNull { it.second.containsMatchIn(p) }?.first
            ?: return if (FOOT_AILLEURS.containsMatchIn(p)) FOOT_MONDE else AUTRES_MATCHS
        return if (r.startsWith("⚽") && FOOT_AILLEURS.containsMatchIn(p)) FOOT_MONDE else r
    }
    /** Foot des autres pays / féminin / jeunes : à part, pour ne pas noyer les grands championnats. */
    private const val FOOT_MONDE = "🌍 Foot d'ailleurs"

    private fun rubriqueChaine(nomPropre: String): String {
        val p = plat(nomPropre)
        // Auto-moto d'abord : « Canal+ F1 » va avec les chaînes auto-moto, pas avec Canal+.
        if (MECA.containsMatchIn(p)) return BOUQUETS.last().first
        return BOUQUETS.firstOrNull { it.second.containsMatchIn(p) }?.first ?: AUTRES_CHAINES
    }

    // 2026-10-28 (user : F1 rangée dans « Foot d'ailleurs ») : un sport hors foot sans chaîne
    //   française va dans sa rubrique « (VO) » (« 🏎 Sports mécaniques (VO) »), pas dans le foot.
    private val RUBRIQUES_VO: Map<String, String> =
        COMPETITIONS.map { it.first }.filterNot { it.startsWith("⚽") }.associateWith { "$it (VO)" }

    // ⚠ RUBRIQUES_VO ajoutées à la FIN : les index servent d'identifiants (« matchs~N », « tv~N »)
    //   et les insérer au milieu décalerait les dossiers déjà mis en favoris.
    private val ORDRE_RUBRIQUES: List<String> =
        COMPETITIONS.map { it.first } + AUTRES_MATCHS + FOOT_MONDE + BOUQUETS.map { it.first } + AUTRES_CHAINES + RENEVEO_AUTRES +
            com.streamflixreborn.streamflix.sport.providers.ChaineDirect.RUBRIQUES.values.distinct() + LIVE_MIX +
            RUBRIQUES_VO.values

    // ────────────────────────────────────────────────────────── Chargement

    /**
     * [brut] = nom à nettoyer (listes IPTV) ; false = nom déjà propre (sources web du module
     * sport/, dont la rubrique est donnée par [rubrique]). [live] = événement en direct.
     */
    private class Trouve(
        val proprio: String, val id: String, val nom: String, val logo: String, val sportif: Boolean,
        val brut: Boolean = true, val rubrique: String? = null, val live: Boolean = false,
        /** Événement web sans serveur en français (→ « Foot d'ailleurs » si aucune autre source n'en a). */
        val etranger: Boolean = false,
    )

    private const val LIBELLE_OLA = "OLA"
    private val LIBELLES = mapOf(
        "ola" to LIBELLE_OLA, "vegeta" to "Vegeta", "vavoo" to "Vavoo",
        "hub" to "TV Hub", "reneveo" to "RénéVéo", "mixfr" to "Mix FR", "fast" to "FAST",
        DricSport.PROPRIO to "Dric4rTV",
        DamiTv.ID to "DAMITV", DamiTvChaines.PROPRIO to "DAMITV",
        com.streamflixreborn.streamflix.sport.providers.StreamedPk.ID to "Streamed",
        com.streamflixreborn.streamflix.sport.providers.DaddyLive.ID to "DaddyLive",
        com.streamflixreborn.streamflix.sport.providers.ChaineDirect.ID to "ChaineDirect",
        // 2026-10-28 (user : ajouter xonaflix au dossier Sport) : matchs + chaînes, lecture native.
        XonaFlix.ID to "XonaFlix",
    )
    /** Ordre des serveurs d'une tuile : RénéVéo (sondé, rapide), les portails, puis les pages
     *  web du module sport/ (lues dans la WebView du grand lecteur, donc en dernier pour une
     *  chaîne qui a aussi des flux directs). */
    private val ORDRE_SOURCES = listOf("hub", "reneveo", DamiTvChaines.PROPRIO, "mixfr", "fast", DricSport.PROPRIO,
        // 2026-10-28 : XonaFlix, flux natif (comme DAMITV TV) → avant les sources WebView.
        XonaFlix.ID,
        "ola", "vegeta", "vavoo", com.streamflixreborn.streamflix.sport.providers.StreamedPk.ID,
        com.streamflixreborn.streamflix.sport.providers.DaddyLive.ID,
        // 2026-10-27 : ChaineDirect rejoint les bouquets (page web lue dans la WebView → avec les sources web).
        com.streamflixreborn.streamflix.sport.providers.ChaineDirect.ID, DamiTv.ID)
    private val SOURCES_WEB = setOf(DamiTv.ID, com.streamflixreborn.streamflix.sport.providers.StreamedPk.ID,
        com.streamflixreborn.streamflix.sport.providers.DaddyLive.ID, com.streamflixreborn.streamflix.sport.providers.ChaineDirect.ID)

    // ────────────────────────────────────────────────────────── Sous-dossiers (chargés au clic)

    /**
     * 2026-10-25 (user, sur la TCL : « le dossier Sport est trop lourd et peine à s'ouvrir, on
     * aurait dû faire comme les autres dossiers : un chargement qu'à l'endroit où on veut
     * aller ») — mesuré : 44 s à l'ouverture (≈2 400 chaînes de 7 sources traitées d'un coup).
     * L'ouverture n'affiche plus que ces sous-dossiers, SANS AUCUN chargement ; chacun ne
     * charge que les sources et les chaînes qui le concernent (un bouquet ne lit que les noms
     * qui le citent, « Nouvelles sources » ne lit que les sites web…).
     */
    private class SousDossier(val id: String, val titre: String, val logo: String? = null)
    // 2026-10-25 (user : « tu remets tout comme avant… que quand on ouvre le dossier ça charge
    //   pas tout d'un coup ; la nouvelle source, tu la mets dans un petit dossier à part ») :
    //   l'accueil montre les MÊMES rubriques qu'avant (Ligue 1, Liga… beIN, Canal+…), chacune
    //   en tuile chargée au clic. Les compétitions partagent un seul chargement (« matchs »,
    //   filtré par rubrique : `matchs~<n>`) ; damitv est rangé à part, en dernier.
    private val SOUS_DOSSIERS: List<SousDossier> by lazy {
        // 2026-10-28 (user : « 5 dossiers qui apparaissent dans Sport alors qu'ils sont déjà dans
        //   Nouvelles sources ») : plus AUCUNE tuile de matchs (Ligue 1, Bundesliga, Basket,
        //   Sports mécaniques, Autres matchs, Foot d'ailleurs…) — les matchs sont dans « Nouvelles
        //   sources ». Les id « matchs~N » restent ouvrables (anciens favoris) via collecter.
        BOUQUETS.mapIndexed { i, b -> SousDossier("b$i", b.first) } +
            SousDossier("autres", AUTRES_CHAINES) +
            SousDossier("web", NOM_DOSSIER_WEB)
        // 2026-10-27 (user) : « RénéVéo : chaînes TV » et « ChaineDirect » quittent Sport — leur
        //   contenu hors sport est dans le dossier Chaînes TV (cf. SOUS_DOSSIERS_TV), leur sport
        //   dans les bouquets. Les branches « reneveo » / « cdirect » de collecter restent pour
        //   rouvrir d'anciens favoris.
    }

    /** Rangée « Thèmes » du dossier Chaînes TV : un sous-dossier par thème (un seul chargement
     *  « tv », filtré par rubrique comme les matchs). */
    private val SOUS_DOSSIERS_TV_THEMES: List<SousDossier> by lazy {
        THEMES_TV.distinct().map { r -> SousDossier("tv~${ORDRE_RUBRIQUES.indexOf(r)}", r) }
    }

    /**
     * 2026-10-27 (user : « moins de dossiers, mieux rangé ») : rangée « Plateformes » du dossier
     * Chaînes TV. Live MIX est chargé ici (SportHub) ; les autres sont les dossiers existants du
     * TV Hub, qui quittent la rangée du haut : LiveHubFolderDialog redirige leur clé (cf.
     * [REDIRECTIONS_TV]) vers le dossier d'origine, chargé à l'ouverture — rien de plus en mémoire.
     */
    private val SOUS_DOSSIERS_TV_PLATEFORMES: List<SousDossier> by lazy {
        listOf(
            SousDossier("livemix", LIVE_MIX),
            SousDossier("s4f", "Stream4Free", "https://www.stream4free.tv/images/logos4f.png"),
            SousDossier("otf", "OTF TV"),
            SousDossier("samsung", "Samsung TV+",
                "https://stg-images.samsung.com/is/image/samsung/p5/ca/tvs/tvplus/Samsung_TVPlus_Logo_500x500.jpg"),
            SousDossier("pluto", "Pluto TV", "https://i.ibb.co/N6Nr80wR/Pluto-TV-logo-2024-svg.png"),
            SousDossier("plex", "Plex TV",
                "https://apps.homeycdn.net/app/tv.plex/25/db367d3f-957b-43c0-83e5-1ff357748210/assets/images/large.png"),
            SousDossier("lg", "LG Channels", "https://static.wikia.nocookie.net/wiggles/images/4/4f/LGChannels.png"),
            SousDossier(SOUS_DOSSIER_RAKUTEN, "🎬 Rakuten TV"),
            SousDossier(SOUS_DOSSIER_SONY, "📡 Sony One"),
            // 2026-10-28 (user, cf. RELAIS « CHAÎNES TV xonaflix ») : catégories NON sportives
            //   de xonaflix (~924 chaînes), plateforme à part — PAS de redirection : la grille
            //   des catégories vient de SportHub.accueilXonaFlix (cf. LiveHubFolderDialog).
            SousDossier(SOUS_DOSSIER_XONA, "📡 XonaFlix"),
        )
    }

    /**
     * 2026-10-27 (user : « garder le verrouillage des dossiers à l'intérieur de Chaînes TV — code
     * parental ») : clé de verrou d'une tuile-dossier. Une tuile de Chaînes TV qui ouvre un dossier
     * existant (Stream4Free, OTF…) partage SON verrou : un verrou déjà posé reste actif, et le poser
     * depuis la tuile verrouille le même dossier. Les autres gardent leur clé (thèmes, Live MIX…).
     */
    // 2026-10-27 : + petits dossiers de Mix FR (tuiles d'Autres Replays) → leur clé d'avant.
    fun cleVerrou(cle: String): String =
        if (cle.startsWith(PREFIXE_SOUS_DOSSIER)) cle.removePrefix(PREFIXE_SOUS_DOSSIER).let { id ->
            AutresReplaysTuiles.cleVerrou(id) ?: REDIRECTIONS_TV[id] ?: cle
        } else cle

    /** Libellé d'un sous-dossier Sport / Chaînes TV (pour la fenêtre du code parental). */
    fun titreSousDossier(cle: String): String? {
        val id = cle.removePrefix(PREFIXE_SOUS_DOSSIER)
        AutresReplaysTuiles.titre(id)?.let { return it }
        // 2026-10-28 : catégories de la plateforme XonaFlix (noms lus sur le site ; null si
        //   pas encore chargées → repli sur l'id, cf. TvHubFolderLockStore).
        if (id.startsWith(SOUS_DOSSIER_XONA + "_")) {
            XonaFlix.nomCategorie(id.removePrefix(SOUS_DOSSIER_XONA + "_"))?.let { return it }
        }
        return (SOUS_DOSSIERS + SOUS_DOSSIERS_TV_THEMES + SOUS_DOSSIERS_TV_PLATEFORMES).firstOrNull { it.id == id }?.titre
    }

    /** Sous-dossier de Chaînes TV → dossier du TV Hub qu'il ouvre (cf. LiveHubFolderDialog). */
    val REDIRECTIONS_TV: Map<String, String> = mapOf(
        "s4f" to "stream4cf", "otf" to "otf", "samsung" to "samsung_tvplus", "pluto" to "pluto_tv",
        "plex" to "plex_tv", "lg" to "lg_channels", SOUS_DOSSIER_RAKUTEN to "rakuten_tv", SOUS_DOSSIER_SONY to "sony_one",
    )

    /**
     * 2026-10-25 (user : « tant qu'il n'y a rien dedans, tu ne devrais pas afficher la tuile ») :
     * une tuile vide (« Premier League indisponible — réessaie ») n'est plus montrée. Les tuiles
     * de compétitions dépendent du chargement « matchs » : il est lancé EN FOND dès l'entrée dans
     * le TV Hub ([prechargerMatchs]) pour que le dossier Sport s'ouvre déjà trié ; sinon l'ouverture
     * l'attend ([accueilCharge]). Les autres tuiles (bouquets, RénéVéo, Nouvelles sources) restent
     * visibles tant qu'on ne sait pas qu'elles sont vides.
     */
    fun matchsPrets(): Boolean = contenus["matchs"]?.let { System.currentTimeMillis() - it.ts < TTL_MS } == true

    /** Lance le chargement des matchs en fond (entrée dans le TV Hub). Rien en mode léger. */
    fun prechargerMatchs() {
        // 2026-10-28 : plus de tuiles de matchs à l'accueil → rien à précharger.
        if (SOUS_DOSSIERS.none { it.id.startsWith("matchs") }) return
        if (UserPreferences.modeLeger || matchsPrets() || !enRafraichissement.add("matchs")) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { runCatching { contenu("matchs") } } finally { enRafraichissement.remove("matchs") }
        }
    }

    /** Accueil après chargement des matchs (attend celui en cours s'il y en a un). */
    suspend fun accueilCharge(): List<Category> {
        // 2026-10-25 (user : « l'ouverture du dossier général ne doit pas faire exploser le profil ») :
        //   on attend la liste des matchs 3 s au plus (mesuré 1,7 s ; 27 s quand damitv bloquait).
        //   Au-delà, l'accueil s'affiche avec toutes les tuiles et le chargement continue en fond.
        //   Le chargement tourne hors de cette attente : la dépasser ne l'annule pas.
        // 2026-10-28 : plus de tuiles de matchs à l'accueil → pas d'attente.
        if (SOUS_DOSSIERS.none { it.id.startsWith("matchs") }) return accueil()
        val charge = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { runCatching { contenu("matchs") } }
        withTimeoutOrNull(3_000L) { charge.await() }
        return accueil()
    }

    /** Rubriques non vides d'un sous-dossier chargé et frais ; null = pas (encore) chargé. */
    private fun rubriquesConnues(id: String): Set<String>? =
        contenuSiPret(id).takeIf { contenus[id] != null && System.currentTimeMillis() - contenus[id]!!.ts < TTL_MS }
            ?.filter { it.list.isNotEmpty() }?.map { it.name }?.toSet()

    /** Accueil du dossier Sport : la grille des sous-dossiers NON VIDES (inconnus = montrés). */
    fun accueil(): List<Category> = grille("Sport", SOUS_DOSSIERS, LOGO)

    /** 2026-10-27 : accueil du dossier Chaînes TV (thèmes + Live MIX), rien n'est chargé à l'ouverture. */
    // 2026-10-27 (user : « tout dans le même dossier, et pouvoir rester appuyé sur n'importe lequel
    //   pour le verrouiller ») : UNE seule grille — thèmes puis plateformes à la suite (les deux
    //   rangées « Thèmes » / « Plateformes » passaient par un autre rendu, sans appui long).
    fun accueilTv(): List<Category> =
        grille("Chaînes TV", SOUS_DOSSIERS_TV_THEMES + SOUS_DOSSIERS_TV_PLATEFORMES, LOGO_TV)

    /**
     * 2026-10-28 (user, cf. RELAIS « CHAÎNES TV xonaflix ») : grille des catégories du site,
     * plateforme XonaFlix du dossier Chaînes TV. Tuiles-dossiers `sportdos_xona_<clé>` chargées
     * au clic ; chaînes NON sportives uniquement (le Sport est déjà dans le dossier Sport) et
     * JAMAIS fusionnées avec les chaînes françaises des autres sources (étiquettes de pays du
     * site non fiables, cf. le tri du Sport du 2026-10-28).
     */
    fun accueilXonaFlixSiPret(): List<Category> =
        XonaFlix.categoriesPretes()?.let { grilleXonaFlix(it) } ?: emptyList()

    suspend fun accueilXonaFlix(): List<Category> = grilleXonaFlix(XonaFlix.categories())

    private fun grilleXonaFlix(cats: List<XonaFlix.Categorie>): List<Category> =
        listOf(Category(name = "XonaFlix", list = cats.map { c ->
            TvShow(id = "livehub::folder::$PREFIXE_SOUS_DOSSIER${SOUS_DOSSIER_XONA}_${c.cle}", title = c.nom).apply {
                providerName = "TV Hub"
                poster = LOGO_TV
                banner = LOGO_TV
            }
        }))

    private fun grille(nom: String, dossiers: List<SousDossier>, logo: String): List<Category> =
        listOf(Category(name = nom, list = dossiers.filter { d ->
            val base = d.id.substringBefore('~')
            val connues = rubriquesConnues(base)
            when {
                // 2026-10-27 (user : « tu as réaffiché des dossiers vides — ils ne doivent apparaître
                //   que s'il y a du contenu ») : une tuile de COMPÉTITION (matchs~n) n'est montrée
                //   qu'une fois les matchs connus. Mesuré sur la TV : chargement 3,3 s > 3 s
                //   d'attente → Ligue 1 / Liga vides affichées. Les autres tuiles (bouquets…)
                //   restent montrées tant qu'on ne sait pas.
                connues == null -> !(d.id.contains('~') && base == "matchs")
                d.id.contains('~') -> d.titre in connues
                else -> connues.isNotEmpty()
            }
        }.map { d ->
            TvShow(id = "livehub::folder::$PREFIXE_SOUS_DOSSIER${d.id}", title = d.titre).apply {
                providerName = "TV Hub"
                poster = d.logo ?: logo
                banner = d.logo ?: logo
            }
        }))

    /** Au-delà, le sous-dossier est rechargé EN FOND (les matchs changent vite) ; l'affichage
     *  n'attend pas : il montre la liste en main, la suivante sert à l'ouverture d'après. */
    private const val RAFRAICHIR_APRES_MS = 2 * 60_000L
    private class Contenu(val cats: List<Category>, val ts: Long)
    private val contenus = java.util.concurrent.ConcurrentHashMap<String, Contenu>()
    private val verrous = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val enRafraichissement = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()


    /**
     * 2026-10-25 (user : « pourquoi quand on va dans Matchs du jour la liste n'est pas comme les
     * autres ») : un sous-dossier à plusieurs rubriques (Matchs du jour, Nouvelles sources)
     * s'affichait en liste de texte, les autres en grille de tuiles. Ses rubriques deviennent
     * des TUILES-DOSSIERS (`sportdos_<id>~<n° de rubrique>`), comme partout ailleurs.
     */
    private fun presenter(id: String, cats: List<Category>): List<Category> {
        if (cats.isEmpty()) return cats
        val rubrique = id.substringAfter('~', "")
        if (rubrique.isNotEmpty()) {
            val nom = ORDRE_RUBRIQUES.getOrNull(rubrique.toIntOrNull() ?: -1) ?: return emptyList()
            return cats.filter { it.name == nom }
        }
        if (cats.size == 1) return cats
        val titre = SOUS_DOSSIERS.firstOrNull { it.id == id }?.titre ?: "Sport"
        return listOf(Category(name = titre, list = cats.map { c ->
            TvShow(id = "livehub::folder::$PREFIXE_SOUS_DOSSIER$id~${ORDRE_RUBRIQUES.indexOf(c.name)}",
                title = "${c.name} (${c.list.size})").apply {
                providerName = "TV Hub"
                poster = LOGO
                banner = LOGO
            }
        }))
    }

    /** Contenu d'un sous-dossier déjà prêt (affichage immédiat), sinon vide. */
    fun sousDossierSiPret(cleDossier: String): List<Category> {
        val complet = cleDossier.removePrefix(PREFIXE_SOUS_DOSSIER)
        return presenter(complet, contenuSiPret(complet.substringBefore('~')))
    }

    private fun contenuSiPret(id: String): List<Category> {
        val cleDossier = PREFIXE_SOUS_DOSSIER + id
        val c = contenus[id] ?: return emptyList()
        val age = System.currentTimeMillis() - c.ts
        if (age >= TTL_MS) return emptyList()
        if (age > RAFRAICHIR_APRES_MS && enRafraichissement.add(id)) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try { sousDossier(cleDossier, forcer = true) } finally { enRafraichissement.remove(id) }
            }
        }
        return c.cats
    }

    /** Charge (si besoin) puis rend le contenu d'un sous-dossier. */
    suspend fun sousDossier(cleDossier: String, forcer: Boolean = false): List<Category> {
        val complet = cleDossier.removePrefix(PREFIXE_SOUS_DOSSIER)
        return presenter(complet, contenu(complet.substringBefore('~'), forcer))
    }

    private suspend fun contenu(id: String, forcer: Boolean = false): List<Category> = withContext(Dispatchers.IO) {
        if (com.streamflixreborn.streamflix.BuildConfig.DEBUG) {
            runCatching { SportSelfTest.lancerUneFois() }
            // Audit des lecteurs (fichier drapeau posé par adb) : tourne en fond, une fois.
            com.streamflixreborn.streamflix.sport.SportAudit.drapeau()?.takeIf { it.exists() }?.let { f ->
                // Contenu du drapeau (facultatif) = filtre sur le nom des tuiles (expression régulière).
                val filtre = runCatching { f.readText().trim() }.getOrDefault("")
                if (!f.delete()) return@let
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    runCatching { com.streamflixreborn.streamflix.sport.SportAudit.lancer(filtre) }
                        .onFailure { Log.e("SportAudit", "audit interrompu", it) }
                }
            }
        }
        if (!forcer) contenuSiPret(id).takeIf { it.isNotEmpty() }?.let { return@withContext it }
        verrous.getOrPut(id) { Mutex() }.withLock {
            if (!forcer) contenus[id]?.takeIf { System.currentTimeMillis() - it.ts < TTL_MS }?.let { return@withLock it.cats }
            val debut = System.currentTimeMillis()
            val trouves = collecter(id)
            if (trouves.isEmpty()) {
                // 2026-10-25 : « rien trouvé » est un résultat (ex. aucune chaîne de match dans les
                //   sources du TV Hub) : on le retient, sinon l'accueil croit ne pas savoir et
                //   affiche les tuiles vides. Une liste précédente non vide reste servie.
                contenus[id]?.cats?.takeIf { it.isNotEmpty() }?.let { return@withLock it }
                contenus[id] = Contenu(emptyList(), System.currentTimeMillis())
                Log.i(TAG, "sous-dossier $id : rien trouvé (${System.currentTimeMillis() - debut} ms)")
                return@withLock emptyList()
            }
            val cats = construire(id, trouves)
            contenus[id] = Contenu(cats, System.currentTimeMillis())
            Log.i(TAG, "sous-dossier $id : ${trouves.size} trouvées → " +
                cats.joinToString { "${it.name}=${it.list.size}" } + " (${System.currentTimeMillis() - debut} ms)")
            cats
        }
    }

    /** 2026-10-25 : JAMAIS de Zone 18@ dans Sport — une vidéo de ce dossier dont le titre contenait
     *  « football » passait le filtre et s'affichait dans « L'Équipe et autres chaînes ». On écarte
     *  le dossier (nom de catégorie) et ses liens (`adulttv://`). */
    private fun estZone18(texte: String?): Boolean =
        texte != null && (texte.contains("zone 18", ignoreCase = true) || texte.contains("adulttv", ignoreCase = true))

    /** Chaîne damitv à ranger dans les bouquets sport (Canal+ France diffuse la Ligue 1, la F1…). */
    private fun estSportDami(nom: String): Boolean = estSport(nom) || plat(nom).contains("canal")

    /** Clé de rapprochement d'une chaîne TV hors sport (« TF1 » RénéVéo = « TF1 France » damitv). */
    private fun cleTv(nom: String): String =
        plat(nom).replace(rx("\\s+france$"), "").replace(rx("[^a-z0-9+]"), "")

    /** Un nom qui ressemble à un match (avant tout nettoyage) : sert à ne traiter, pour
     *  « Matchs du jour », que ces lignes-là parmi les ~2 400. */
    private val RESSEMBLE_MATCH = rx("\\bvs?\\b\\.?|\\||\\b\\d{1,2}[:h]\\d{2}\\b|\\s[-–]\\s.*\\s[-–]\\s|@", RegexOption.IGNORE_CASE)

    private suspend fun collecter(id: String): List<Trouve> = coroutineScope {
        val bouquet = if (id.startsWith("b")) id.removePrefix("b").toIntOrNull()?.let { BOUQUETS.getOrNull(it) } else null
        // Filtre sur le nom BRUT, avant tout traitement : seules les chaînes qui peuvent finir
        //   dans CE sous-dossier sont gardées.
        val garder: (String) -> Boolean = when {
            bouquet != null -> { n -> estSport(n) && bouquet.second.containsMatchIn(plat(n)) }
            id == "autres" -> { n -> estSport(n) && BOUQUETS.none { it.second.containsMatchIn(plat(n)) } }
            else -> { n -> estSport(n) && RESSEMBLE_MATCH.containsMatchIn(n) } // matchs
        }
        val leger = UserPreferences.modeLeger
        // Mode léger : une source à la fois (registres OLA/Vegeta/Vavoo + Mix FR = gros en mémoire).
        // 2026-10-27 : idem sur les appareils à moins de 3 Go (TV TCL 2,4 Go : ~500 Mo libres) —
        //   les gros registres un par un, pour ne pas les cumuler en mémoire au même instant.
        val porte = Semaphore(if (leger || MemoireAppareil.juste) 1 else 3)
        // 2026-10-28 (cf. RELAIS « CHAÎNES TV xonaflix ») : variante avec un délai propre
        //   (une catégorie XonaFlix pagine jusqu'à ~21 pages). Nom distinct : deux fonctions
        //   locales de même nom ne se surchargent pas en Kotlin.
        suspend fun sourceDelai(nom: String, delai: Long, bloc: suspend () -> List<Trouve>): List<Trouve> = porte.withPermit {
            val debut = System.currentTimeMillis()
            val r = runCatching { withTimeoutOrNull(delai) { bloc() } }
                .onFailure { Log.w(TAG, "$nom KO : ${it.message}") }.getOrNull().orEmpty()
            Log.i(TAG, "$nom : ${r.size} chaînes sport (${System.currentTimeMillis() - debut} ms)")
            r
        }
        suspend fun source(nom: String, bloc: suspend () -> List<Trouve>): List<Trouve> =
            sourceDelai(nom, DELAI_SOURCE_MS, bloc)

        // 2026-10-28 (user, cf. RELAIS « CHAÎNES TV xonaflix ») : une catégorie de la
        //   plateforme XonaFlix (`xona_<clé>`, ouverte depuis la grille
        //   SportHub.accueilXonaFlix). Tuiles À ELLES, jamais fusionnées (cf. construire) ;
        //   chargement au clic uniquement, cache 30 min côté XonaFlix. Traité AVANT le
        //   `when (id)` (qui ne compare qu'à des valeurs fixes).
        if (id == SOUS_DOSSIER_XONA) return@coroutineScope emptyList()
        if (id.startsWith(SOUS_DOSSIER_XONA + "_")) {
            val cleCategorie = id.removePrefix(SOUS_DOSSIER_XONA + "_")
            val rubrique = XonaFlix.nomCategorie(cleCategorie)
            return@coroutineScope sourceDelai("XonaFlix${rubrique?.let { " · $it" } ?: ""}", DELAI_SOURCE_TV_MS) {
                XonaFlix.chainesTv(cleCategorie).map { c ->
                    Trouve(XonaFlix.ID, c.slug, c.nom, c.logo, false, brut = false, rubrique = rubrique)
                }
            }
        }
        fun depuis(proprio: String, l: List<TvShow>) =
            l.map { Trouve(proprio, it.id, it.title ?: "", it.poster ?: "", true) }
        val taches = when (id) {
            // Module sport/ : événements damitv, isolés dans « Nouvelles sources » (cf. SportProviders).
            "web" -> listOf(
                async {
                    source("Web · événements") {
                        SportProviders.evenements().map { ev ->
                            // 2026-10-25 (user : « tu gardes tout, mais tout ce qui n'est pas français,
                            //   lorsque tu penses qu'il n'est pas français, dans Foot d'ailleurs »).
                            //   La rubrique réelle est gardée : une autre source du même match peut
                            //   avoir du français (cf. construire).
                            Trouve(ev.provider, "ev::${ev.id}", nomEvenement(ev), ev.poster, true,
                                brut = false, rubrique = rubriqueEvenement(ev),
                                live = ev.enDirect, etranger = !ev.francais)
                        }
                    }
                },
                // 2026-10-28 (user : ajouter xonaflix) : ses matchs rejoignent « Nouvelles sources »
                //   comme les autres événements web, mais la lecture est NATIVE (cf. XonaFlix).
                async { source("XonaFlix matchs") { trouvesXonaFlix() } },
            )
            // chainedirect.com : toutes ses chaînes, rangées par rubrique du site (cf. ChaineDirect).
            "cdirect" -> listOf(async {
                source("ChaineDirect") {
                    // 2026-10-27 : la rubrique Sport part dans les bouquets (cf. collecter, branche bouquets) ;
                    //   ici il ne reste que le hors-sport (Généraliste, Info, Cinéma…).
                    com.streamflixreborn.streamflix.sport.providers.ChaineDirect.getStreams()
                        .filter { it.category != "sport" }.map { ev ->
                        Trouve(ev.provider, "ev::${ev.id}", ev.title, ev.poster, true, brut = false,
                            rubrique = com.streamflixreborn.streamflix.sport.providers.ChaineDirect.rubrique(ev), live = true)
                    }
                }
            })
            // 2026-10-27 (user) : dossier Chaînes TV — tout le direct hors sport, par thème.
            //   Toutes les sources sont fusionnées par nom (cf. construire) ; Mix FR n'apporte que
            //   les groupes de ces thèmes, et reste lui-même intact.
            "tv" -> listOf(
                async {
                    source("TV Hub") {
                        LiveTvHubProvider.chainesHubHorsSport().mapNotNull { (cat, t) ->
                            val th = themeDe(cat) ?: return@mapNotNull null
                            Trouve("hub", t.id, t.title ?: "", t.poster ?: "", false, brut = false, rubrique = th)
                        }
                    }
                },
                async {
                    source("RénéVéo") {
                        ReneveoTv.chaines().ifEmpty { ReneveoTv.chainesSiDejaChargees() }
                            .filter { it.categorie != "sport" && !estSport(it.nom) }
                            .map { c -> Trouve("reneveo", "${ReneveoTv.PREFIX}${c.id}", c.nom, c.logo, false,
                                rubrique = themeDe(c.categorie) ?: themeTv("generaliste")) }
                    }
                },
                async {
                    source("DAMITV TV") {
                        DamiTvChaines.chaines().filter { !estSportDami(it.nom) }
                            .map { c -> Trouve(DamiTvChaines.PROPRIO, "${DamiTvChaines.PREFIX}${c.id}", c.nom, c.logo, false,
                                rubrique = themeTv("generaliste")) }
                    }
                },
                async {
                    source("ChaineDirect") {
                        com.streamflixreborn.streamflix.sport.providers.ChaineDirect.getStreams()
                            .filter { it.category != "sport" && !estSport(it.title) }
                            .map { ev -> Trouve(ev.provider, "ev::${ev.id}", ev.title, ev.poster, false, brut = false,
                                rubrique = com.streamflixreborn.streamflix.sport.providers.ChaineDirect.rubrique(ev), live = false) }
                    }
                },
                async {
                    source("Mix FR") {
                        LiveTvHubProvider.fetchMixFrCategoriesPublic().asSequence()
                            .filter { cat -> !estZone18(cat.name) }
                            .mapNotNull { cat -> themeDe(cat.name)?.let { th -> cat to th } }
                            .flatMap { (cat, th) ->
                                cat.list.asSequence().filterIsInstance<TvShow>()
                                    .filter { t -> !estZone18(t.id) && !estSport(t.title ?: "") }
                                    .map { t -> Trouve("mixfr", t.id, t.title ?: "", t.poster ?: "", false, rubrique = th) }
                            }
                            .distinctBy { it.id }.toList()
                    }
                },
            )
            // 2026-10-27 (user : « Live MIX, ce sont des chaînes qu'on ajoute avec la communauté ») :
            //   le groupe Live MIX de Mix FR, en petit sous-dossier de Chaînes TV.
            "livemix" -> listOf(async {
                source("Live MIX") {
                    LiveTvHubProvider.fetchMixFrCategoriesPublic()
                        .filter { cat -> plat(cat.name).trim() == "live mix" }
                        .flatMap { cat -> cat.list.filterIsInstance<TvShow>() }
                        .filter { t -> !estZone18(t.id) }
                        .map { t -> Trouve("mixfr", t.id, t.title ?: "", t.poster ?: "", false, rubrique = LIVE_MIX) }
                        .distinctBy { it.id }
                }
            })
            // Chaînes RénéVéo hors sport (TF1, M6…) : liste embarquée, instantanée.
            "reneveo" -> listOf(async {
                source("RénéVéo") {
                    ReneveoTv.chaines().ifEmpty { ReneveoTv.chainesSiDejaChargees() }
                        .filter { it.categorie != "sport" && !estSport(it.nom) }
                        .map { c -> Trouve("reneveo", "${ReneveoTv.PREFIX}${c.id}", c.nom, c.logo, false) }
                }
            }, async {
                // damitv « Télévision en direct » (TF1, France 2, M6…) : rejoint la tuile RénéVéo
                //   de même nom quand elle existe (cf. construire).
                source("DAMITV TV") {
                    DamiTvChaines.chaines().filter { !estSportDami(it.nom) }
                        .map { c -> Trouve(DamiTvChaines.PROPRIO, "${DamiTvChaines.PREFIX}${c.id}", c.nom, c.logo, false) }
                }
            })
            else -> listOfNotNull(
                // 2026-10-27 (user : « je suis censé avoir un dossier qui réapparaît au moment où il y a du
                //   contenu dedans ») : les tuiles de compétitions (Ligue 1, Liga…) ne se remplissaient
                //   qu'avec les chaînes IPTV nommées comme un match (RénéVéo, Mix FR, FAST) — souvent
                //   aucune → tuiles toujours cachées, alors que les VRAIS plannings (DaddyLive, Streamed,
                //   damitv : match + heure + chaînes) n'allaient que dans « Nouvelles sources ». Ils
                //   alimentent maintenant aussi les compétitions (même liste en cache, pas de 2e appel).
                if (id == "matchs") async {
                    source("Web · matchs") {
                        SportProviders.evenements().map { ev ->
                            Trouve(ev.provider, "ev::${ev.id}", nomEvenement(ev), ev.poster, true,
                                brut = false, rubrique = rubriqueEvenement(ev),
                                live = ev.enDirect, etranger = !ev.francais)
                        }
                    }
                } else null,
                // 2026-10-28 : les matchs xonaflix alimentent aussi les tuiles de compétitions
                //   (même cache que « Nouvelles sources », pas de 2e appel réseau).
                if (id == "matchs") async { source("XonaFlix matchs") { trouvesXonaFlix() } } else null,
                async {
                    source("RénéVéo") {
                        ReneveoTv.chaines().ifEmpty { ReneveoTv.chainesSiDejaChargees() }
                            .filter { (it.categorie == "sport" || estSport(it.nom)) && garder(if (estSport(it.nom)) it.nom else "sport ${it.nom}") }
                            .map { c -> Trouve("reneveo", "${ReneveoTv.PREFIX}${c.id}", c.nom, c.logo, true) }
                    }
                },
                // 2026-10-25 (user : « virer tout ce qui vient de Vegeta, Vavoo et OLA » — ils ont leur
                //   propre profil — « et condenser ce qu'il y a dans le TV Hub ») : plus d'OLA / Vegeta /
                //   Vavoo ici ; les chaînes sport du TV Hub lui-même : RénéVéo (ci-dessus), Mix FR (dont
                //   Multi Live) et les chaînes FAST (Samsung TV+, Pluto, Plex, LG, Rakuten…).
                async {
                    source("Mix FR") {
                        LiveTvHubProvider.fetchMixFrCategoriesPublic().asSequence()
                            .filter { cat -> !estZone18(cat.name) }
                            .flatMap { cat -> cat.list.asSequence().filterIsInstance<TvShow>() }
                            // Sur le NOM seulement : la rubrique « sport » de Mix FR mélange BFM, RMC Découverte…
                            .filter { t -> !estZone18(t.id) && garder(t.title ?: "") }
                            .map { t -> Trouve("mixfr", t.id, t.title ?: "", t.poster ?: "", true) }
                            .distinctBy { it.id }.take(400).toList()
                    }
                },
                async {
                    source("FAST") {
                        LiveTvHubProvider.fetchFastCategoriesPublic().asSequence()
                            .filter { cat -> !estZone18(cat.name) }
                            .flatMap { cat -> cat.list.asSequence().filterIsInstance<TvShow>() }
                            .filter { t -> !estZone18(t.id) && garder(t.title ?: "") }
                            .map { t -> Trouve("fast", t.id, t.title ?: "", t.poster ?: "", true) }
                            .distinctBy { it.id }.take(300).toList()
                    }
                },
                // 2026-10-28 (user : Vavoo en serveur de secours, PAS OLA ni Vegeta) : seulement à
                //   l'ouverture d'un bouquet ; les noms perdent le suffixe Vavoo (« .s », « .b »)
                //   pour tomber sur la même clé que la tuile existante (cf. construire).
                if (bouquet == null) null else async {
                    source("Vavoo") {
                        VavooProvider.chainesQui({ n -> garder(n) }, max = 120)
                            .map { t -> Trouve("vavoo", t.id,
                                (t.title ?: "").replace(rx("\\s+\\.[a-z]{1,2}$"), ""), t.poster ?: "", true) }
                    }
                },
                // 2026-10-25 (user : « intégrer les chaînes France de damitv, Télévision en direct,
                //   onglet France ») : flux HLS directs (segments déguisés en PNG, cf. SauteImageDataSource).
                // 2026-10-27 (user : « tu as réaffiché des dossiers vides dans le dossier Sport ») : les
                //   3 sources ci-dessous ne donnent que des NOMS DE CHAÎNES, jamais un match → inutiles
                //   pour « matchs ». Mesuré sur la TV : elles faisaient passer ce chargement de ~4 à
                //   8,6 s, au-delà des 3 s qu'attend l'accueil, qui montrait alors toutes les tuiles
                //   de compétitions, vides comprises.
                if (id == "matchs") null else async {
                    source("DAMITV TV") {
                        DamiTvChaines.chaines().filter { estSportDami(it.nom) }
                            .filter { c -> garder(if (estSport(c.nom)) c.nom else "sport ${c.nom}") }
                            .map { c -> Trouve(DamiTvChaines.PROPRIO, "${DamiTvChaines.PREFIX}${c.id}", c.nom, c.logo, true) }
                    }
                },
                // 2026-10-27 (user : « pourquoi tu n'as pas trié ces chaînes dans les bons dossiers ») :
                //   la rubrique « Sport » de chainedirect.com (beIN, Canal+, RMC, L'Équipe…) était coincée
                //   dans le sous-dossier ChaineDirect. Elle alimente maintenant les bouquets comme les
                //   autres sources : même nom = même tuile, ChaineDirect devient un serveur de plus.
                if (id == "matchs") null else async {
                    source("ChaineDirect sport") {
                        com.streamflixreborn.streamflix.sport.providers.ChaineDirect.getStreams()
                            .filter { it.category == "sport" }
                            .filter { ev -> garder(if (estSport(ev.title)) ev.title else "sport ${ev.title}") }
                            .map { ev -> Trouve(ev.provider, "ev::${ev.id}", ev.title, ev.poster, true, brut = false) }
                    }
                },
                // 2026-10-27 (user : « les chaînes de sport de Dric4rTV arrivent-elles dans mon dossier
                //   Sport ? » → non → « ajoute ») : beIN, RMC Sport, Eurosport, Canal+ Sport, ESPN…
                //   Même nom = même tuile, Dric4rTV devient un serveur de plus (cf. DricSport).
                if (id == "matchs") null else async {
                    source("Dric4rTV") {
                        DricSport.chaines()
                            .filter { c -> garder(if (estSport(c.nom)) c.nom else "sport ${c.nom}") }
                            .map { c -> Trouve(DricSport.PROPRIO, c.id, c.nom, c.logo, true) }
                    }
                },
                // 2026-10-28 (user : ajouter xonaflix) : chaînes fixes du catalogue Sport de
                //   xonaflix — même nom = même tuile que DAMITV / ChaineDirect / Dric4rTV
                //   (cf. construire), XonaFlix devient un serveur de plus, en lecture native.
                if (id == "matchs") null else async {
                    source("XonaFlix chaînes") {
                        XonaFlix.chaines()
                            .filter { c -> garder(if (estSport(c.nom)) c.nom else "sport ${c.nom}") }
                            .map { c -> Trouve(XonaFlix.ID, c.slug, c.nom, "", true, brut = false) }
                    }
                },
            )
        }
        taches.awaitAll().flatten()
    }

    /** « Inter Milan vs Fiorentina · Serie A · 18:00 » (l'heure seulement s'il n'a pas commencé). */
    private fun nomEvenement(ev: SportEvent): String {
        val heure = if (!ev.enDirect && ev.startsAt != null)
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(ev.startsAt)) else null
        // Pas de ligue quand damitv recopie la catégorie (« NFL Network · american-football »).
        val ligue = ev.league.takeIf {
            it.isNotBlank() && !ev.title.contains(it, ignoreCase = true) && !it.equals(ev.category, ignoreCase = true)
        }
        return listOfNotNull(ev.title, ligue, heure).joinToString(" · ")
    }

    private val HIPPIQUE = rx("\\bat the races\\b|dunstall|romford|churchill downs|horse|hippi|trot|galop|turf|greyhound|lingfield|kempton|wolverhampton|newcastle racing")

    /** Rubrique d'un événement web : le foot par compétition, le reste par sport. */
    private fun rubriqueEvenement(ev: SportEvent): String {
        val cat = plat(ev.category)
        return when {
            cat.contains("football") && !cat.contains("american") || cat.contains("soccer") ->
                rubriqueMatch("${ev.title} · ${ev.league}")
            cat.contains("rugby") || cat.contains("aussie") -> "🏉 Rugby"
            cat.contains("tennis") -> "🎾 Tennis"
            cat.contains("basket") || cat.contains("nba") -> "🏀 Basket"
            cat.contains("american") || cat.contains("nfl") || cat.contains("hockey") || cat.contains("baseball") -> "🏈 Sports US"
            // 2026-10-28 : « racing » couvre aussi les courses de chevaux (DaddyLive : « Live racing
            //   from Dunstall Park », « America's Day At the Races ») → pas en sport mécanique.
            HIPPIQUE.containsMatchIn(plat("${ev.title} ${ev.league}")) -> AUTRES_MATCHS
            cat.contains("motor") || cat.contains("f1") || cat.contains("racing") -> "🏎 Sports mécaniques"
            cat.contains("fight") || cat.contains("box") || cat.contains("mma") || cat.contains("ufc") -> "🥊 Sports de combat"
            cat.contains("cycl") -> "🚴 Cyclisme"
            cat.contains("golf") -> "⛳ Golf"
            cat.contains("hand") || cat.contains("volley") -> "🤾 Hand & volley"
            else -> rubriqueMatch("${ev.title} · ${ev.league}")
        }
    }

    /** 2026-10-28 : tuiles des matchs xonaflix (« Nouvelles sources » et compétitions) —
     *  id « m::<slug> » (préfixe « ev:: » réservé aux sources WebView, cf. serveursWeb). */
    private suspend fun trouvesXonaFlix(): List<Trouve> = XonaFlix.matchs().map { ev ->
        Trouve(XonaFlix.ID, "m::${ev.id}", nomEvenement(ev), ev.poster, true,
            brut = false, rubrique = rubriqueEvenement(ev),
            live = ev.enDirect, etranger = !ev.francais)
    }

    /**
     * 2026-10-25 — tri du dossier Sport fait AVEC le user, chaîne par chaîne (fichier
     * outils/captures/tri_sport.txt) : « on supprime les chaînes HS, et ce qui n'est pas français
     * va dans Foot d'ailleurs ». Noms comparés sans accents ni majuscules.
     */
    private val CHAINES_HS = rx(
        // 2026-10-27 (user : « beIN Sports 1 Arabic ne fonctionne pas, en plus il est arabe ») : versions arabes.
        "\\barab(ic|e|ia)?\\b|" +
        // 2026-10-27 (test du user, dossier Canal+ Sport, 18 chaînes) — vérifiées mortes à la source :
        //   Canal+ Sport 1…5 Afrique (DAMITV : 404), Canal+ Sport 2 et 3 (ChaineDirect : « serveur
        //   temporairement inaccessible » sur le site), Canal+ Live 1 (le lecteur du site ne démarre
        //   pas), Canal+ Sport Poland (aucune vidéo sur le site, et pas en français).
        "^canal ?\\+ sport \\d afrique|^canal ?\\+ sport [23]\\b|^canal ?\\+ live 1\\b|^canal ?\\+ sport poland|" +
        "^rmc talk sport|^dazn women|^sportitalia|^a spor\\b|^arryadia|^cctv|^ssc 1\\b|^tennis ?\\+|^tennis tv\\b|" +
            "^real madrid tv|^mutv|^region \\d+\\b|^racing 8\\b|^l ?equipe live \\d|" +
        // 2026-10-28 (user : « il doit y avoir plein d'autres trucs comme ça à masquer ») — audit
        //   automatique (SportAudit, Oppo), seulement les mortes QUELLE QUE SOIT la source : image
        //   fixe à la place de la vidéo, ou 403/404. Rapport : outils/captures/sport_audit.txt.
        "^barca tv spain|^nbc sports (boston|california)|^rds canada|" +
        "^la chaine l ?equipe en direct|^trt spor|^journal du golf"
    )
    private val CHAINES_HS_EXACTES = setOf("rmcsport") // tuile RénéVéo « RMC Sport » (3105) : ses 3 serveurs KO
    private val CHAINES_ETRANGERES = rx(
        "^fox sports|^sportsgrid|^tnt sports|^trace sport stars|^tennis channel|^nfl channel|^espn\\b"
    )
    /** 2026-10-28 (user : « DAZN 1 UK aussi », « DAZN Pro League 1 BE, c'est pas français ») :
     *  versions étrangères de bouquets français — sortent du bouquet, vers « Foot d'ailleurs ». */
    private val VERSIONS_ETRANGERES = rx("\\s(uk|be|bel|it|es|de|pt)$|\\bpro league\\b")

    /** Construit les tuiles d'un sous-dossier et rend ses rubriques. */
    private fun construire(idDossier: String, trouves: List<Trouve>): List<Category> {
        class Accu(val cle: String, var nom: String, var logo: String, var rubrique: String) {
            val sources = LinkedHashSet<Source>()
            var live = false
            /** Au moins une source en français (ou une source non web). */
            var francais = false
        }
        /** Rubrique « vague » : remplacée dès qu'une autre source du match en donne une précise. */
        fun vague(r: String) = r == AUTRES_MATCHS || r == FOOT_MONDE
        val heure = rx("\\b\\d{1,2}[:h]\\d{2}\\b")
        val parCle = LinkedHashMap<String, Accu>()
        /** Chaînes TV hors sport : clé de nom → clé de tuile (RénéVéo d'abord, cf. ordre ci-dessous). */
        val parTv = HashMap<String, String>()
        // RénéVéo avant damitv, pour que damitv rejoigne la tuile RénéVéo existante.
        val estTv = idDossier in IDS_TV
        // Chaînes TV : les sources aux noms propres d'abord (TV Hub, RénéVéo) — la tuile garde le
        //   premier nom rencontré ; Mix FR (« FR| TF1 FHD ») vient en dernier.
        val ordre = if (estTv) trouves.sortedBy { ORDRE_SOURCES.indexOf(it.proprio).let { i -> if (i < 0) 99 else i } }
            else trouves.sortedBy { when (it.proprio) { DamiTvChaines.PROPRIO -> 1; "vavoo" -> 2; else -> 0 } }
        for (t in ordre) {
            if (t.nom.isBlank()) continue
            val nom = when {
                estTv -> nomTv(t.nom)
                !t.brut -> t.nom
                t.sportif -> nettoyer(t.nom) ?: continue
                else -> propre(t.nom)
            }
            // Un match se reconnaît à ses deux équipes quel que soit l'ordre ou l'écriture
            //   (« Fiorentina - Inter Milan » = « Inter Milan vs Fiorentina · Serie A ») : les
            //   serveurs de toutes les sources se retrouvent sur la même tuile.
            fun cleMatchEquipes(n: String) = SportProviders.cleEquipes(n)?.let { "m_eq_$it" } ?: ("m_" + cleMatch(n))
            val (cle, rubrique) = when {
                // 2026-10-28 (user, cf. RELAIS « CHAÎNES TV xonaflix ») : plateforme XonaFlix
                //   de Chaînes TV — tuiles À ELLES (`c_xona_`, comme les chaînes Sport xonaflix
                //   mais dans LEUR dossier), jamais fusionnées avec les chaînes françaises des
                //   autres sources : les étiquettes de pays du site ne sont pas fiables.
                idDossier.startsWith(SOUS_DOSSIER_XONA + "_") && t.proprio == XonaFlix.ID ->
                    "c_xona_" + cleChaine(nom) to (t.rubrique ?: "XonaFlix")
                // 2026-10-27 : Chaînes TV — une tuile par chaîne, toutes sources confondues.
                estTv -> cleTuileTv(nom) to
                    (t.rubrique ?: themeTv("generaliste"))
                // damitv : rejoint la tuile RénéVéo de même nom (TF1, France 2…), sinon la sienne.
                !t.sportif && t.proprio == DamiTvChaines.PROPRIO ->
                    (parTv[cleTv(nom)] ?: ("rv_dt_" + t.id.removePrefix(DamiTvChaines.PREFIX))) to RENEVEO_AUTRES
                !t.sportif -> ("rv_" + t.id.removePrefix(ReneveoTv.PREFIX)).also { parTv.putIfAbsent(cleTv(nom), it) } to RENEVEO_AUTRES
                t.rubrique != null -> cleMatchEquipes(nom) to t.rubrique
                estMatch(nom) -> cleMatchEquipes(nom) to rubriqueMatch(nom)
                // 2026-10-28 (user : « les quatre live ne sont pas français », puis « fais le tri par
                //   toi-même ») : seules les chaînes xonaflix à l'audio VÉRIFIÉ français
                //   (XonaFlix.FRANCAIS) rejoignent les bouquets ; les autres ont leurs tuiles À ELLES
                //   (jamais fusionnées avec beIN/DAZN français), rangées dans « Foot d'ailleurs ».
                t.proprio == XonaFlix.ID && t.id !in XonaFlix.FRANCAIS -> "c_xona_" + cleChaine(nom) to AUTRES_CHAINES
                VERSIONS_ETRANGERES.containsMatchIn(plat(nom).trim()) -> "c_" + cleChaine(nom) to AUTRES_CHAINES
                else -> "c_" + cleChaine(nom) to rubriqueChaine(nom)
            }
            if (cle.startsWith("c_")) {
                val p = plat(nom).trim()
                if (CHAINES_HS.containsMatchIn(p) || cle.removePrefix("c_") in CHAINES_HS_EXACTES) continue
            }
            // Chaîne étrangère : rangée dans « Foot d'ailleurs » du dossier « L'Équipe et autres chaînes ».
            val etrangere = cle.startsWith("c_") && rubrique == AUTRES_CHAINES &&
                ((t.proprio == XonaFlix.ID && t.id !in XonaFlix.FRANCAIS) || CHAINES_ETRANGERES.containsMatchIn(plat(nom).trim()) ||
                    VERSIONS_ETRANGERES.containsMatchIn(plat(nom).trim()))
            val rubriqueFinale = if (etrangere) FOOT_MONDE else rubrique
            // Chaque sous-dossier ne garde que ce qui lui revient (un bouquet : ses chaînes
            //   fixes ; « Matchs du jour » : les matchs ; « Nouvelles sources » : tout).
            val aGarder = when {
                // 2026-10-28 : catégorie XonaFlix — tout ce que la catégorie contient est à elle.
                idDossier.startsWith(SOUS_DOSSIER_XONA + "_") -> true
                estTv || idDossier == "web" || idDossier == "reneveo" || idDossier == "cdirect" -> true
                idDossier == "matchs" -> cle.startsWith("m_")
                else -> cle.startsWith("c_") && rubrique == (SOUS_DOSSIERS.firstOrNull { it.id == idDossier }?.titre)
            }
            if (!aGarder) continue
            // 2026-10-28 (user : « ça devient un serveur supplémentaire, ça va ») : Vavoo ne crée
            //   JAMAIS de tuile, il s'ajoute seulement comme serveur de secours à une tuile existante.
            if (t.proprio == "vavoo" && cle !in parCle) continue
            val a = parCle.getOrPut(cle) { Accu(cle, nom, t.logo, rubriqueFinale) }
            // Une source donne la compétition (DaddyLive : « Spain - La Liga »), une autre le français
            //   (streamed : « French - DAZN ») : la tuile garde le meilleur des deux.
            if (!cle.startsWith("c_") && vague(a.rubrique) && !vague(rubrique)) a.rubrique = rubrique
            if (!t.etranger) a.francais = true
            if (t.live) a.live = true
            if (a.logo.isBlank() && t.logo.isNotBlank()) a.logo = t.logo
            // Nom le plus lisible : on préfère celui qui n'est pas tout en majuscules.
            if (a.nom == a.nom.uppercase() && nom != nom.uppercase()) a.nom = nom
            // Match : on garde le nom qui donne l'heure.
            if (cle.startsWith("m_") && !heure.containsMatchIn(a.nom) && heure.containsMatchIn(nom)) a.nom = nom
            a.sources += Source(t.proprio, t.id)
        }
        // 2026-10-28 (user : « la formule 1 n'apparaît pas », trouvée dans « Foot d'ailleurs ») :
        //   sans chaîne française, TOUT partait dans FOOT_MONDE — F1, tennis, rugby… Choix du user :
        //   le non-français reste à part, mais PAR SPORT : le foot étranger dans FOOT_MONDE, les
        //   autres dans « <rubrique> (VO) » (« 🏎 Sports mécaniques (VO) »). Les rubriques normales
        //   ne gardent que le français.
        val nouvelles = parCle.values.map { a ->
            val rubriqueFinale = when {
                a.francais -> a.rubrique
                !a.cle.startsWith("c_") && a.rubrique in RUBRIQUES_VO -> RUBRIQUES_VO.getValue(a.rubrique)
                else -> FOOT_MONDE
            }
            Entree(a.cle, a.nom, a.logo, rubriqueFinale,
                a.sources.sortedBy { ORDRE_SOURCES.indexOf(it.proprio) }, a.live)
        }
        // Tuiles de CE sous-dossier remplacées, celles des autres gardées.
        val prefixe = "$idDossier::"
        entrees = entrees.filterKeys { !it.startsWith(prefixe) } + nouvelles.associateBy { prefixe + it.cle }
        val parRubrique = nouvelles.groupBy { it.rubrique }
        // 2026-10-28 : les rubriques des catégories XonaFlix viennent du site (inconnues à la
        //   compilation) → celles absentes d'ORDRE_RUBRIQUES s'affichent À LA FIN, dans l'ordre
        //   du site, au lieu de disparaître.
        val ordreRubriques = ORDRE_RUBRIQUES + parRubrique.keys.filter { it !in ORDRE_RUBRIQUES }
        val cats = ordreRubriques.mapNotNull { r ->
            val liste = parRubrique[r] ?: return@mapNotNull null
            // Matchs EN DIRECT d'abord. Chaînes : les « … Sport(s) N » d'abord (beIN SPORTS 1
            //   avant beIN MAX, Canal+ Sport avant Canal+ Foot). Puis ordre naturel.
            //   Clés de tri calculées UNE fois par tuile (pas à chaque comparaison).
            // 2026-10-27 : Chaînes TV — ordre TNT puis thématique (cf. ORDRE_TV), puis alphabétique.
            class Cle(val e: Entree, val direct: Int, val horsSport: Int, val rang: Int, val texte: String)
            val tri = compareBy<Cle>({ it.direct }, { it.horsSport }, { it.rang }).thenBy(NaturalOrder) { it.texte }
            Category(name = r, list = liste.map { e ->
                Cle(e, if (e.live) 0 else 1,
                    if (e.cle.startsWith("c_") && !plat(e.nom).contains("sport")) 1 else 0,
                    if (estTv) rangTv(e.cle) else 0,
                    cleTri(e.nom))
            }.sortedWith(tri).map { tuile(idDossier, it.e) })
        }
        cats.forEach { c -> Log.d(TAG, "  [$idDossier] ${c.name} : " + c.list.take(25).joinToString { (it as TvShow).title ?: "" }) }
        return cats
    }

    /** « Bein Sport HD1 » se range comme « beIN SPORTS 1 ». */
    private fun cleTri(nom: String): String =
        plat(nom).replace("sports", "sport").replace(rx("\\bf?i?hd ?(\\d)"), "$1").replace(rx("\\s+"), " ")

    /** Tri « naturel » : beIN 2 avant beIN 10. */
    private object NaturalOrder : Comparator<String> {
        private val bloc = rx("\\d+|\\D+")
        override fun compare(a: String, b: String): Int {
            val x = bloc.findAll(a.lowercase()).map { it.value }.toList()
            val y = bloc.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val nx = x[i].toBigIntegerOrNull(); val ny = y[i].toBigIntegerOrNull()
                val c = if (nx != null && ny != null) nx.compareTo(ny) else x[i].compareTo(y[i])
                if (c != 0) return c
            }
            return x.size - y.size
        }
    }

    /** Id de tuile : `livehub::sport::<sous-dossier>::<clé>` — le sous-dossier permet de le
     *  recharger seul si la tuile est rouverte plus tard (favori, après redémarrage). */
    private fun tuile(idDossier: String, e: Entree): TvShow = TvShow(id = "$PREFIX$idDossier::${e.cle}", title = e.nom).apply {
        providerName = "TV Hub"
        poster = e.logo.ifBlank { LOGO }
        banner = e.logo.ifBlank { LOGO }
    }

    // ────────────────────────────────────────────────────────── Lecture

    fun estTuile(id: String) = id.startsWith(PREFIX)

    /** Id de serveur → source qui l'a donné (pour getVideo). */
    private val proprios = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Entrée d'une tuile ; recharge le catalogue si besoin (favori rouvert après redémarrage). */
    private suspend fun entree(id: String): Entree? {
        val cle = id.removePrefix(PREFIX)
        entrees[cle]?.let { return it }
        val dossier = cle.substringBefore("::", "")
        if (dossier.isBlank()) return null
        runCatching { sousDossier(PREFIXE_SOUS_DOSSIER + dossier) }
        entrees[cle]?.let { return it }
        // Tuile d'avant un changement de liste (match fini…) : rien à lire.
        return null
    }

    suspend fun fiche(id: String): TvShow {
        val e = entree(id)
        val logo = e?.logo?.ifBlank { LOGO } ?: LOGO
        return TvShow(id = id, title = e?.nom ?: "Sport").apply {
            providerName = "TV Hub"
            poster = logo
            banner = logo
        }.copy(
            seasons = listOf(
                com.streamflixreborn.streamflix.models.Season(
                    id = id, number = 1, title = "En Direct",
                    episodes = listOf(
                        com.streamflixreborn.streamflix.models.Episode(
                            id = id, number = 1, title = "Regarder en Direct", poster = logo,
                        ),
                    ),
                ),
            ),
        )
    }

    /** Serveurs de toutes les sources de la tuile, demandés en parallèle, préfixés par la source. */
    suspend fun serveurs(id: String, type: Video.Type): List<Video.Server> = coroutineScope {
        val e = entree(id) ?: run { Log.w(TAG, "tuile inconnue : $id"); return@coroutineScope emptyList() }
        val parSource = e.sources.map { s ->
            async(Dispatchers.IO) {
                val libelle = LIBELLES[s.proprio] ?: s.proprio
                val brut = runCatching {
                    withTimeoutOrNull(DELAI_SERVEURS_MS) {
                        when (s.proprio) {
                            "ola" -> OlaTvProvider.getServers(s.id, type)
                            "vegeta" -> VegetaTvProvider.getServers(s.id, type)
                            "vavoo" -> VavooProvider.getServers(s.id, type)
                            in SOURCES_WEB -> serveursWeb(s)
                            DamiTvChaines.PROPRIO -> listOf(DamiTvChaines.serveur(s.id, "Direct"))
                            DricSport.PROPRIO -> DricSport.serveurs(s.id)
                            // 2026-10-28 : xonaflix — serveur rendu sans appel réseau (résolution
                            //   northlive à la lecture, cf. XonaFlix.video).
                            XonaFlix.ID -> XonaFlix.serveurs(s.id)
                            else -> LiveTvHubProvider.getServers(s.id, type) // RénéVéo, Mix FR
                        }
                    }
                }.onFailure { Log.w(TAG, "${e.nom} / $libelle KO : ${it.message}") }.getOrNull().orEmpty()
                if (proprios.size > 2_000) proprios.clear()
                brut.forEach { proprios[it.id] = s.proprio }
                brut.map { srv ->
                    if (srv.name.contains(libelle, ignoreCase = true)) srv
                    else srv.copy(name = "$libelle · ${srv.name}")
                }
            }
        }
        val vus = HashSet<String>()
        // 2026-10-25 (user : « la chaîne que tu viens de lancer n'est pas française ») : serveurs
        //   français d'abord, langues étrangères en dernier (tri stable : l'ordre des sources reste).
        val tous = parSource.awaitAll().flatten().filter { vus.add(it.src.ifBlank { it.id }) }
            .sortedBy { rangLangue(it.name) }
        Log.i(TAG, "${e.nom} : ${tous.size} serveurs (${e.sources.joinToString { it.proprio }})")
        tous
    }

    private val SERVEUR_FRANCAIS = rx("fran[cç]ais|french|france|\\bfr\\b|\\bvf\\b|ligue ?1\\+", RegexOption.IGNORE_CASE)
    private val SERVEUR_ETRANGER = rx(
        "arab|spanish|espa[nñ]ol|espagnol|english|anglais|italian|italien|german|allemand|portug|poland|polska|" +
            "dutch|swedish|czech|turk|greek|serbia|croat|hungar|romania|\\b(usa|us|uk|de|es|it|pt|pl|nl|ar|tr)\\b|" +
            "espn|sky sport|fox|tnt|cbs|nbc|\\babc\\b|eleven|viaplay|nova|sport tv|astro|supersport|kayo|willow",
        RegexOption.IGNORE_CASE,
    )

    /** 0 = serveur en français, 1 = langue inconnue (« Server 1 », « HD »), 2 = étranger. */
    fun rangLangue(nom: String): Int = when {
        SERVEUR_FRANCAIS.containsMatchIn(nom) -> 0
        SERVEUR_ETRANGER.containsMatchIn(nom) -> 2
        else -> 1
    }

    /** Serveur d'une tuile Sport dans une autre langue : jamais mémorisé comme « dernier bon ». */
    fun serveurEtranger(idContenu: String, nomServeur: String?): Boolean =
        estTuile(idContenu) && nomServeur != null && rangLangue(nomServeur) == 2

    // ── Débogage (SportAudit) ──
    internal suspend fun toutesLesTuiles(): Map<String, Entree> {
        SOUS_DOSSIERS.map { it.id.substringBefore('~') }.distinct().forEach { runCatching { contenu(it) } }
        return entrees
    }
    internal fun idTuile(cle: String) = PREFIX + cle
    internal fun estSourceWeb(proprio: String) = proprio in SOURCES_WEB
    internal suspend fun serveursWebAudit(s: Source) = serveursWeb(s)

    /** Serveurs d'une source web du module sport/ (événement damitv). */
    private suspend fun serveursWeb(s: Source): List<Video.Server> {
        val idEv = s.id.removePrefix("ev::")
        fun cherche() = SportProviders.derniereListe(s.proprio)
            .firstOrNull { it.provider == s.proprio && it.id == idEv }
        val ev = cherche() ?: run { runCatching { SportProviders.evenements() }; cherche() }
            ?: return emptyList()
        return SportProviders.serveursEvenement(ev)
    }

    /** Lecture d'un serveur web du module sport/ (flux capté en arrière-plan, lu par le
     *  lecteur ONYX). Null = ce n'est pas un serveur web. */
    suspend fun videoWeb(server: Video.Server): Video? {
        // 2026-10-28 : xonaflix → lecture NATIVE (m3u8 northlive + Referer), pas de WebView.
        if (XonaFlix.estServeur(server)) return XonaFlix.video(server)
        if (DamiTvChaines.estServeur(server)) return DamiTvChaines.video(server)
        if (!SportProviders.estServeurWeb(server)) return null
        return SportProviders.video(server) ?: throw Exception("Aucun flux pour ${server.name}")
    }

    /**
     * Source qui sait lire ce serveur, d'après le préfixe de son id (les serveurs gardent l'id de
     * leur source). Null = un serveur du TV Hub lui-même (RénéVéo, Mix FR…).
     */
    fun lecteurDe(server: Video.Server): Provider? {
        // D'abord ce que serveurs() a noté (couvre aussi les ids « Mon IPTV » de Vavoo).
        when (proprios[server.id]) {
            "ola" -> return OlaTvProvider
            "vegeta" -> return VegetaTvProvider
            "vavoo" -> return VavooProvider
        }
        if (server.id.startsWith("lazy::vavoo::")) return VavooProvider
        // Préfixes EXACTS : « olavodep:: » / « vegetavodep:: » (VOD du TV Hub) ne sont pas à eux.
        val prefixe = server.id.substringBefore("::", "")
        return when (prefixe) {
            "ola", "ola_stream", "ola_fasttrack", "olahub" -> OlaTvProvider
            "vegeta", "vegeta_stream" -> VegetaTvProvider
            "vavoo" -> VavooProvider
            else -> null
        }
    }
}
