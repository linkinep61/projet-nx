package com.streamflixreborn.streamflix.utils

/**
 * Filtre langue partagé pour les providers IPTV (Vegeta, Ola, MovixLiveTv,
 * SportLive). Streamflix est FR-only → on garde uniquement les chaînes
 * dont le nom est francophone OU sans marqueur langue identifiable.
 *
 *  Logique :
 *    1. Si le nom contient un MARQUEUR FR explicite (`|FR|`, `[FR]`, `(FR)`,
 *       `FR -`, `Français`, `France`) → ACCEPT inconditionnel (whitelist).
 *    2. Sinon, REJECT si le nom a un préfixe langue NON-FR clair
 *       (`DE|`, `[DE]`, `(DE)`, `DE-`, etc.) ou un mot complet langue en
 *       clair (`Deutsch`, `English`, `Italiano`, etc.).
 *    3. Sinon → ACCEPT (chaînes sans marqueur, on assume FR par défaut).
 *
 *  IMPORTANT : on n'utilise PAS les codes 2-lettres avec délimiteur "espace"
 *  pour le suffixe (ex: ` en `, ` in `) car ils causent des faux positifs
 *  sur des titres FR comme "Billy Dilley en Vacances" ou "Marc Marquez All In".
 */
object IptvLangFilter {

    /** Whitelist : un marqueur FR explicite force l'acceptation. */
    private val frMarkerRegex = Regex(
        "(^|[\\s|\\[\\(])(fr|fra|french|francais|français|france)([\\s|:\\-\\]\\)]|$)"
    )

    /** Préfixe langue non-FR au début du nom : `DE|`, `[DE]`, `(DE)`, `DE-`, etc. */
    private val nonFrPrefixRegex = Regex(
        "^[|\\[\\(]?(de|en|es|pt|it|ar|tr|nl|pl|ro|us|uk|ru|gr|hu|cz|jp|cn|hr|sr|sk|bg|fi|se|no|dk)[|:\\-\\]\\)\\s]"
    )

    /** Mot langue complet en clair (pas les codes 2-lettres ambigus). */
    private val nonFrWordRegex = Regex(
        "\\b(deutsch|english|spanish|portuguese|italiano|italian|arabic|turkish|" +
        "dutch|polish|romanian|russian|greek|hungarian|czech|japanese|chinese|" +
        "hindi|croatian|serbian|slovak|bulgarian|finnish|swedish|norwegian|danish)\\b"
    )

    /**
     * Retourne `true` si la chaîne avec ce nom doit être conservée comme FR-compatible.
     *
     *  @param rawName le nom brut tel qu'arrivé du m3u/portal (ex: `|FR| TF1 HD`,
     *                 `DE | Arte`, `Arte`, `Marc Marquez All In`)
     *  @return true si on garde la chaîne, false si on la rejette
     */
    fun isFrCompatible(rawName: String): Boolean {
        if (rawName.isBlank()) return false
        val nameLower = rawName.lowercase()
        // 1. Whitelist FR → toujours accepter
        if (frMarkerRegex.containsMatchIn(nameLower)) return true
        // 2. Reject si préfixe langue non-FR ou mot complet langue
        if (nonFrPrefixRegex.containsMatchIn(nameLower)) return false
        if (nonFrWordRegex.containsMatchIn(nameLower)) return false
        // 3. Sans marqueur → on assume FR (cas du nom propre genre "Arte", "TF1", etc.)
        return true
    }
}
