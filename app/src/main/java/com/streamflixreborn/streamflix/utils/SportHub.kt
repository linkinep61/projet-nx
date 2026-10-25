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
import com.streamflixreborn.streamflix.sport.providers.SportProviders
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
    const val LOGO = "https://cdn-icons-png.flaticon.com/512/857/857455.png"
    private const val TTL_MS = 20 * 60 * 1000L
    private const val DELAI_SOURCE_MS = 20_000L
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
        "multiplex|olympi"
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
        "liga mx|primeira|portugal|betplay|\\bmls\\b|saudi|super lig|eredivisie|jupiler|scottish|allsvenskan|eliteserien|superliga"
    )
    private const val AUTRES_MATCHS = "🏟 Autres matchs"

    /** Chaînes fixes : rubrique par bouquet. */
    private val BOUQUETS = listOf(
        "📺 beIN SPORTS" to rx("bein"),
        "📺 Canal+ Sport" to rx("canal ?\\+|canal plus|\\bc\\+"),
        "📺 RMC Sport" to rx("rmc"),
        "📺 Eurosport" to rx("eurosport"),
        "📺 DAZN" to rx("dazn"),
        "📺 Ligue 1+" to rx("ligue ?1"),
        "📺 Disney+, Prime, Max…" to rx("disney|prime|\\bmax\\b|apple"),
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
        return BOUQUETS.firstOrNull { it.second.containsMatchIn(p) }?.first ?: AUTRES_CHAINES
    }

    private val ORDRE_RUBRIQUES: List<String> =
        COMPETITIONS.map { it.first } + AUTRES_MATCHS + FOOT_MONDE + BOUQUETS.map { it.first } + AUTRES_CHAINES + RENEVEO_AUTRES

    // ────────────────────────────────────────────────────────── Chargement

    /**
     * [brut] = nom à nettoyer (listes IPTV) ; false = nom déjà propre (sources web du module
     * sport/, dont la rubrique est donnée par [rubrique]). [live] = événement en direct.
     */
    private class Trouve(
        val proprio: String, val id: String, val nom: String, val logo: String, val sportif: Boolean,
        val brut: Boolean = true, val rubrique: String? = null, val live: Boolean = false,
    )

    private const val LIBELLE_OLA = "OLA"
    private val LIBELLES = mapOf(
        "ola" to LIBELLE_OLA, "vegeta" to "Vegeta", "vavoo" to "Vavoo",
        "reneveo" to "RénéVéo", "mixfr" to "Mix FR",
        DamiTv.ID to "DAMITV",
    )
    /** Ordre des serveurs d'une tuile : RénéVéo (sondé, rapide), les portails, puis les pages
     *  web du module sport/ (lues dans la WebView du grand lecteur, donc en dernier pour une
     *  chaîne qui a aussi des flux directs). */
    private val ORDRE_SOURCES = listOf("reneveo", "ola", "vegeta", "vavoo", "mixfr", DamiTv.ID)
    private val SOURCES_WEB = setOf(DamiTv.ID)

    // ────────────────────────────────────────────────────────── Sous-dossiers (chargés au clic)

    /**
     * 2026-10-25 (user, sur la TCL : « le dossier Sport est trop lourd et peine à s'ouvrir, on
     * aurait dû faire comme les autres dossiers : un chargement qu'à l'endroit où on veut
     * aller ») — mesuré : 44 s à l'ouverture (≈2 400 chaînes de 7 sources traitées d'un coup).
     * L'ouverture n'affiche plus que ces sous-dossiers, SANS AUCUN chargement ; chacun ne
     * charge que les sources et les chaînes qui le concernent (un bouquet ne lit que les noms
     * qui le citent, « Nouvelles sources » ne lit que les sites web…).
     */
    private class SousDossier(val id: String, val titre: String)
    // 2026-10-25 (user : « tu remets tout comme avant… que quand on ouvre le dossier ça charge
    //   pas tout d'un coup ; la nouvelle source, tu la mets dans un petit dossier à part ») :
    //   l'accueil montre les MÊMES rubriques qu'avant (Ligue 1, Liga… beIN, Canal+…), chacune
    //   en tuile chargée au clic. Les compétitions partagent un seul chargement (« matchs »,
    //   filtré par rubrique : `matchs~<n>`) ; damitv est rangé à part, en dernier.
    private val SOUS_DOSSIERS: List<SousDossier> by lazy {
        (COMPETITIONS.map { it.first } + AUTRES_MATCHS + FOOT_MONDE).map { r ->
            SousDossier("matchs~${ORDRE_RUBRIQUES.indexOf(r)}", r)
        } +
            BOUQUETS.mapIndexed { i, b -> SousDossier("b$i", b.first) } +
            SousDossier("autres", AUTRES_CHAINES) + SousDossier("reneveo", RENEVEO_AUTRES) +
            SousDossier("web", NOM_DOSSIER_WEB)
    }

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
        if (UserPreferences.modeLeger || matchsPrets() || !enRafraichissement.add("matchs")) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { runCatching { contenu("matchs") } } finally { enRafraichissement.remove("matchs") }
        }
    }

    /** Accueil après chargement des matchs (attend celui en cours s'il y en a un). */
    suspend fun accueilCharge(): List<Category> {
        runCatching { contenu("matchs") }
        return accueil()
    }

    /** Rubriques non vides d'un sous-dossier chargé et frais ; null = pas (encore) chargé. */
    private fun rubriquesConnues(id: String): Set<String>? =
        contenuSiPret(id).takeIf { contenus[id] != null && System.currentTimeMillis() - contenus[id]!!.ts < TTL_MS }
            ?.filter { it.list.isNotEmpty() }?.map { it.name }?.toSet()

    /** Accueil du dossier Sport : la grille des sous-dossiers NON VIDES (inconnus = montrés). */
    fun accueil(): List<Category> = listOf(Category(name = "Sport", list = SOUS_DOSSIERS.filter { d ->
        val base = d.id.substringBefore('~')
        val connues = rubriquesConnues(base)
        when {
            connues == null -> true
            d.id.contains('~') -> d.titre in connues
            else -> connues.isNotEmpty()
        }
    }.map { d ->
        TvShow(id = "livehub::folder::$PREFIXE_SOUS_DOSSIER${d.id}", title = d.titre).apply {
            providerName = "TV Hub"
            poster = LOGO
            banner = LOGO
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
            if (trouves.isEmpty()) return@withLock contenus[id]?.cats.orEmpty()
            val cats = construire(id, trouves)
            contenus[id] = Contenu(cats, System.currentTimeMillis())
            Log.i(TAG, "sous-dossier $id : ${trouves.size} trouvées → " +
                cats.joinToString { "${it.name}=${it.list.size}" } + " (${System.currentTimeMillis() - debut} ms)")
            cats
        }
    }

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
        val porte = Semaphore(if (leger) 1 else 3)
        suspend fun source(nom: String, bloc: suspend () -> List<Trouve>): List<Trouve> = porte.withPermit {
            val debut = System.currentTimeMillis()
            val r = runCatching { withTimeoutOrNull(DELAI_SOURCE_MS) { bloc() } }
                .onFailure { Log.w(TAG, "$nom KO : ${it.message}") }.getOrNull().orEmpty()
            Log.i(TAG, "$nom : ${r.size} chaînes sport (${System.currentTimeMillis() - debut} ms)")
            r
        }
        fun depuis(proprio: String, l: List<TvShow>) =
            l.map { Trouve(proprio, it.id, it.title ?: "", it.poster ?: "", true) }
        val taches = when (id) {
            // Module sport/ : événements damitv, isolés dans « Nouvelles sources » (cf. SportProviders).
            "web" -> listOf(
                async {
                    source("Web · événements") {
                        SportProviders.evenements().map { ev ->
                            Trouve(ev.provider, "ev::${ev.id}", nomEvenement(ev), ev.poster, true,
                                brut = false, rubrique = rubriqueEvenement(ev), live = ev.enDirect)
                        }
                    }
                },
            )
            // Chaînes RénéVéo hors sport (TF1, M6…) : liste embarquée, instantanée.
            "reneveo" -> listOf(async {
                source("RénéVéo") {
                    ReneveoTv.chaines().ifEmpty { ReneveoTv.chainesSiDejaChargees() }
                        .filter { it.categorie != "sport" && !estSport(it.nom) }
                        .map { c -> Trouve("reneveo", "${ReneveoTv.PREFIX}${c.id}", c.nom, c.logo, false) }
                }
            })
            else -> listOf(
                async {
                    source("RénéVéo") {
                        ReneveoTv.chaines().ifEmpty { ReneveoTv.chainesSiDejaChargees() }
                            .filter { (it.categorie == "sport" || estSport(it.nom)) && garder(if (estSport(it.nom)) it.nom else "sport ${it.nom}") }
                            .map { c -> Trouve("reneveo", "${ReneveoTv.PREFIX}${c.id}", c.nom, c.logo, true) }
                    }
                },
                // 1 500 max : beaucoup sont écartées ensuite (autres pays, matchs d'un autre jour).
                async { source("OLA") { depuis("ola", OlaTvProvider.chainesQui(garder, 1_500)) } },
                async { source("Vegeta") { depuis("vegeta", VegetaTvProvider.chainesQui(garder, 1_500)) } },
                async { source("Vavoo") { depuis("vavoo", VavooProvider.chainesQui(garder, 1_500)) } },
                async {
                    source("Mix FR") {
                        LiveTvHubProvider.fetchMixFrCategoriesPublic().asSequence()
                            .flatMap { cat -> cat.list.asSequence().filterIsInstance<TvShow>() }
                            // Sur le NOM seulement : la rubrique « sport » de Mix FR mélange BFM, RMC Découverte…
                            .filter { t -> garder(t.title ?: "") }
                            .map { t -> Trouve("mixfr", t.id, t.title ?: "", t.poster ?: "", true) }
                            .distinctBy { it.id }.take(400).toList()
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
            cat.contains("motor") || cat.contains("f1") || cat.contains("racing") -> "🏎 Sports mécaniques"
            cat.contains("fight") || cat.contains("box") || cat.contains("mma") || cat.contains("ufc") -> "🥊 Sports de combat"
            cat.contains("cycl") -> "🚴 Cyclisme"
            cat.contains("golf") -> "⛳ Golf"
            cat.contains("hand") || cat.contains("volley") -> "🤾 Hand & volley"
            else -> rubriqueMatch("${ev.title} · ${ev.league}")
        }
    }

    /** Construit les tuiles d'un sous-dossier et rend ses rubriques. */
    private fun construire(idDossier: String, trouves: List<Trouve>): List<Category> {
        class Accu(val cle: String, var nom: String, var logo: String, val rubrique: String) {
            val sources = LinkedHashSet<Source>()
            var live = false
        }
        val heure = rx("\\b\\d{1,2}[:h]\\d{2}\\b")
        val parCle = LinkedHashMap<String, Accu>()
        for (t in trouves) {
            if (t.nom.isBlank()) continue
            val nom = when {
                !t.brut -> t.nom
                t.sportif -> nettoyer(t.nom) ?: continue
                else -> propre(t.nom)
            }
            // Un match se reconnaît à ses deux équipes quel que soit l'ordre ou l'écriture
            //   (« Fiorentina - Inter Milan » = « Inter Milan vs Fiorentina · Serie A ») : les
            //   serveurs de toutes les sources se retrouvent sur la même tuile.
            fun cleMatchEquipes(n: String) = SportProviders.cleEquipes(n)?.let { "m_eq_$it" } ?: ("m_" + cleMatch(n))
            val (cle, rubrique) = when {
                !t.sportif -> "rv_" + t.id.removePrefix(ReneveoTv.PREFIX) to RENEVEO_AUTRES
                t.rubrique != null -> cleMatchEquipes(nom) to t.rubrique
                estMatch(nom) -> cleMatchEquipes(nom) to rubriqueMatch(nom)
                else -> "c_" + cleChaine(nom) to rubriqueChaine(nom)
            }
            // Chaque sous-dossier ne garde que ce qui lui revient (un bouquet : ses chaînes
            //   fixes ; « Matchs du jour » : les matchs ; « Nouvelles sources » : tout).
            val aGarder = when {
                idDossier == "web" || idDossier == "reneveo" -> true
                idDossier == "matchs" -> cle.startsWith("m_")
                else -> cle.startsWith("c_") && rubrique == (SOUS_DOSSIERS.firstOrNull { it.id == idDossier }?.titre)
            }
            if (!aGarder) continue
            val a = parCle.getOrPut(cle) { Accu(cle, nom, t.logo, rubrique) }
            if (t.live) a.live = true
            if (a.logo.isBlank() && t.logo.isNotBlank()) a.logo = t.logo
            // Nom le plus lisible : on préfère celui qui n'est pas tout en majuscules.
            if (a.nom == a.nom.uppercase() && nom != nom.uppercase()) a.nom = nom
            // Match : on garde le nom qui donne l'heure.
            if (cle.startsWith("m_") && !heure.containsMatchIn(a.nom) && heure.containsMatchIn(nom)) a.nom = nom
            a.sources += Source(t.proprio, t.id)
        }
        val nouvelles = parCle.values.map { a ->
            Entree(a.cle, a.nom, a.logo, a.rubrique, a.sources.sortedBy { ORDRE_SOURCES.indexOf(it.proprio) }, a.live)
        }
        // Tuiles de CE sous-dossier remplacées, celles des autres gardées.
        val prefixe = "$idDossier::"
        entrees = entrees.filterKeys { !it.startsWith(prefixe) } + nouvelles.associateBy { prefixe + it.cle }
        val parRubrique = nouvelles.groupBy { it.rubrique }
        val cats = ORDRE_RUBRIQUES.mapNotNull { r ->
            val liste = parRubrique[r] ?: return@mapNotNull null
            // Matchs EN DIRECT d'abord. Chaînes : les « … Sport(s) N » d'abord (beIN SPORTS 1
            //   avant beIN MAX, Canal+ Sport avant Canal+ Foot). Puis ordre naturel.
            //   Clés de tri calculées UNE fois par tuile (pas à chaque comparaison).
            class Cle(val e: Entree, val direct: Int, val horsSport: Int, val texte: String)
            val tri = compareBy<Cle>({ it.direct }, { it.horsSport }).thenBy(NaturalOrder) { it.texte }
            Category(name = r, list = liste.map { e ->
                Cle(e, if (e.live) 0 else 1,
                    if (e.cle.startsWith("c_") && !plat(e.nom).contains("sport")) 1 else 0,
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
        val tous = parSource.awaitAll().flatten().filter { vus.add(it.src.ifBlank { it.id }) }
        Log.i(TAG, "${e.nom} : ${tous.size} serveurs (${e.sources.joinToString { it.proprio }})")
        tous
    }

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
        fun cherche() = DamiTv.derniereListe()
            .firstOrNull { it.provider == s.proprio && it.id == idEv }
        val ev = cherche() ?: run { runCatching { SportProviders.evenements() }; cherche() }
            ?: return emptyList()
        return SportProviders.serveursEvenement(ev)
    }

    /** Lecture d'un serveur web du module sport/ (flux capté en arrière-plan, lu par le
     *  lecteur ONYX). Null = ce n'est pas un serveur web. */
    suspend fun videoWeb(server: Video.Server): Video? {
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
