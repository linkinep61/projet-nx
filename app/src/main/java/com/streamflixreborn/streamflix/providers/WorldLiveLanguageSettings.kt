package com.streamflixreborn.streamflix.providers

import android.content.Context

/**
 * 2026-06-08 (user "au lieu de virer les non-FR, on va mettre un filtre de
 * langues comme dans Vavoo") : settings persistant pour le filtre de langue
 * de World Live. Stocké en SharedPrefs.
 */
object WorldLiveLanguageSettings {

    private const val PREFS = "world_live_language"
    private const val KEY_CODE = "code"

    data class Language(
        val code: String,        // "all", "fr", "en", "es", etc.
        val label: String,       // "Toutes", "Français", "English", etc.
        val flag: String,        // "🌐", "🇫🇷", "🇬🇧", etc.
        /** Mots-clés à chercher dans group-title / nom / tvg-language. */
        val keywords: List<String>,
    )

    // 2026-06-08 (user "Français en haut, Toutes en bas") : Français en
    //   premier (= défaut), Toutes en dernier.
    val list: List<Language> = listOf(
        Language("fr",   "Français",   "🇫🇷", listOf("fr", "france", "french", "française", "francais")),
        Language("en",   "English",    "🇬🇧", listOf("en", "english", "uk", "us", "usa", "united states", "united kingdom")),
        Language("es",   "Español",    "🇪🇸", listOf("es", "spanish", "español", "espana", "españa", "spain")),
        Language("de",   "Deutsch",    "🇩🇪", listOf("de", "german", "deutsch", "germany")),
        Language("it",   "Italiano",   "🇮🇹", listOf("it", "italian", "italiano", "italia", "italy")),
        Language("pt",   "Português",  "🇵🇹", listOf("pt", "portuguese", "português", "portugal", "brasil", "brazil")),
        Language("ar",   "العربية",    "🇦🇪", listOf("ar", "arabic", "العربية", "arab")),
        Language("ru",   "Русский",    "🇷🇺", listOf("ru", "russian", "russia")),
        Language("nl",   "Nederlands", "🇳🇱", listOf("nl", "dutch", "nederlands", "netherlands", "holland")),
        Language("tr",   "Türkçe",     "🇹🇷", listOf("tr", "turkish", "türkçe", "turkey")),
        Language("zh",   "中文",        "🇨🇳", listOf("zh", "chinese", "中文", "china")),
        Language("ja",   "日本語",      "🇯🇵", listOf("ja", "japanese", "日本", "japan")),
        Language("ko",   "한국어",      "🇰🇷", listOf("ko", "korean", "한국", "korea")),
        Language("hi",   "हिन्दी",      "🇮🇳", listOf("hi", "hindi", "indian", "india")),
        Language("all",  "Toutes",     "🌐", emptyList()),
    )

    fun getCurrent(context: Context): Language {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 2026-06-08 : défaut = "fr" (= Français en haut de liste).
        val code = prefs.getString(KEY_CODE, "fr") ?: "fr"
        return list.firstOrNull { it.code == code } ?: list.first()
    }

    fun setCurrent(context: Context, lang: Language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODE, lang.code)
            .apply()
    }

    /** 2026-06-08 (user "world live, il y a tellement de contenu que ça crache,
     *  on a 20-30 sections World Chine") : liste exhaustive de pays/régions
     *  non listés dans mes 15 langues, pour détecter qu'une chaîne est
     *  spécifique à un pays sans match → vire si filtre actif.
     *  Format : nom-de-pays lowercase. */
    private val knownCountriesAndRegions: Set<String> = setOf(
        // Afrique
        "afghanistan", "algeria", "algérie", "angola", "benin", "bénin",
        "botswana", "burkina", "burundi", "cameroon", "cameroun", "cape verde",
        "central african", "centrafrique", "chad", "tchad", "comoros", "congo",
        "djibouti", "egypt", "égypte", "egypte", "equatorial guinea", "eritrea",
        "ethiopia", "éthiopie", "gabon", "gambia", "ghana", "guinea", "guinée",
        "ivory coast", "côte d'ivoire", "ivoire", "kenya", "lesotho", "liberia",
        "libya", "libye", "madagascar", "malawi", "mali", "mauritania", "mauritanie",
        "mauritius", "morocco", "maroc", "mozambique", "namibia", "niger",
        "nigeria", "rwanda", "senegal", "sénégal", "seychelles", "sierra leone",
        "somalia", "south africa", "afrique du sud", "south sudan", "sudan",
        "soudan", "swaziland", "tanzania", "togo", "tunisia", "tunisie", "uganda",
        "ouganda", "zambia", "zimbabwe", "africa", "afrique",
        // Asie
        "albania", "albanian", "armenia", "arménie", "azerbaijan", "bahrain",
        "bangladesh", "bhutan", "brunei", "cambodia", "cambodge", "georgia",
        "géorgie", "indonesia", "indonésie", "iraq", "iran", "israel", "israël",
        "jordan", "jordanie", "kazakhstan", "kuwait", "kyrgyzstan", "laos",
        "lebanon", "liban", "malaysia", "malaisie", "maldives", "mongolia",
        "mongolie", "myanmar", "burma", "nepal", "népal", "north korea", "oman",
        "pakistan", "palestine", "philippines", "qatar", "saudi", "arabia",
        "saoudite", "singapore", "singapour", "sri lanka", "syria", "syrie",
        "tajikistan", "taiwan", "taïwan", "thailand", "thaïlande", "timor",
        "turkmenistan", "uae", "emirates", "émirats", "uzbekistan", "vietnam",
        "yemen", "yémen", "asia", "asie",
        // Europe (hors mes langues principales)
        "andorra", "andorre", "austria", "autriche", "belarus", "biélorussie",
        "belgium", "belgique", "bosnia", "bosnie", "bulgaria", "bulgarie",
        "croatia", "croatie", "cyprus", "chypre", "czech", "tchèque",
        "denmark", "danemark", "estonia", "estonie", "finland", "finlande",
        "greece", "grèce", "hungary", "hongrie", "iceland", "islande",
        "ireland", "irlande", "kosovo", "latvia", "lettonie", "liechtenstein",
        "lithuania", "lituanie", "luxembourg", "macedonia", "macédoine", "malta",
        "malte", "moldova", "moldavie", "monaco", "montenegro", "monténégro",
        "norway", "norvège", "poland", "pologne", "romania", "roumanie",
        "san marino", "saint-marin", "serbia", "serbie", "slovakia", "slovaquie",
        "slovenia", "slovénie", "sweden", "suède", "switzerland", "suisse",
        "swiss", "ukraine", "vatican",
        // Amériques (hors mes langues)
        "argentina", "argentine", "bolivia", "bolivie", "chile", "chili",
        "colombia", "colombie", "costa rica", "cuba", "dominican", "dominicain",
        "ecuador", "équateur", "el salvador", "guatemala", "guyana", "haiti",
        "haïti", "honduras", "jamaica", "jamaïque", "mexico", "mexique",
        "nicaragua", "panama", "paraguay", "peru", "pérou", "puerto rico",
        "trinidad", "uruguay", "venezuela", "canada", "canadian", "canadien",
        // Océanie
        "australia", "australie", "fiji", "fidji", "new zealand", "nouvelle-zélande",
        "papua", "papouasie", "samoa", "tonga", "vanuatu",
        // Régions
        "balkan", "caribbean", "caraïbes", "scandinavia", "scandinavie",
        "latin", "nordic", "afrique du nord", "north africa", "sub-saharan",
    )

    /** 2026-06-08 v3 (user "bien sûr World Live y a plus rien qui charge") :
     *  filtre re-équilibré pour ne pas trop virer.
     *
     *  - "all" → toujours true
     *  - tvg-language défini → match strict (autorité maximale)
     *  - Sinon, on regarde SURTOUT le group-title (= source la plus fiable
     *    pour la langue, ex "World - Afghanistan" est sans ambiguïté) :
     *    - Si group-title matche la langue choisie → garde
     *    - Si group-title matche une autre langue → vire
     *    - Si group-title matche un pays connu (hors mes 15 langues) → vire
     *    - Pour le NOM de chaîne, on ne vire QUE si on a un match POSITIF
     *      d'une autre langue claire (= mots-clés stricts). Pas de
     *      knownCountries sur le nom (= trop agressif, ça virait tout).
     *    - Sinon → garde (chaîne neutre type "ABC Movies", "CNN HD") */
    fun matches(
        lang: Language,
        channelName: String,
        groupName: String,
        tvgLanguage: String?,
    ): Boolean {
        if (lang.code == "all") return true
        val tvg = tvgLanguage?.lowercase()?.trim()
        if (!tvg.isNullOrBlank()) {
            return lang.keywords.any { kw -> matchesWordBoundary(tvg, kw) }
        }
        val groupLower = groupName.lowercase()
        val nameLower = channelName.lowercase()

        // 1) group-title : autorité forte. Si match langue choisie → garde.
        if (lang.keywords.any { kw -> matchesWordBoundary(groupLower, kw) }) return true
        // 2) group-title : si une AUTRE langue matche → vire
        val groupMatchesOtherLang = list.any { other ->
            other.code != "all" && other.code != lang.code &&
                other.keywords.any { kw -> matchesWordBoundary(groupLower, kw) }
        }
        if (groupMatchesOtherLang) return false
        // 3) group-title : si un pays connu matche → vire (ex: "WORLD - AFGHANISTAN")
        val groupMatchesCountry = knownCountriesAndRegions.any { country ->
            matchesWordBoundary(groupLower, country)
        }
        if (groupMatchesCountry) return false

        // 4) channel name : check uniquement pour MATCH POSITIF de la langue choisie
        if (lang.keywords.any { kw -> matchesWordBoundary(nameLower, kw) }) return true
        // 5) channel name : check une AUTRE langue connue (= contenu clairement
        //    d'une autre langue). Pas de knownCountries pour éviter de virer
        //    trop de noms génériques.
        val nameMatchesOtherLang = list.any { other ->
            other.code != "all" && other.code != lang.code &&
                other.keywords.any { kw -> matchesWordBoundary(nameLower, kw) }
        }
        if (nameMatchesOtherLang) return false

        // 6) Chaîne neutre (aucun pays détectable dans nom NI group) → garde
        return true
    }

    /** 2026-06-08 v4 (user "le home ne charge plus") : cache des Regex
     *  pré-compilées. Sans ça, 16387 chaînes × ~270 keywords = 4.4M
     *  recompilations de regex à chaque getHome() = 25-30s de freeze.
     *  Avec le cache, la compilation se fait 1 fois (~270 regex) et après
     *  c'est juste du matching ultra rapide. */
    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /** Wrapper public pour utilisation externe (LiveTvHubPlusProvider). */
    fun matchesWordBoundaryPublic(text: String, keyword: String): Boolean =
        matchesWordBoundary(text, keyword)

    private fun matchesWordBoundary(text: String, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        val kw = keyword.lowercase()
        return try {
            val regex = regexCache.getOrPut(kw) {
                Regex("(?:^|[^\\p{L}\\p{N}_])${Regex.escape(kw)}(?:[^\\p{L}\\p{N}_]|$)")
            }
            regex.containsMatchIn(text)
        } catch (_: Throwable) {
            text.contains(kw)
        }
    }
}
