package com.streamflixreborn.streamflix.extractors

/**
 * 2026-11-15 (user : « très intéressant… ajoute l'ensemble des vidéos ») — pages de chaîne du
 * réseau SportsOnline : `https://sportsoonline.click/channels/hd/hd4.php` (aussi /pt/, /bra/).
 *
 * Vu dans le Chrome du user : la page n'est qu'une façade qui charge UNE iframe de lecteur chez un
 * hébergeur qui change (dynbush.net/e/<id> ce jour-là ; meritend.net autrefois, cf.
 * [MeritendExtractor]). C'est exactement le montage des façades Multi Live, donc on réutilise
 * [Hoca8Extractor] tel quel : WebView, règle générique d'injection dans la sous-frame du lecteur,
 * capture du m3u8, Referer déduit de la page qui a réellement demandé le flux.
 *
 * Le nom de domaine du site change souvent d'orthographe (sportssonline, sportsoonline,
 * sportsonliine… en .click, .st, .si, .so) : reconnu par motif, pas par liste. Les deux anciens
 * domaines déclarés par [MeritendExtractor] restent chez lui (liste exacte, testée avant).
 */
class SportsOnlineExtractor : Hoca8Extractor() {
    override val name = "SportsOnline"
    override val mainUrl = "https://sportsonline.st"
    // Pas de miroirs interchangeables : chaque page de chaîne n'existe que sur le domaine du jour.
    override val aliasUrls = emptyList<String>()
    override val miroirsInterchangeables = false
    override val rotatingDomain = listOf(Regex("""(^|[/.])sports+o+n+l+i+n+e\.[a-z]{2,6}/channels/"""))
}
