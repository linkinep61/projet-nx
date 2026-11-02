package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// 2026-11-03 — Lecteur « Zenix » de fluxora.sbs (proposé par 1Jour1Film : /embed/zenix?tmdb=…&type=…&lang=vf).
//   Vérifié dans le navigateur (Superman 2025) : lecteur vivant, film entier (2h09), audio FR + EN.
//   Chemin :
//     1. page embed → JSON `const INITIAL_PLAY = {...}` → `stream_url` (/embed-stream/<jeton>?…)
//     2. page embed-stream → <input id="encrypted-source" value="<base64>">
//     3. base64 → https://free.finepulfe.xyz/movies/<tmdb>-xxxx/master.m3u8#zx_relay=… (le fragment est ignoré)
//   Le m3u8 et les segments se lisent sans Referer.
class FluxoraExtractor : Extractor() {
    override val name = "Fluxora"
    override val mainUrl = "https://fluxora.sbs"

    private val client: OkHttpClient by lazy {
        sharedClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun lire(url: String, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Referer", referer)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Fluxora: HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val page = lire(link, "$mainUrl/")
        // 2026-11-03 (banc Mission: Impossible) : la ligne `const INITIAL_PLAY = {...};` est parfois
        //   suivie d'autre code sur la même ligne → l'ancienne regex (qui exigeait un saut de ligne
        //   juste après `};`) ratait la page. On lit le JSON avec JSONTokener, qui s'arrête tout seul
        //   à la fin de l'objet.
        val debut = page.indexOf("const INITIAL_PLAY = ")
        val play = if (debut >= 0) {
            runCatching { org.json.JSONTokener(page.substring(debut + "const INITIAL_PLAY = ".length)).nextValue() as? JSONObject }.getOrNull()
        } else null
        if (play == null) {
            val titre = Regex("(?is)<title>(.*?)</title>").find(page)?.groupValues?.get(1)?.trim()
            Log.w("FluxoraDiag", "pas d'INITIAL_PLAY : taille=${page.length} titre=« $titre » " +
                "debut=${page.take(300).replace("\n", " ")}")
            throw Exception("Fluxora: lecteur sans INITIAL_PLAY (page « $titre », ${page.length} car.)")
        }
        val streamUrl = play.optString("stream_url").takeIf { it.isNotBlank() }
            ?: throw Exception("Fluxora: aucune source proposée pour ce titre (ok=${play.optBoolean("ok")})")
        Log.d("Fluxora", "source=${play.optString("source")} lang=${play.optString("lang")}")

        val stream = lire(streamUrl, link)
        val enc = Regex("""id="encrypted-source"\s+value="([^"]+)"""").find(stream)?.groupValues?.get(1)
            ?: throw Exception("Fluxora: page embed-stream sans source")
        val src = String(Base64.decode(enc, Base64.DEFAULT)).substringBefore('#').trim()
        if (!src.startsWith("http")) throw Exception("Fluxora: source illisible")
        // 2026-11-03 : la source « heiwa » renvoie le lecteur HeiwaStream (page web à jetons anti-robot,
        //   vérifié : sans eux son API répond 410 « Recharge la page »), pas un flux. On échoue
        //   clairement au lieu d'envoyer une page HTML au lecteur vidéo (« format illisible »).
        if (!Regex("""(?i)\.(m3u8|mp4|mkv)(\?|#|$)""").containsMatchIn(src)) {
            throw Exception("Fluxora: source ${play.optString("provider")} = lecteur externe (${android.net.Uri.parse(src).host}), pas de flux direct")
        }

        Video(
            source = src,
            subtitles = listOf(),
            headers = mapOf("User-Agent" to DEFAULT_USER_AGENT)
        )
    }
}
