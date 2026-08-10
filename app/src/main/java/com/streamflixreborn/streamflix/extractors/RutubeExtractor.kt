package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.RutubeProvider

/**
 * RutubeExtractor — rutube.ru (2026-08-13).
 *
 * Hébergeur vidéo russe (comme ok.ru / VK / Mail.ru). Certains providers — DessinAnime en
 * particulier — servent des embeds `rutube.ru/video/<id>` ou `rutube.ru/play/embed/<id>` qui
 * remontaient « No extractors found » (aucun extracteur Rutube n'existait). On extrait l'id
 * (32 hex) de l'URL et on délègue à `RutubeProvider.resolveById`, qui appelle l'API
 * `/api/play/options` et renvoie le master HLS (jouable direct par ExoPlayer).
 */
class RutubeExtractor : Extractor() {

    override val name = "Rutube"
    override val mainUrl = "https://rutube.ru"
    override val aliasUrls = listOf("https://rutube.ru")

    override suspend fun extract(link: String): Video {
        val id = Regex("[a-f0-9]{32}").find(link)?.value
            ?: throw Exception("Rutube : id introuvable dans $link")
        return RutubeProvider.resolveById(id)
    }
}
