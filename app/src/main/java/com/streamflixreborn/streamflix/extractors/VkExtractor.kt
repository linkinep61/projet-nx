package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

/**
 * VK.com video embed extractor.
 *
 * Format URL : `https://vk.com/video_ext.php?oid=<oid>&id=<id>&hash=<hash>` ou
 * variantes (`vk.ru`, `vkvideo.ru`, `m.vk.com`). La page contient un object JS
 * `var playerParams = {...}` avec une propriété `params` qui liste les sources
 * vidéo par qualité (url240, url360, url480, url720, url1080, hls).
 *
 * 2026-05-16 : créé pour supporter FranimeProvider (vk + vkru = 5232
 * occurrences dans le catalogue franime.fr, après les top 4
 * sibnet/sendvid/vidmoly/filemoon).
 */
class VkExtractor : Extractor() {
    override val name = "VK"
    override val mainUrl = "https://vk.com"
    override val aliasUrls = listOf(
        "https://vk.ru",
        "https://vkvideo.ru",
        "https://m.vk.com",
        "https://m.vk.ru",
    )

    private val ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract($link)")
        // Normaliser m.vk → vk pour matcher le format du player
        val target = link
            .replace("m.vk.com", "vk.com")
            .replace("m.vk.ru", "vk.ru")
        // 2026-10-04 (VIVA TV KO sur TV : « Too many follow-up requests: 21 ») : sans cookies,
        //   la page d'intégration boucle vers login.vk.ru (autologin) puis revient sur elle-même.
        //   On fait comme le lecteur web de VK : jeton ANONYME (aucun compte) puis API video.get,
        //   qui rend les flux du direct (hls_live…). L'ancien chemin reste en secours.
        runCatching { viaApiAnonyme(target) }
            .onFailure { Log.w(TAG, "API anonyme KO : ${it.message}") }
            .getOrNull()?.let { return videoDirect(it) }

        val service = Extractor.createJsoupService<Service>(mainUrl, mainUrl)
        val document = service.get(
            url = target,
            referer = "https://vk.com/",
            userAgent = ANDROID_CHROME_UA,
        )
        val html = document.toString()

        // 2026-09-23 : DIRECTS VK (demande testeur : VIVA TV pour le dossier Musique,
        //   https://vkvideo.ru/video_ext.php?oid=-177948463&id=456239339). Un direct
        //   n'a ni `hls` ni `url720` : ses flux sont sous `hls_live` / `hls_live_ondemand`
        //   (mesuré : 4 et 5 qualités, 200). `hls_live_playback` rend 404 → pas utilisé.
        lienDirect(html, avecHlsSimple = false)?.let { return videoDirect(it) }

        // Pattern principal : "url720":"https://..." (ou url480/url360/etc.).
        // Préfère HLS si présent, sinon descend par qualité.
        val hls = Regex(""""hls":"([^"]+\.m3u8[^"]*)"""")
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (hls != null) {
            Log.d(TAG, "HLS found: $hls")
            return Video(
                source = hls,
                headers = mapOf(
                    "Referer" to "https://vk.com/",
                    "User-Agent" to ANDROID_CHROME_UA,
                ),
            )
        }

        // Sinon, choisir le MP4 de meilleure qualité disponible
        val qualities = listOf("url1080", "url720", "url480", "url360", "url240")
        for (q in qualities) {
            val m = Regex("""\"$q\":\"([^\"]+)\"""").find(html)
            if (m != null) {
                val url = m.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "Found $q: $url")
                return Video(
                    source = url,
                    headers = mapOf(
                        "Referer" to "https://vk.com/",
                        "User-Agent" to ANDROID_CHROME_UA,
                    ),
                )
            }
        }

        // Fallback : chercher cache_url pattern (legacy)
        val cacheUrl = Regex(""""cache_url":"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
        if (cacheUrl != null) {
            Log.d(TAG, "cache_url fallback: $cacheUrl")
            return Video(
                source = cacheUrl,
                headers = mapOf(
                    "Referer" to "https://vk.com/",
                    "User-Agent" to ANDROID_CHROME_UA,
                ),
            )
        }

        // 2026-09-23 : SANS COOKIES, la page d'intégration ne contient AUCUN flux (mesuré :
        //   21 Ko, zéro lien okcdn — le lecteur va d'abord chercher un jeton anonyme en JS).
        //   Or l'app n'a jamais de cookies VK. En revanche `POST /al_video.php` (act=show,
        //   al=1, video=<oid>_<id>) rend les flux SANS cookies ni compte (mesuré : 200,
        //   hls_live présent et lisible). Ne sert que si tout ce qui précède a échoué.
        runCatching { viaAlVideo(target) }
            .onFailure { Log.w(TAG, "al_video.php KO : ${it.message}") }
            .getOrNull()?.let { return videoDirect(it) }

        throw Exception("VK: no playable source found in embed page")
    }

    /** Premier lien de flux trouvé sous les clés du direct (puis `hls` si demandé). */
    private fun lienDirect(texte: String, avecHlsSimple: Boolean): String? {
        val cles = listOf("hls_live", "hls_live_ondemand") + if (avecHlsSimple) listOf("hls") else emptyList()
        for (cle in cles) {
            val m = Regex("""\\*"$cle\\*"\s*:\s*\\*"(https?:.*?)\\*"""").find(texte) ?: continue
            val u = m.groupValues[1].replace("\\/", "/").replace("\\", "")
            if (u.startsWith("https://")) return u
        }
        return null
    }

    private fun videoDirect(url: String) = Video(
        source = url,
        type = "application/vnd.apple.mpegurl",
        headers = mapOf(
            "Referer" to "https://vkvideo.ru/",
            "User-Agent" to ANDROID_CHROME_UA,
        ),
    )

    @Volatile private var jetonAnonyme: String? = null
    @Volatile private var jetonExpireMs: Long = 0L

    /** Jeton anonyme de l'appli web VK Vidéo (même requête que le lecteur du site, sans compte). */
    private fun jetonAnonyme(): String? {
        jetonAnonyme?.let { if (System.currentTimeMillis() < jetonExpireMs) return it }
        val corps = FormBody.Builder()
            .add("client_secret", "o557NLIkAErNhakXrQ7A")
            .add("client_id", "52461373")
            .add("scopes", "audio_anonymous,video_anonymous,photos_anonymous,profile_anonymous")
            .add("isApiOauthAnonymEnabled", "false")
            .add("version", "1")
            .add("app_id", "6287487")
            .build()
        val requete = Request.Builder()
            .url("https://login.vk.ru/?act=get_anonym_token")
            .post(corps)
            .header("Origin", "https://vkvideo.ru")
            .header("Referer", "https://vkvideo.ru/")
            .header("User-Agent", ANDROID_CHROME_UA)
            .build()
        val texte = Extractor.sharedClient.newCall(requete).execute().use { it.body?.string() } ?: return null
        val data = org.json.JSONObject(texte).optJSONObject("data") ?: return null
        val jeton = data.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val expireS = data.optLong("expired_at", 0L)
        jetonAnonyme = jeton
        // Expiration donnée par VK si présente, sinon 30 min ; marge de 5 min.
        jetonExpireMs = if (expireS > 0) expireS * 1000L - 5 * 60_000L else System.currentTimeMillis() + 30 * 60_000L
        return jeton
    }

    /** API video.get avec le jeton anonyme → premier flux HLS du direct (ou de la vidéo). */
    private suspend fun viaApiAnonyme(link: String): String? = withContext(Dispatchers.IO) {
        val oid = Regex("""[?&]oid=(-?\d+)""").find(link)?.groupValues?.get(1)
        val id = Regex("""[?&]id=(\d+)""").find(link)?.groupValues?.get(1)
        val cle = if (oid != null && id != null) "${oid}_$id"
            else Regex("""video(-?\d+_\d+)""").find(link)?.groupValues?.get(1)
            ?: return@withContext null
        val jeton = jetonAnonyme() ?: return@withContext null
        val corps = FormBody.Builder().add("videos", cle).add("access_token", jeton).build()
        val requete = Request.Builder()
            .url("https://api.vkvideo.ru/method/video.get?v=5.289&client_id=52461373")
            .post(corps)
            .header("Origin", "https://vkvideo.ru")
            .header("Referer", "https://vkvideo.ru/")
            .header("User-Agent", ANDROID_CHROME_UA)
            .build()
        val texte = Extractor.sharedClient.newCall(requete).execute().use { it.body?.string() }
            ?: return@withContext null
        val racine = org.json.JSONObject(texte)
        racine.optJSONObject("error")?.let {
            if (it.optInt("error_code") == 5) jetonAnonyme = null   // jeton refusé → nouveau la prochaine fois
            Log.w(TAG, "video.get erreur ${it.optInt("error_code")} : ${it.optString("error_msg")}")
            return@withContext null
        }
        val fichiers = racine.optJSONObject("response")?.optJSONArray("items")
            ?.optJSONObject(0)?.optJSONObject("files") ?: return@withContext null
        val lien = listOf("hls_live", "hls_live_ondemand", "hls")
            .firstNotNullOfOrNull { k -> fichiers.optString(k).takeIf { it.startsWith("https://") } }
        Log.d(TAG, "API anonyme $cle → ${if (lien != null) "flux trouvé" else "aucun flux"}")
        lien
    }
    private suspend fun viaAlVideo(link: String): String? = withContext(Dispatchers.IO) {
        val oid = Regex("""[?&]oid=(-?\d+)""").find(link)?.groupValues?.get(1)
        val id = Regex("""[?&]id=(\d+)""").find(link)?.groupValues?.get(1)
        val cle = if (oid != null && id != null) "${oid}_$id"
            else Regex("""video(-?\d+_\d+)""").find(link)?.groupValues?.get(1)
            ?: return@withContext null
        val corps = FormBody.Builder()
            .add("act", "show")
            .add("al", "1")
            .add("video", cle)
            .build()
        val requete = Request.Builder()
            .url("https://vkvideo.ru/al_video.php?act=show")
            .post(corps)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://vkvideo.ru/")
            .build()
        val texte = Extractor.sharedClient.newCall(requete).execute().use { it.body?.string() }
            ?: return@withContext null
        val lien = lienDirect(texte, avecHlsSimple = true)
        Log.d(TAG, "al_video.php $cle → ${if (lien != null) "flux trouvé" else "aucun flux"}")
        lien
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String,
            @Header("Accept-Language") lang: String = "fr-FR,fr;q=0.9,en;q=0.8",
        ): Document
    }

    companion object {
        private const val TAG = "VkExtractor"
    }
}
