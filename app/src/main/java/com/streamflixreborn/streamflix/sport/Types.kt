package com.streamflixreborn.streamflix.sport

/**
 * Module sport — types communs (brief « Module Sport », 25/10/2026, § 3 et § 9).
 *
 * L'UI (SportHub / TV Hub) ne connaît QUE ces types, jamais le format d'une source.
 * Stack réel : Kotlin/Android, sans backend (le brief était écrit en TypeScript) ;
 * l'arborescence sport/providers, sport/utils… est gardée telle quelle.
 */

/** Un lecteur d'un événement ou d'une chaîne. [embedUrl] s'ouvre dans la WebView du lecteur
 *  (l'« iframe » du brief : sur Android il n'y a pas d'iframe, le lecteur ONYX affiche la page). */
data class StreamSource(
    val name: String,
    val embedUrl: String,
    /** URL décodée d'un paramètre `enc:` (information seulement : pas de résolution m3u8 au MVP). */
    val decodedUrl: String? = null,
)

data class SportEvent(
    val id: String,
    /** « damitv », « daddylive »… */
    val provider: String,
    val title: String,
    val league: String,
    /** Catégorie de la source (« football », « basketball »…). */
    val category: String,
    val poster: String,
    /** Millisecondes (les secondes Unix de la source sont multipliées par 1000). */
    val startsAt: Long?,
    val endsAt: Long?,
    /** « live » ou « upcoming », tel que donné par la source (jamais recalculé). */
    val status: String,
    val viewers: Int,
    val sources: List<StreamSource>,
    val embedUrl: String,
    /** Au moins un serveur en français (2026-10-25, user : le reste va dans « Foot d'ailleurs »). */
    val francais: Boolean = false,
) {
    val enDirect: Boolean get() = status == "live"
}

/** Chaîne fixe (24/7) d'une source web : beIN SPORTS 1, Canal+ Foot… */
data class SportChannel(
    val provider: String,
    /** Identifiant propre à la source (chemin de page, numéro…). */
    val ref: String,
    val name: String,
    val logo: String = "",
)

class ProviderError(val provider: String, val code: Int, message: String? = null) :
    Exception("[$provider] ${message ?: "HTTP $code"}")
