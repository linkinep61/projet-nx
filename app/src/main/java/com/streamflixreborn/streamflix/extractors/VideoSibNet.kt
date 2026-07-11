package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.util.concurrent.TimeUnit


class VideoSibNetExtractor : Extractor() {
    override val name = "VideoSibNet"
    override val mainUrl = "https://video.sibnet.ru/"

    // 2026-07-06 : Sibnet CDN URLs expirent (~1h, param e=timestamp).
    //   On garde un cache court pour éviter de re-fetcher en boucle lors
    //   d'un seek/replay, mais assez court pour ne pas servir un token mort.
    override val cacheTtlMs: Long = 5L * 60L * 1000L  // 5 min

    override suspend fun extract(link: String): Video {
        android.util.Log.d("VideoSibNet", "extract() link=$link")
        val service = Service.build(mainUrl)

        val document = service.get(link, mainUrl)
        android.util.Log.d("VideoSibNet", "HTML loaded, len=${document.html().length}, scripts=${document.select("script").size}")

        val relativeVideoUrl = extractVideoUrl(document) ?: run {
            val htmlSnippet = document.html().take(600).replace("\n", " ")
            android.util.Log.w("VideoSibNet", "No video URL in HTML. Snippet: $htmlSnippet")
            throw Exception("Could not find video source in the webpage")
        }
        android.util.Log.d("VideoSibNet", "Extracted relative URL: $relativeVideoUrl")

        val gatewayUrl = when {
            relativeVideoUrl.startsWith("http") -> relativeVideoUrl
            relativeVideoUrl.startsWith("//") -> "https:$relativeVideoUrl"
            relativeVideoUrl.startsWith("/") -> mainUrl.trimEnd('/') + relativeVideoUrl
            else -> mainUrl.trimEnd('/') + "/" + relativeVideoUrl
        }
        android.util.Log.d("VideoSibNet", "Gateway URL: $gatewayUrl")

        // 2026-07-06 : Le gateway /v/<hash>/<id>.mp4 fait un 302 vers l'URL CDN
        //   (dv*.sibnet.ru/...mp4?st=TOKEN&e=EXPIRY&noip=1) quand le Referer est
        //   correct. ExoPlayer sur Chromecast envoyait un mauvais Referer → 403.
        //   On suit la redirection nous-mêmes pour obtenir l'URL CDN directe qui
        //   a noip=1 = pas de vérif IP/Referer → marche partout.
        val cdnUrl = resolveCdnUrl(gatewayUrl)
        if (cdnUrl != null) {
            android.util.Log.d("VideoSibNet", "CDN URL resolved: ${cdnUrl.take(80)}...")
            return Video(
                source = cdnUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                )
            )
        }

        // Fallback : si la résolution CDN échoue, on retourne le gateway
        // avec les headers Referer (fonctionnera sur mobile, pas Chromecast)
        android.util.Log.w("VideoSibNet", "CDN resolve failed, falling back to gateway URL")
        return Video(
            source = gatewayUrl,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to USER_AGENT,
                "Sec-Fetch-Dest" to "video",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "same-origin",
                "Accept" to "*/*",
            )
        )
    }

    /**
     * Suit la redirection 302 du gateway Sibnet pour obtenir l'URL CDN directe.
     * Le gateway /v/<hash>/<id>.mp4 redirige vers dv*.sibnet.ru/...mp4?st=...&noip=1
     * quand le Referer est video.sibnet.ru. On désactive followRedirects pour
     * capturer le Location header au lieu de suivre automatiquement.
     */
    private fun resolveCdnUrl(gatewayUrl: String): String? {
        return try {
            val client = OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .followRedirects(false)       // On veut le 302, pas le suivre
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(gatewayUrl)
                .header("Referer", mainUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Sec-Fetch-Dest", "video")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()

            client.newCall(request).execute().use { resp ->
                android.util.Log.d("VideoSibNet", "Gateway response: ${resp.code}")
                when (resp.code) {
                    301, 302, 303, 307, 308 -> {
                        val location = resp.header("Location")
                        android.util.Log.d("VideoSibNet", "Redirect Location: ${location?.take(80)}")
                        // Vérifier que c'est bien une URL CDN Sibnet
                        if (location != null && (location.contains("sibnet.ru") || location.contains(".mp4"))) {
                            when {
                                location.startsWith("http") -> location
                                // URL protocol-relative (//dv97.sibnet.ru/...)
                                location.startsWith("//") -> "https:$location"
                                // Chemin relatif (/path/...)
                                location.startsWith("/") -> mainUrl.trimEnd('/') + location
                                else -> location
                            }
                        } else {
                            android.util.Log.w("VideoSibNet", "Unexpected redirect target: $location")
                            null
                        }
                    }
                    200 -> {
                        // Le serveur a répondu directement sans redirect (rare)
                        // L'URL gateway est peut-être déjà le CDN
                        android.util.Log.d("VideoSibNet", "No redirect, gateway serves directly")
                        null  // On laisse le fallback gateway
                    }
                    else -> {
                        android.util.Log.w("VideoSibNet", "Gateway HTTP ${resp.code}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoSibNet", "CDN resolve error: ${e.message}")
            null
        }
    }

    private fun extractVideoUrl(document: Document): String? {
        // Priority 1: <video src="..."> element (modern Sibnet uses this)
        document.selectFirst("video[src]")?.attr("src")?.let {
            if (it.isNotEmpty() && (it.contains(".mp4") || it.contains(".flv") || it.contains("/v/"))) {
                return it
            }
        }

        // Priority 2: <video><source src="..."></video>
        document.selectFirst("video source[src]")?.attr("src")?.let {
            if (it.isNotEmpty()) {
                return it
            }
        }

        val html = document.toString()

        // Pattern 3: player.src([{src: "..."}])
        Regex("""player\.src\(\[\{src:\s*["']([^"']+)["']""").find(html)?.let {
            return it.groupValues[1]
        }

        // Pattern 4: player.src({src: "..."})
        Regex("""player\.src\(\{src:\s*["']([^"']+)["']""").find(html)?.let {
            return it.groupValues[1]
        }

        // Pattern 5: src: "/video..." or "/v/..." inside a script with player
        val scriptTags = document.select("script")
        for (script in scriptTags) {
            val scriptContent = script.html()
            if ("player" in scriptContent) {
                Regex("""src:\s*["']([^"']+\.mp4[^"']*)["']""").find(scriptContent)?.let {
                    return it.groupValues[1]
                }
                Regex("""src:\s*["']([^"']+\.flv[^"']*)["']""").find(scriptContent)?.let {
                    return it.groupValues[1]
                }
                Regex("""file:\s*["']([^"']+)["']""").find(scriptContent)?.let {
                    return it.groupValues[1]
                }
                Regex("""src:\s*["'](/v/[^"']+)["']""").find(scriptContent)?.let {
                    return it.groupValues[1]
                }
            }
        }

        return null
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(
                    OkHttpClient.Builder()
                        .dns(DnsResolver.doh)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .callTimeout(45, TimeUnit.SECONDS)
                        .build()
                )
                .addConverterFactory(JsoupConverterFactory.create())
                .build()
                .create(Service::class.java)
        }

        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("Accept") accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            @Header("Accept-Language") lang: String = "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
            @Header("User-Agent") userAgent: String = USER_AGENT,
        ): Document
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}