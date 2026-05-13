package com.streamflixreborn.streamflix.models

import java.io.Serializable

/**
 * 2026-05-12 (user "fait un nouveau provider sur lequel on pourra ajouter nos
 * sources nous-mêmes") : représente une source IPTV custom configurée par
 * l'utilisateur, utilisée par MyIptvProvider.
 *
 * Inspiré de TiviMate : 2 types de sources supportées au launch :
 * - **M3U** : URL pointant vers un fichier .m3u/.m3u8 (catalogue HTTP simple)
 * - **STALKER** : portail MAG (URL + adresse MAC), protocole utilisé par les
 *   box officielles (style Vegeta/Ola dans nos providers existants)
 *
 * @property id identifiant stable interne (généré au create, jamais affiché)
 * @property type type de source (M3U ou STALKER)
 * @property name nom affiché à l'utilisateur (ex: "Mon bouquet FR")
 * @property url URL principale : URL .m3u pour M3U, URL portail (ex:
 *   `http://serveur.com:8080/c/`) pour Stalker
 * @property mac adresse MAC (Stalker uniquement, format `00:1A:79:XX:XX:XX`)
 * @property serial serial number optionnel (Stalker, certains portails
 *   l'exigent en plus du MAC)
 * @property userAgent User-Agent custom (M3U + Stalker, optionnel). Certains
 *   portails refusent les requêtes sans UA spécifique (ex: "Mozilla/5.0
 *   (QtEmbedded; U; Linux; C)" pour les vrais MAG box)
 * @property referer Referer HTTP custom (optionnel)
 */
data class IptvSource(
    val id: String,
    val type: Type,
    val name: String,
    val url: String,
    val mac: String? = null,
    val serial: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
) : Serializable {

    enum class Type {
        M3U,
        STALKER,
    }
}
