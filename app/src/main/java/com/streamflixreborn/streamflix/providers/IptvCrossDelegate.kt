package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.fragments.player.settings.IptvFavorites
import com.streamflixreborn.streamflix.models.Video

/**
 * Helper partagé pour que chaque provider IPTV retourne aussi les servers
 * favoris d'AUTRES providers (cross-provider).
 *
 * 2026-05-08 : ajouté pour répondre à la demande user :
 *   "Chaque petit cœur ajoute un serveur Pour la chaîne en question"
 *
 * Avant : les favoris (IptvFavorites) sont stockés cross-provider mais ne
 * s'affichaient que sur le provider qui possédait le server.
 * Après : chaque getServers IPTV append les favoris cross-provider absents
 *         de sa liste, reconstruits comme Video.Server. Le getVideo délègue
 *         au bon provider selon le préfixe de l'id.
 */
object IptvCrossDelegate {

    /** Reconstruit un Video.Server à partir d'un id favori (encode le préfixe
     *  provider + label + URL). Retourne null si le format n'est pas reconnu. */
    fun decodeServer(favId: String): Video.Server? {
        return when {
            favId.startsWith("vegeta_stream::") -> {
                val parts = favId.removePrefix("vegeta_stream::").split("::", limit = 3)
                if (parts.size < 3) return null
                Video.Server(favId, "Vegeta[${parts[0]}] ${parts[1]}")
            }
            favId.startsWith("ola_stream::") -> {
                val parts = favId.removePrefix("ola_stream::").split("::", limit = 3)
                if (parts.size < 3) return null
                val cidShort = parts[0].takeLast(6)
                Video.Server(favId, "OLA[$cidShort] ${parts[1]}")
            }
            favId.startsWith("ch::") || favId.startsWith("m3u8::") || favId.startsWith("sport::") -> {
                Video.Server(favId, "WiTV ${favId.take(40)}")
            }
            favId.startsWith("movixlivetv__") -> {
                val parts = favId.removePrefix("movixlivetv__").split("__")
                val mirror = parts.getOrNull(1) ?: "?"
                Video.Server(favId, "Movix Mirror $mirror")
            }
            else -> null
        }
    }

    /** Returns la liste des favoris cross-provider de cette chaîne, reconstruits
     *  en Video.Server. Exclut ceux dont l'id est déjà dans [existingIds].
     *
     *  2026-05-08 : DÉDUPLICATION par "préfixe stable" (provider+position+qualité,
     *  sans l'URL CDN). Sans ça, marquer ❤ sur "Vegeta[43] FHD" sur 5 sessions
     *  différentes = 5 entrées stockées (URLs différentes à chaque session) qui
     *  ressemblent toutes à la même chaîne.
     *
     *  Stratégie : pour chaque favori décodé, calcule un "stableKey" basé sur
     *  les parties non-URL de l'id. Si plusieurs entrées ont le même stableKey,
     *  on garde la PREMIÈRE (= la plus récente, car IptvFavorites.add(0, ...)
     *  met les nouveaux favoris en tête de liste). */
    fun getFavoritesAsServers(channelKey: String, existingIds: Set<String>): List<Video.Server> {
        val rawIds = IptvFavorites.getFavoritesForChannel(channelKey)
            .filter { it !in existingIds }
        val seenStable = mutableSetOf<String>()
        val deduped = mutableListOf<Video.Server>()
        for (favId in rawIds) {
            val stableKey = stablePrefix(favId)
            if (!seenStable.add(stableKey)) continue
            decodeServer(favId)?.let { deduped += it }
        }
        return deduped
    }

    /** Returns le "préfixe stable" d'un serverId IPTV en strippant la partie URL.
     *  Permet de comparer deux favoris du même server même si leurs URLs CDN
     *  diffèrent (tokens signés qui changent par session). */
    private fun stablePrefix(favId: String): String {
        return when {
            favId.startsWith("vegeta_stream::") -> {
                // Format : vegeta_stream::POS::QUALITY::URL → keep "vegeta_stream::POS::QUALITY"
                val parts = favId.split("::", limit = 4)
                if (parts.size >= 3) "${parts[0]}::${parts[1]}::${parts[2]}" else favId
            }
            favId.startsWith("ola_stream::") -> {
                // Format : ola_stream::CID::QUALITY::URL → keep "ola_stream::CID::QUALITY"
                val parts = favId.split("::", limit = 4)
                if (parts.size >= 3) "${parts[0]}::${parts[1]}::${parts[2]}" else favId
            }
            favId.startsWith("m3u8::") || favId.startsWith("ch::") || favId.startsWith("sport::") -> {
                // WiTV : URL brute → on dédup par hôte+path (sans query string)
                // pour résister aux tokens CDN dans les query params.
                val urlPart = favId.substringAfter("::")
                val noQuery = urlPart.substringBefore('?')
                "${favId.substringBefore("::")}::$noQuery"
            }
            favId.startsWith("movixlivetv__") -> {
                // Format : movixlivetv__rawId__mirror → already stable
                favId
            }
            else -> favId
        }
    }

    /** Délègue le getVideo au bon provider selon le préfixe.
     *  Returns null si pas de match (le caller utilise sa logique propre).
     *  Suspend car les sous-providers ont des getVideo suspend. */
    suspend fun delegateGetVideo(server: Video.Server): Video? {
        return try {
            when {
                server.id.startsWith("vegeta_stream::") ->
                    VegetaTvProvider.getVideo(server)
                server.id.startsWith("ola_stream::") ->
                    OlaTvProvider.getVideo(server)
                server.id.startsWith("ch::") || server.id.startsWith("m3u8::") ||
                    server.id.startsWith("sport::") ->
                    WiTvProvider.getVideo(server)
                // 2026-05-10 : Movix LiveTV (Vavoo) retiré (inaccessible Tahiti
                // sans VPN propre).
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
