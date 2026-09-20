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
}
