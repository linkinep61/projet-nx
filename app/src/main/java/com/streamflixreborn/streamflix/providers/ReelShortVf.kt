package com.streamflixreborn.streamflix.providers

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-11-01 (retour d'un membre : « Rejet des cinq compagnes » en anglais sur DramaExpress alors
 * que sur ReelShort c'est en français) — relevé sur reelshort.com/fr :
 *   · une série doublée existe en DEUX fiches distinctes (deux vidéos, pas deux pistes audio) :
 *     l'originale et la « Doublé » (`is_dub: 1` dans la recherche). DramaExpress sert l'originale.
 *   · recherche  /fr/search?keywords=<titre>  → __NEXT_DATA__ : book_id, book_title, is_dub,
 *     paid_start (= 1er épisode payant ; avant lui, épisodes gratuits) ;
 *   · fiche      /fr/movie/x-<book_id>  → props.pageProps.data.online_base[] {serial_number, chapter_id}
 *   · épisode    /fr/episodes/episode-<n>-x-<book_id>-<chapter_id>  → "video_url" (HLS) si gratuit.
 *   Le slug du titre est ignoré par le site (« x » suffit). Aucun Cloudflare constaté.
 * → serveur « ReelShort · VF » sur les épisodes GRATUITS de la version doublée.
 */
object ReelShortVf {
    private const val TAG = "ReelShortVf"
    private const val BASE = "https://www.reelshort.com"
    const val PREFIXE = "rsvf|"

    private data class Livre(val id: String, val gratuitsJusqua: Int)
    private val livres = HashMap<String, Livre?>()                      // titre normalisé → version doublée (null = aucune)
    private val chapitres = ConcurrentHashMap<String, Map<Int, String>>() // book_id → n → chapter_id

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun nextData(url: String): JSONObject? {
        val req = Request.Builder().url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .header("Accept-Language", "fr-FR,fr;q=0.9").build()
        val html = NetworkClient.default.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val m = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        return JSONObject(m.groupValues[1])
    }

    private fun livresDans(o: Any?, acc: MutableList<JSONObject>) {
        when (o) {
            is JSONObject -> {
                if (o.has("book_id") && o.has("book_title")) acc.add(o)
                o.keys().forEach { livresDans(o.opt(it), acc) }
            }
            is JSONArray -> for (i in 0 until o.length()) livresDans(o.opt(i), acc)
        }
    }

    private fun versionDoublee(titre: String): Livre? {
        val cle = norm(titre)
        synchronized(livres) { if (livres.containsKey(cle)) return livres[cle] }
        return runCatching {
            val nd = nextData("$BASE/fr/search?keywords=" + URLEncoder.encode(titre, "UTF-8")) ?: return@runCatching null
            val acc = mutableListOf<JSONObject>(); livresDans(nd, acc)
            acc.firstOrNull { norm(it.optString("book_title")) == norm(titre) && it.optInt("is_dub") == 1 }
                ?.let { Livre(it.getString("book_id"), it.optInt("paid_start", 1) - 1) }
        }.onFailure { Log.w(TAG, "recherche « $titre » : ${it.message}") }.getOrNull()
            .also { Log.i(TAG, "« $titre » → ${it?.let { l -> "version doublée ${l.id}, gratuite jusqu'à l'épisode ${l.gratuitsJusqua}" } ?: "pas de version doublée"}") }
            .also { synchronized(livres) { livres[cle] = it } }
    }

    private fun chapitresDe(bookId: String): Map<Int, String> = chapitres.getOrPut(bookId) {
        val nd = nextData("$BASE/fr/movie/x-$bookId") ?: return emptyMap()
        val a = nd.getJSONObject("props").getJSONObject("pageProps").getJSONObject("data").optJSONArray("online_base") ?: JSONArray()
        (0 until a.length()).associate { a.getJSONObject(it).let { c -> c.optInt("serial_number") to c.optString("chapter_id") } }
    }

    /** Serveur VF pour l'épisode [episode] de [titre], ou null (pas de version doublée, épisode payant…). */
    suspend fun serveur(titre: String?, episode: Int): Video.Server? = withContext(Dispatchers.IO) {
        if (titre.isNullOrBlank()) return@withContext null
        val livre = versionDoublee(titre) ?: return@withContext null
        if (episode > livre.gratuitsJusqua) return@withContext null
        val chap = runCatching { chapitresDe(livre.id)[episode] }.getOrNull() ?: return@withContext null
        Video.Server(id = "$PREFIXE${livre.id}|$episode|$chap", name = "ReelShort · VF",
            src = "$BASE/fr/episodes/episode-$episode-x-${livre.id}-$chap").also { it.language = "VF" }
    }

    suspend fun video(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val nd = nextData(server.src) ?: throw Exception("ReelShort : page épisode illisible")
        val url = Regex("\"video_url\":\"([^\"]+)\"").find(nd.toString())?.groupValues?.get(1)?.replace("\\/", "/")
            ?: throw Exception("ReelShort : épisode verrouillé")
        Video(source = url, type = if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null,
            headers = mapOf("Referer" to "$BASE/", "Origin" to BASE))
    }
}
