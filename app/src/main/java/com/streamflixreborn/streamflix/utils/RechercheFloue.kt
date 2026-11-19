package com.streamflixreborn.streamflix.utils

import java.text.Normalizer

/**
 * 2026-09-22 — Comparaison de titres tolérante, partagée par la recherche.
 *
 * Demande du user : « quand on fait une recherche et qu'il y a une faute d'orthographe,
 * il ne trouve rien du tout alors qu'il pourrait trouver une correspondance ».
 *
 * MESURÉ AVANT D'ÉCRIRE — le premier coupable n'est même pas la faute de frappe. La
 * normalisation historique faisait `lowercase()` puis retirait tout ce qui n'est pas
 * `[a-z0-9]`. Un `é` n'étant pas dans `a-z`, il était **effacé**, pas converti :
 *
 *     "Amélie"       -> "amlie"      | requête "amelie"      -> aucune correspondance
 *     "Les Bronzés"  -> "lesbronzs"  | requête "bronzes"     -> aucune correspondance
 *     "Le Père Noël" -> "leprenol"   | requête "pere noel"   -> aucune correspondance
 *
 * Taper un titre français SANS accent — ce que tout le monde fait à la télécommande —
 * suffisait donc à ne rien trouver. C'est corrigé ici en dépliant les accents (NFD) avant
 * de filtrer, au lieu de les supprimer.
 *
 * Vient ensuite la vraie faute de frappe, traitée par une distance d'édition bornée.
 * ⚠ La tolérance est VOLONTAIREMENT serrée et proportionnelle à la longueur : sur des
 *   requêtes courtes, une lettre d'écart change complètement le sens ("ola" / "old"),
 *   et le principe du projet reste « PAS DE RÉSULTAT plutôt que le mauvais ».
 */
object RechercheFloue {

    /** Minuscules, accents dépliés puis retirés, ponctuation et espaces supprimés. */
    fun normaliser(texte: String): String =
        Normalizer.normalize(texte, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")      // enlève les diacritiques seuls
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    /** Idem mais en gardant les espaces, pour comparer mot à mot. */
    fun normaliserAvecEspaces(texte: String): String =
        Normalizer.normalize(texte, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex(" +"), " ")
            .trim()

    /** Nombre de fautes tolérées pour une requête de cette longueur. */
    fun tolerancePour(requeteNormalisee: String): Int = when {
        requeteNormalisee.length < 4 -> 0      // trop court : aucune tolérance
        requeteNormalisee.length < 8 -> 1
        else -> 2
    }

    /**
     * Distance d'edition entre deux chaines, abandonnee des qu'elle depasse [max]
     * (coupure rapide : sur un catalogue de plusieurs milliers de chaines, on ne
     * calcule pas pour rien).
     *
     * ⚠ Variante de Damerau : une INVERSION de deux lettres voisines coute 1, pas 2.
     *   C'est la faute de frappe la plus frequente, et la distance de Levenshtein
     *   ordinaire la punissait deux fois — « bien » pour « beIN » sortait a 2, donc
     *   hors tolerance pour un mot de 4 lettres. Avec l'inversion a 1, ca passe.
     */
    fun distance(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var avantPrecedente = IntArray(b.length + 1)
        var precedente = IntArray(b.length + 1) { it }
        var courante = IntArray(b.length + 1)
        for (i in 1..a.length) {
            courante[0] = i
            var meilleureDeLaLigne = courante[0]
            for (j in 1..b.length) {
                val cout = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(
                    courante[j - 1] + 1,
                    precedente[j] + 1,
                    precedente[j - 1] + cout
                )
                // Inversion de deux lettres voisines : « ei » <-> « ie ».
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, avantPrecedente[j - 2] + 1)
                }
                courante[j] = v
                if (v < meilleureDeLaLigne) meilleureDeLaLigne = v
            }
            if (meilleureDeLaLigne > max) return max + 1   // plus aucune chance d'y arriver
            val echange = avantPrecedente
            avantPrecedente = precedente
            precedente = courante
            courante = echange
        }
        return precedente[b.length]
    }

    /**
     * Le candidat correspond-il à la requête ?
     * Dans l'ordre : contenu exact (accents dépliés), puis un mot du candidat proche
     * d'un mot de la requête, puis le titre entier proche de la requête entière.
     */
    fun correspond(candidat: String, requete: String): Boolean {
        val c = normaliser(candidat)
        val q = normaliser(requete)
        if (q.isEmpty()) return false
        if (c.contains(q)) return true

        val tol = tolerancePour(q)
        if (tol == 0) return false

        // Titre entier proche de la requête entière ("intersteller" / "interstellar").
        if (distance(c, q, tol) <= tol) return true

        // Un mot du titre proche d'un mot de la requête ("bien sport" / "beIN Sports").
        val motsCandidat = normaliserAvecEspaces(candidat).split(" ").filter { it.length >= 3 }
        val motsRequete = normaliserAvecEspaces(requete).split(" ").filter { it.length >= 3 }
        if (motsRequete.isEmpty()) return false
        return motsRequete.all { mq ->
            val t = tolerancePour(mq)
            motsCandidat.any { mc -> mc.contains(mq) || (t > 0 && distance(mc, mq, t) <= t) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 2026-11-23 (user : « quand on tape Bing Sport 1 sur Vegeta ou OLA, on n'a rien, alors
    //   qu'ils ont des serveurs » / « ça fait des mois qu'on loupe tout ça »). Mesuré sur la
    //   vraie base Vegeta (649 chaînes beIN) avec [correspond] :
    //   - « bing sport 1 » (la dictée de la télécommande écrit « bing » pour « beIN ») : 3
    //     résultats sans rapport - « bing »/« bein » = 2 fautes, hors tolérance ;
    //   - « bein sport 1 » : 399 résultats - le « 1 » (mot de moins de 3 lettres) était ignoré,
    //     toutes les beIN de tous les pays sortaient, y compris des séparateurs « ##### … ##### »,
    //     triées par ordre alphabétique puis coupées à 30 : la beIN Sports 1 FR n'y était pas.
    // Règles réservées aux CHAÎNES TV ([correspond] reste inchangé pour les films/séries) :
    //   alias de dictée, chiffres obligatoires, bruit écarté, classement par pertinence.
    // ════════════════════════════════════════════════════════════════════════════════

    /** Mots que la dictée vocale écrit à la place du nom de chaîne. */
    private val ALIAS_CHAINES = mapOf("bing" to "bein", "being" to "bein", "beine" to "bein")

    private val CHIFFRES = Regex("\\d+")
    private val LIGNE_PROGRAMME = Regex("^\\s*\\d{1,2}/\\d{1,2}\\s+\\d{1,2}:\\d{2}")
    private val MOTS_QUALITE = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "raw", "hevc", "h265", "h264",
        "720", "720p", "1080", "1080p", "2160", "backup", "multi", "vip")
    private val PAYS_FR = setOf("fr", "fra", "france", "french", "francais")
    /** Codes pays reconnus UNIQUEMENT en préfixe (« AR| », « TH: », « *AR beIN ») - « de »,
     *  « en »… sont aussi des mots français, on ne les cherche pas au milieu du nom. */
    private val PAYS_ETRANGERS = setOf("ar", "arb", "th", "es", "esp", "de", "ger", "uk", "en", "us", "usa",
        "it", "ita", "pt", "por", "tr", "nl", "pl", "ro", "mx", "br", "ca", "af", "in", "pk", "id", "my",
        "sa", "qa", "ru", "gr", "se", "dk", "no", "fi", "ch", "al", "ex", "cz", "hu", "bg", "rs", "hr",
        // 2026-11-23 : vus en tête de « bing sport 1 » à égalité avec la carte FR.
        "au", "aus", "car", "ph", "hk", "sg", "nz", "za", "ng", "ma", "dz", "tn", "eg", "lb", "ae", "uae",
        "ksa", "kw", "ir", "il", "jp", "kr", "cn", "tw", "vn", "cl", "co", "pe", "ve", "ie", "at", "ua",
        "si", "sk", "ba", "mk", "afr", "lat", "latam", "asia", "mena", "ids", "sr", "eng")
    private val MOTS_ETRANGERS = setOf("arabic", "english", "eng", "deporte", "deportes", "latino", "espana", "mena",
        "turkish", "turkiye", "indonesia", "malaysia", "australia", "thailand", "usa", "afcon", "afc")
    private val PREFIXE_PAYS = Regex("^\\W*([A-Z]{2,3})(\\s*[|:\\-]|\\s)")

    private fun prefixePays(nom: String): String? = PREFIXE_PAYS.find(nom)?.groupValues?.get(1)?.lowercase()

    /** Requête avec les alias de dictée remplacés (« bing sport 1 » → « bein sport 1 »). */
    fun requeteChaine(requete: String): String =
        normaliserAvecEspaces(requete).split(" ").joinToString(" ") { ALIAS_CHAINES[it] ?: it }

    private fun nombres(texte: String): Set<String> =
        CHIFFRES.findAll(normaliserAvecEspaces(texte)).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()

    /** Ligne qui n'est pas une chaîne : séparateur de catégorie ou ligne de programme. */
    fun estBruit(nom: String): Boolean =
        nom.trimStart().startsWith("#") || nom.contains("###") || LIGNE_PROGRAMME.containsMatchIn(nom)

    /** Comme [correspond], avec les alias de dictée et les chiffres OBLIGATOIRES :
     *  « bein sport 1 » ne renvoie plus beIN Sports 2, 3 ou 11. */
    fun correspondChaine(candidat: String, requete: String): Boolean {
        if (estBruit(candidat)) return false
        val c = plusEnMot(candidat)
        val q = requeteChaine(plusEnMot(requete))
        val nq = nombres(q)
        if (nq.isNotEmpty() && !nombres(c).containsAll(nq)) return false
        return correspond(c, q)
    }

    /** « CANAL+ » → « CANAL plus » : sinon le « + » disparaît à la normalisation et
     *  « canal plus » ne trouvait aucune chaîne Canal+ (mesuré : 0 résultat sur Vegeta). */
    private fun plusEnMot(texte: String): String = texte.replace("+", " plus ")

    /** Mots du nom sans pays ni marqueurs de qualité (« FR| BEIN SPORTS 1 FHD » → « bein sports 1 »). */
    private fun motsUtiles(nom: String): List<String> {
        val prefixe = prefixePays(nom)
        val mots = normaliserAvecEspaces(nom).split(" ").filter { it.isNotBlank() }
        val sansPrefixe = if (prefixe != null && mots.firstOrNull() == prefixe) mots.drop(1) else mots
        return sansPrefixe.filter { it !in MOTS_QUALITE && it !in PAYS_FR && it !in MOTS_ETRANGERS }
    }

    /** Plus haut = plus pertinent. Exact > commence par > contient > approché ; chaîne
     *  française en avant, chaîne d'un autre pays en retrait, nom court préféré. */
    fun scoreChaine(nomBrut: String, requeteBrute: String): Int {
        val nom = plusEnMot(nomBrut)
        val requete = plusEnMot(requeteBrute)
        // « sport » et « sports » comptent pour le même mot (pluriel retiré des deux côtés).
        fun singulier(w: String) = if (w.length > 3 && w.endsWith("s")) w.dropLast(1) else w
        val q = requeteChaine(requete).split(" ").filter { it.isNotBlank() }.joinToString("") { singulier(it) }
        val propre = motsUtiles(nom).joinToString("") { singulier(it) }
        val brut = normaliserAvecEspaces(nom).split(" ").filter { it.isNotBlank() }.joinToString("") { singulier(it) }
        var s = when {
            propre == q -> 100
            propre.startsWith(q) -> 70
            brut.contains(q) -> 45
            else -> 15
        }
        val mots = normaliserAvecEspaces(nom).split(" ")
        val prefixe = prefixePays(nom)
        if (prefixe in PAYS_FR || mots.any { it in PAYS_FR }) s += 25
        if (prefixe in PAYS_ETRANGERS || mots.any { it in MOTS_ETRANGERS }) s -= 35
        s -= ((propre.length - q.length).coerceAtLeast(0) / 3).coerceAtMost(20)
        return s
    }
}
