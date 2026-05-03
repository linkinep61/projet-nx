package com.streamflixreborn.streamflix.download

/**
 * Wrapper used by [DownloadAdapter] to render a sectioned list of downloads on the
 * "Mes téléchargements" page: a "Films" group at the top followed by one group per
 * TV show, alphabetical, with episodes ordered by season then episode number.
 */
sealed class DownloadListItem {
    /** Section header — sticky-style separator between groups. */
    data class Header(val key: String, val title: String) : DownloadListItem()

    /** A single download row. */
    data class Entry(val download: DownloadEntity) : DownloadListItem()
}

/**
 * Group a flat list of [DownloadEntity] into a sectioned list:
 *   1. "Films" header + all movies (most recent first)
 *   2. One header per TV show (alphabetical) + its episodes (S/E ordered)
 *
 * If a section is empty it's omitted entirely. The "Films" header is only emitted
 * when at least one movie exists AND at least one TV show is also present — when
 * there are only movies, no header is needed (the existing flat layout is fine).
 */
internal fun groupDownloads(downloads: List<DownloadEntity>): List<DownloadListItem> {
    if (downloads.isEmpty()) return emptyList()

    val movies = downloads.filter { it.type == "movie" }
    val episodes = downloads.filter { it.type == "episode" }

    val items = mutableListOf<DownloadListItem>()

    // Films group — only show its header when there are also episodes below; otherwise
    // the page is just movies and a "Films" header would be noise.
    if (movies.isNotEmpty()) {
        if (episodes.isNotEmpty()) {
            items.add(DownloadListItem.Header(key = "movies", title = "Films"))
        }
        movies.sortedByDescending { it.createdAt }
            .forEach { items.add(DownloadListItem.Entry(it)) }
    }

    // Episodes grouped by tv show title (alphabetical), then sorted by season + episode.
    if (episodes.isNotEmpty()) {
        val bySeries = episodes.groupBy { it.title }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        for ((showTitle, eps) in bySeries) {
            items.add(DownloadListItem.Header(key = "show:$showTitle", title = showTitle))
            eps.sortedWith(
                compareBy(
                    { episodeOrderKey(it).first },
                    { episodeOrderKey(it).second },
                )
            ).forEach { items.add(DownloadListItem.Entry(it)) }
        }
    }

    return items
}

/** Extract (seasonNumber, episodeNumber) from the "S{n} E{m} - …" subtitle the
 *  enqueue() helper writes. Falls back to (0,0) when the format doesn't match,
 *  so unparseable entries cluster at the top of the series rather than disappearing. */
private val SE_REGEX = Regex("""^S(\d+)\s*E(\d+)""")
private fun episodeOrderKey(d: DownloadEntity): Pair<Int, Int> {
    val match = SE_REGEX.find(d.subtitle ?: "")
    val s = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val e = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
    return Pair(s, e)
}
