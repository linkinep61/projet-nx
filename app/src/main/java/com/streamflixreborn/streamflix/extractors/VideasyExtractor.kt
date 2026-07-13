package com.streamflixreborn.streamflix.extractors

import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Videasy / VidKing — API migrée vers api.wingsdatabase.com (2026-07).
 *
 * 2026-07-06 : l'ancien flux (api.videasy.net/<endpoint>/sources-with-title + POST
 *   enc-dec.app/api/dec-videasy) est MORT (404 sur videasy.net, 400 sur enc-dec.app).
 *   Le site a migré vers api.wingsdatabase.com avec un flux `seed` + `enc=2` et un
 *   DÉCHIFFREMENT LOCAL (plus de service externe). Porté depuis nuvio-providers
 *   (paregi12/nuvio-providers/providers/vidking.js) — keystream custom + XOR + magic "mvm1".
 *
 * Flux :
 *   1. GET  api.wingsdatabase.com/seed?mediaId=<tmdbId>            -> { seed }
 *   2. GET  api.wingsdatabase.com/<endpoint>/sources-with-title?...&enc=2&seed=<seed>  -> base64 chiffré
 *   3. decryptWingsDatabase(base64, seed, tmdbId)                 -> JSON { sources:[{url,quality}], subtitles:[...] }
 */
class VideasyExtractor : Extractor() {
    override val name = "Videasy"
    override val mainUrl = "https://api.wingsdatabase.com"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val reqHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Origin" to "https://www.vidking.net",
        "Referer" to "https://www.vidking.net/",
    )

    data class ServerConfig(val name: String, val endpoint: String, val movieOnly: Boolean = false)

    // 2026-07-06 : endpoints wingsdatabase actuels (nuvio-providers/vidking.js).
    private val serverConfigs = listOf(
        ServerConfig("Hydrogen", "cdn"),
        ServerConfig("Titanium", "tejo"),
        ServerConfig("Oxygen", "neon2"),
    )

    /** Construit les serveurs (URL SANS seed — le seed est récupéré au moment de l'extract). */
    fun servers(videoType: Video.Type, language: String): List<Video.Server> {
        // 2026-07-12 (user « ces 3 serveurs Videasy VOSTFR apparaissent partout mais ne lisent
        //   jamais / n'ont pas de source → les retirer partout ») : on NEUTRALISE Videasy à la
        //   racine. Plus aucun serveur ajouté (Movix, Cloudstream, backup Embed passent tous ici).
        //   Videasy étant VOSTFR/VO uniquement (jamais VF), ça ne touche AUCUN serveur français VF.
        //   L'extracteur reste enregistré pour le routage d'URL (inoffensif) mais ne propose rien.
        return emptyList()
    }

    fun server(videoType: Video.Type, language: String): Video.Server? =
        servers(videoType, language).firstOrNull()

    private fun buildSourcesUrl(endpoint: String, videoType: Video.Type): String? {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return when (videoType) {
            is Video.Type.Movie -> {
                val year = videoType.releaseDate.split("-").firstOrNull() ?: ""
                "$mainUrl/$endpoint/sources-with-title?title=${enc(videoType.title)}&mediaType=movie" +
                    "&year=$year&episodeId=1&seasonId=1&tmdbId=${videoType.id}" +
                    "&imdbId=${videoType.imdbId ?: ""}&enc=2"
            }
            is Video.Type.Episode -> {
                val year = videoType.tvShow.releaseDate?.split("-")?.firstOrNull() ?: ""
                "$mainUrl/$endpoint/sources-with-title?title=${enc(videoType.tvShow.title)}&mediaType=tv" +
                    "&year=$year&episodeId=${videoType.number}&seasonId=${videoType.season.number}" +
                    "&tmdbId=${videoType.tvShow.id}&imdbId=${videoType.tvShow.imdbId ?: ""}&enc=2"
            }
        }
    }

    override suspend fun extract(link: String): Video {
        val client = NetworkClient.default
        val tmdbId = link.substringAfter("tmdbId=", "").substringBefore("&").ifBlank {
            throw Exception("Videasy: pas de tmdbId dans $link")
        }

        // 1. seed
        val seedReq = Request.Builder()
            .url("$mainUrl/seed?mediaId=$tmdbId")
            .apply { reqHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        val seedBody = client.newCall(seedReq).execute().use { it.body?.string() } ?: ""
        val seed = try { JSONObject(seedBody).optString("seed") } catch (_: Exception) { "" }
        if (seed.isBlank()) throw Exception("Videasy: pas de seed (wingsdatabase)")

        // 2. sources chiffrées
        val srcUrl = if (link.contains("seed=")) link else "$link&seed=${java.net.URLEncoder.encode(seed, "UTF-8")}"
        val srcReq = Request.Builder()
            .url(srcUrl)
            .apply { reqHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        val encData = client.newCall(srcReq).execute().use { it.body?.string() } ?: ""
        if (encData.isBlank()) throw Exception("Videasy: réponse vide (source non indexée)")

        // 3. déchiffrement local
        val decrypted = WingsCrypto.decrypt(encData.trim(), seed, tmdbId.toLongOrNull() ?: 0L)
            ?: throw Exception("Videasy: déchiffrement échoué")
        val result = JSONObject(decrypted)
        val sources = result.optJSONArray("sources")
            ?: throw Exception("Videasy: pas de 'sources' après déchiffrement")

        val subtitles = mutableListOf<Video.Subtitle>()
        result.optJSONArray("subtitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val u = t.optString("url")
                if (u.isNotEmpty()) subtitles.add(
                    Video.Subtitle(label = t.optString("language", t.optString("lang", "?")), file = u)
                )
            }
        }

        if (sources.length() == 0) throw Exception("Videasy: aucune source")
        val src0 = sources.getJSONObject(0)
        val url0 = src0.optString("url")
        if (url0.isBlank()) throw Exception("Videasy: url source vide")
        val mime = if (url0.contains(".mp4")) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8
        return Video(
            source = url0,
            type = mime,
            subtitles = subtitles,
            headers = mapOf(
                "Referer" to "https://www.vidking.net/",
                "Origin" to "https://www.vidking.net",
                "User-Agent" to userAgent,
            ),
        )
    }

    /**
     * Déchiffrement wingsdatabase — porté à l'identique de nuvio-providers/vidking.js.
     * Arithmétique 32-bit non signée reproduite via Int (mêmes bits) + `ushr` pour `>>>`,
     * `Int * Int` pour Math.imul (wrap 32-bit), et mod non signé pour `x >>> 0 % n`.
     * NB : dans le JS, bf(length) est toujours faux (length*(length+1) est pair) → la branche
     * "Af/If" n'est jamais prise ; on l'omet.
     */
    private object WingsCrypto {
        private val MS = 2654435769L.toInt()             // 0x9E3779B9
        private const val JS_C = 61
        private const val ROUNDS = 8
        private val YS = intArrayOf(109, 118, 109, 49)   // "mvm1"
        private val JL = longArrayOf(
            1116352408, 1899447441, 3049323471, 3921009573, 961987163, 1508970993,
            2453635748, 2870763221, 3624381080, 310598401, 607225278, 1426881987,
            1925078388, 2162078206, 2614888103, 3248222580
        ).map { it.toInt() }.toIntArray()

        private fun ui(l0: Int): Int {
            var l = l0
            l = l xor (l ushr 16)
            l *= 2246822507L.toInt()                     // 0x85EBCA6B
            l = l xor (l ushr 13)
            l *= 3266489909L.toInt()                     // 0xC2B2AE35
            l = l xor (l ushr 16)
            return l
        }

        private fun ps(l: Int, o0: Int): Int {
            val o = o0 and 31
            return if (o == 0) l else (l shl o) or (l ushr (32 - o))
        }

        private fun wf(s: String): Int {
            var o = 2166136261L.toInt()                  // 0x811C9DC5
            for (c in s) o = (o xor c.code) * 16777619
            return ui(o)
        }

        private fun vf(l: Int, o: Int, e: Int): Int = (l xor o) or (l and o and e)

        /** x >>> 0 puis % n (non signé). */
        private fun uMod(x: Int, n: Int): Int = ((x.toLong() and 0xFFFFFFFFL) % n).toInt()

        private class State(val s: IntArray, val assigned: BooleanArray, var acc: Int)

        private fun nf(seed: String, tmdb: Int): State {
            val e = IntArray(JS_C)
            val assigned = BooleanArray(JS_C)
            var i = ui(wf(seed) xor ui(tmdb xor MS))
            for (r in 0 until ROUNDS) {                  // Sf(r) toujours vrai
                val n = uMod(i, JS_C)
                i = ps(i + MS, 7 + (r and 7))
                e[n] = i xor ui(i); assigned[n] = true
                i = ui(i + n)
            }
            return State(e, assigned, ui(i xor 2779096485L.toInt())) // 0xA5A5A5A5
        }

        private fun rf(st: State, counter: Int): Int {
            val e = st.s
            val i = st.acc
            val r = uMod(i, JS_C)
            val n = if (st.assigned[r]) -1 else 0
            val u = if (st.assigned[r]) e[r] else 0
            val d = MS * (counter + 1)
            var g = vf(i, u xor d, n)
            g = ps(g + i, r and 31) xor ps(i, (r * 7) and 31)
            val ni = ui(g + MS)
            e[r] = ni; st.assigned[r] = true
            st.acc = ni
            return ni
        }

        private fun cf(seed: String, tmdb: Int, len: Int): ByteArray {
            val st = nf(seed, tmdb)
            val r = ByteArray(len)
            var u = 0
            var counter = 0
            while (u < len) {
                val d = rf(st, counter++)
                r[u++] = (d and 255).toByte()
                if (u < len) r[u++] = ((d ushr 8) and 255).toByte()
                if (u < len) r[u++] = ((d ushr 16) and 255).toByte()
                if (u < len) r[u++] = ((d ushr 24) and 255).toByte()
            }
            return r
        }

        private fun decodeB64(str: String): ByteArray {
            val clean = str.replace('-', '+').replace('_', '/').trimEnd('=')
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        }

        fun decrypt(encB64: String, seed: String, tmdbId: Long): String? {
            return try {
                val i = decodeB64(encB64)
                val ks = cf(seed, tmdbId.toInt(), i.size)
                for (n in i.indices) i[n] = (i[n].toInt() xor ks[n].toInt()).toByte()
                for (n in YS.indices) if ((i[n].toInt() and 0xFF) != YS[n]) return null // bad seed
                String(i, YS.size, i.size - YS.size, Charsets.UTF_8)
            } catch (_: Exception) { null }
        }
    }
}
