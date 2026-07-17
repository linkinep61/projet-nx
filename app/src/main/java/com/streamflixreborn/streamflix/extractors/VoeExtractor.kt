package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DecryptHelper
import com.streamflixreborn.streamflix.utils.UserPreferences
import android.util.Log
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.net.URL

class VoeExtractor : Extractor() {

    override val name = "VOE"
    override val mainUrl = "https://voe.sx/"
    override val aliasUrls = listOf(
        "https://jilliandescribecompany.com", "https://mikaylaarealike.com",
        "https://christopheruntilpoint.com", "https://walterprettytheir.com",
        "https://crystaltreatmenteast.com", "https://lauradaydo.com",
        "https://lancewhosedifficult.com", "https://dianaavoidthey.com",
        "https://jefferycontrolmodel.com", "https://sandratableother.com",
        "https://marissasharecareer.com", "https://ralphysuccessfull.org",
        "https://charlestoughrace.com", "https://timmaybealready.com",
        // 2026-06-02 : domaines Voe rotating supplémentaires découverts via FS proxy
        "https://maryspecialwatch.com", "https://rebeccacostthousand.com",
        "https://bryantenunder.com",
        // 2026-07-06 : domaines VOE courts (< 12 chars) non captés par rotatingDomain regex
        // 2026-07-12 (user « le VOE de Frembed échoue alors qu'il marche sur leur site ») :
        //   RETIRÉ "playmogo.com" — c'est en réalité du DoodStream (page = titre « DoodStream »,
        //   CDN doodcdn.io/doimg.net), déjà géré par DoodLaExtractor. Il était dans LES DEUX
        //   extracteurs → routé à tort vers VOE → « no encoded JSON ». Laisse DoodLa le prendre.
        "https://vvide0.com"
        // PAS d'alias "kokoflix.lol" — c'est un proxy multi-host (osaka_go.php
        //   = Voe, grandline_go.php = Netu, etc.). Géré par KakaflixExtractor
        //   (proxy générique) qui résout le redirect, puis l'URL VOE réelle
        //   matche le rotatingDomain word-pattern ci-dessous.
    )

    // Voe uses rotating random-word domains (e.g. sandratableother.com, marissasharecareer.com)
    // Pattern: 3+ concatenated English words (12+ lowercase chars) + /e/ path
    //   Note : kokoflix.lol/osaka_go.php RETIRÉ d'ici — le KakaflixExtractor (proxy
    //   générique) résout le redirect AVANT, donc VoeExtractor reçoit l'URL VOE réelle.
    override val rotatingDomain: List<Regex> = listOf(
        Regex("""^[a-zA-Z0-9-]{12,60}\.(com|net|org|to|sx)/e/[a-zA-Z0-9]+""")
    )


    override suspend fun extract(link: String): Video {
        // Fetch the page — may be a JS redirect page or the real content
        var source = fetchPage(link)
        Log.d("VOE_EXTRACT", "initial fetch: len=${source.html().length}")

        // VOE uses JS redirect pages (small HTML with window.location.href)
        // that point to the real domain. Follow up to 2 redirect layers.
        repeat(2) {
            if (source.html().length < 2000) {
                val redirectUrl = extractJsRedirect(source) ?: return@repeat
                Log.d("VOE_EXTRACT", "JS redirect → $redirectUrl")
                try {
                    source = fetchPage(redirectUrl)
                    Log.d("VOE_EXTRACT", "after redirect: len=${source.html().length}")
                } catch (e: Exception) {
                    Log.e("VOE_EXTRACT", "fetch redirect FAILED: ${e::class.simpleName}: ${e.message}", e)
                    // 2026-06-02 : fallback OkHttp simple si JSoup/Retrofit
                    //   échoue sur le redirect (TLS/timeout). Le serveur Voe
                    //   répond OK avec un Chrome UA, l'app a un souci config.
                    try {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                            .followRedirects(true)
                            .build()
                        val req = okhttp3.Request.Builder()
                            .url(redirectUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                            .build()
                        val resp = client.newCall(req).execute()
                        val html = resp.body?.string() ?: ""
                        resp.close()
                        Log.d("VOE_EXTRACT", "fallback OkHttp: status=${resp.code} len=${html.length}")
                        if (html.length > 2000) {
                            source = org.jsoup.Jsoup.parse(html, redirectUrl)
                            Log.d("VOE_EXTRACT", "fallback OK : after redirect: len=${source.html().length}")
                        }
                    } catch (e2: Exception) {
                        Log.e("VOE_EXTRACT", "fallback OkHttp ALSO failed: ${e2::class.simpleName}: ${e2.message}")
                    }
                }
            }
        }

        val scriptTag = source.selectFirst("script[type=application/json]")
        val encodedStringInScriptTag = scriptTag?.data()?.trim().orEmpty()
        val encodedString = DecryptHelper.findEncodedRegex(source.html())
        Log.d("VOE_EXTRACT",
            "scriptTag=${scriptTag != null} (len=${encodedStringInScriptTag.length}) " +
            "regex=${encodedString != null} (len=${encodedString?.length ?: 0})")

        // VOE may wrap the encoded data in a JSON array like ["encodedString"]
        val rawEncoded = encodedString ?: encodedStringInScriptTag
        if (rawEncoded.isBlank()) {
            Log.e("VOE_EXTRACT", "No encoded data found. HTML preview: ${source.html().take(500)}")
            throw Exception("VOE: no encoded JSON in page (HTML changed or CF block?)")
        }
        val unwrappedEncoded = try {
            val jsonArray = com.google.gson.JsonParser.parseString(rawEncoded).asJsonArray
            jsonArray.get(0).asString
        } catch (_: Exception) {
            rawEncoded
        }
        Log.d("VOE_EXTRACT", "unwrappedEncoded len=${unwrappedEncoded.length} preview=${unwrappedEncoded.take(60)}")

        val decryptedContent = DecryptHelper.decrypt(unwrappedEncoded)
        Log.d("VOE_EXTRACT", "decrypted keys=${decryptedContent.keySet()}")

        val m3u8 = decryptedContent.get("source")?.asString
            ?: throw Exception("VOE: decryption failed or 'source' key missing (got keys=${decryptedContent.keySet()})")
        Log.d("VOE_EXTRACT", "m3u8 OK: ${m3u8.take(100)}...")

        val baseSubtitleScript = source.selectFirst("script")?.data() ?: ""
        var baseSubtitle = ""
        if (baseSubtitleScript.isNotBlank()) {
            val regex = Regex("""var\s+base\s*=\s*['"]([^'"]+)['"]""")
            baseSubtitle = regex.find(baseSubtitleScript)?.groupValues?.get(1) ?: ""
        }

        val subtitles = decryptedContent.getAsJsonArray("captions")
            .map { caption ->
                val obj = caption.asJsonObject
                var file = obj.get("file").asString

                Video.Subtitle(
                    file = if (file.startsWith("http")) file else baseSubtitle + file,
                    label = obj.get("label").asString,
                    initialDefault = obj.get("default").asBoolean,
                    default = if (UserPreferences.serverAutoSubtitlesDisabled) false else obj.get("default").asBoolean
                )
            }
        // Determine the Referer from the final page URL
        val referer = source.location() ?: link
        val refererHost = try {
            val u = URL(referer); "${u.protocol}://${u.host}/"
        } catch (_: Exception) { link }

        return Video(
            source = m3u8,
            headers = mapOf(
                "Referer" to refererHost,
                "Origin" to refererHost.trimEnd('/'),
                "User-Agent" to DEFAULT_USER_AGENT
            ),
            subtitles = subtitles,
            useServerSubtitleSetting = true
        )
    }

    /**
     * Extract JS redirect URL from small VOE redirect pages.
     * Looks for: window.location.href = 'https://...'
     */
    private fun extractJsRedirect(doc: Document): String? {
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        // Match window.location.href = '...' or "..."
        val regex = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
        val matches = regex.findAll(scriptData).map { it.groupValues[1] }.toList()
        // Prefer the fallback URL (the one without permanentToken logic)
        // Usually the last one or the one that's a plain URL
        return matches.lastOrNull { it.startsWith("http") }
    }

    /**
     * Fetch a page using a fresh Retrofit service for the given URL's domain.
     */
    private suspend fun fetchPage(url: String): Document {
        val baseUrl = URL(url).let { "${it.protocol}://${it.host}" }
        val service = Extractor.createJsoupService<VoeExtractorService>(baseUrl, url)
        return service.getSource(url)
    }


    private interface VoeExtractorService {

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        suspend fun getSource(@Url url: String): Document
    }
}