package com.streamflixreborn.streamflix.utils

/**
 * 2026-10-04 — Identité d'un FICHIER vidéo, indépendante du domaine miroir.
 *
 * Contexte (user : « fais ce qui est le mieux ») : la copie Wiflix/CineStream de Movix était
 *   coupée depuis le 16/08 parce qu'elle doublait nos sources directes. La preuve de l'époque :
 *     uqload.cx/embed-v1alx4mfphrg   ==  uqload.is/embed-v1alx4mfphrg
 *     vidmoly.net/embed-r9olsqo1yu0x ==  vidmoly.org/embed-r9olsqo1yu0x
 *   Même fichier, seul le domaine du miroir change. Notre dédup comparait l'URL ENTIÈRE, hôte
 *   compris, et ne pouvait pas les fusionner → chaque fichier apparaissait deux fois.
 *   Mais couper la source entière faisait PERDRE des lecteurs quand le direct ne rendait rien
 *   (Léon : Wiflix direct = 0, CineStream via Movix = 13 lecteurs, tous perdus).
 *
 * Ici on reconnaît la FAMILLE d'hébergeur (tous ses domaines miroirs) et le CODE du fichier :
 *   uqload.cx/embed-v1alx4mfphrg et uqload.is/embed-v1alx4mfphrg → « uqload:v1alx4mfphrg ».
 *
 * Prudence : seules les familles listées sont concernées ; pour tout le reste on renvoie null
 *   et l'appelant garde sa clé d'URL habituelle. Un code de fichier est propre à son hébergeur,
 *   deux fichiers différents ne peuvent donc pas se confondre.
 */
object CleFichier {

    /** famille → motif d'hôte (tous les domaines miroirs connus de l'hébergeur). */
    private val FAMILLES: List<Pair<String, Regex>> = listOf(
        "uqload" to Regex("""(^|\.)uqload\."""),
        "vidmoly" to Regex("""(^|\.)vidmoly\."""),
        "dood" to Regex("""(^|\.)(dood[a-z]*|d0+d|d0+0d|ds2play|dsvplay|playmogo|doodstream|dooood|vidply)\."""),
        "lulu" to Regex("""(^|\.)(luluvdo|luluvid|lulustream|lulu)\."""),
        "mixdrop" to Regex("""(^|\.)(mixdrop[a-z]*|mxdrop|mixdrp|m1xdrop)\."""),
        "dropload" to Regex("""(^|\.)(dropload|dr0pstream)\."""),
        "netu" to Regex("""(^|\.)(waaw|netu|hqq|younetu)\."""),
        "vidzy" to Regex("""(^|\.)vidzy\."""),
    )

    /** Code de fichier : dernier segment de chemin « assez long », sans `embed-` ni `.html`. */
    private fun codeDepuisChemin(chemin: String): String? =
        chemin.split('/')
            .map { it.removePrefix("embed-").substringBefore(".html").substringBefore(".htm") }
            .lastOrNull { it.length >= 6 && it.all { c -> c.isLetterOrDigit() } }

    /** « famille:code » si l'URL appartient à une famille connue, sinon null. */
    fun de(url: String): String? {
        val sansFragment = url.substringBefore('#').trim()
        val apresSchema = sansFragment.substringAfter("://", sansFragment)
        val hote = apresSchema.substringBefore('/').substringBefore(':').lowercase()
        if (hote.isBlank()) return null
        val famille = FAMILLES.firstOrNull { (_, motif) -> motif.containsMatchIn(hote) }?.first
            ?: return null
        val chemin = apresSchema.substringAfter('/', "").substringBefore('?')
        val code = codeDepuisChemin(chemin) ?: return null
        return "$famille:$code"
    }
}
