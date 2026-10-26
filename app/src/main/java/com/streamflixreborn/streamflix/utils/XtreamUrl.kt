package com.streamflixreborn.streamflix.utils

/**
 * 2026-10-27 (user : « j'ai lancé Auto Moto HD (Dric4rTV, World Live) — notre lecteur le gère
 * mal ») : adresse Xtream COURTE `http://hôte:port/<user>/<pass>/<id>` = flux MPEG-TS continu.
 * Mesuré sur la TV : le panel coupe la connexion au bout de ~10-13 s (STATE_ENDED), le lecteur
 * recharge, et la reconnexion immédiate prend un HTTP 403 (connexion du compte encore comptée).
 *
 * Vérifié dans Chrome sur ultimateiptv.me:8080 : la forme HLS du même flux existe —
 * `/live/<user>/<pass>/<id>.m3u8` → 200 `application/x-mpegurl` (#EXTM3U), alors que
 * `/<user>/<pass>/<id>.m3u8` (sans /live) → 404. En HLS, le lecteur redemande la playlist et
 * les segments un par un : plus de connexion longue à perdre, plus de coupure.
 */
object XtreamUrl {
    private val COURTE = Regex("""^(https?://[^/]+:\d+)/([^/?#]+)/([^/?#]+)/(\d+)/?$""")

    /** Adresse Xtream courte `http://hôte:port/<user>/<pass>/<id>` ? */
    fun estCourte(url: String): Boolean = COURTE.matches(url.trim().substringBefore('?'))

    /** Adresse HLS équivalente, ou l'adresse inchangée si ce n'est pas une adresse Xtream courte. */
    fun versHls(url: String): String {
        val m = COURTE.matchEntire(url.trim()) ?: return url
        val (base, user, pass, id) = m.destructured
        if (user.equals("live", true) || user.equals("movie", true) || user.equals("series", true)) return url
        return "$base/live/$user/$pass/$id.m3u8"
    }
}
