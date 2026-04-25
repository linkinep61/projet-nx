package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DecryptHelper
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import android.util.Log
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.net.URL

class VoeExtractor : Extractor() {

    override val name = "VOE"
    override val mainUrl = "https://voe.sx/"
    override val aliasUrls = listOf("https://jilliandescribecompany.com", "https://mikaylaarealike.com","https://christopheruntilpoint.com","https://walterprettytheir.com","https://crystaltreatmenteast.com","https://lauradaydo.com","https://lancewhosedifficult.com", "https://dianaavoidthey.com", "https://jefferycontrolmodel.com", "https://sandratableother.com", "https://marissasharecareer.com", "https://ralphysuccessfull.org", "https://charlestoughrace.com", "https://timmaybealready.com")

    // Voe uses rotating random-word domains (e.g. sandratableother.com, marissasharecareer.com)
    // Pattern: 3+ concatenated English words (12+ lowercase chars) + /e/ path
    override val rotatingDomain: List<Regex> = listOf(
        Regex("""^[a-zA-Z0-9-]{12,60}\.(com|net|org|to|sx)/e/[a-zA-Z0-9]+""")
    )


    override suspend fun extract(link: String): Video {
        // Fetch the page — may be a JS redirect page or the real content
        var source = fetchPage(link)

        // VOE uses JS redirect pages (small HTML with window.location.href)
        // that point to the real domain. Follow up to 2 redirect layers.
        repeat(2) {
            if (source.html().length < 2000) {
                val redirectUrl = extractJsRedirect(source) ?: return@repeat
                Log.d("VOE_EXTRACT", "JS redirect → $redirectUrl")
                source = fetchPage(redirectUrl)
            }
        }

        val scriptTag = source.selectFirst("script[type=application/json]")
        val encodedStringInScriptTag = scriptTag?.data()?.trim().orEmpty()
        val encodedString = DecryptHelper.findEncodedRegex(source.html())

        // VOE may wrap the encoded data in a JSON array like ["encodedString"]
        val rawEncoded = encodedString ?: encodedStringInScriptTag
        val unwrappedEncoded = try {
            val jsonArray = com.google.gson.JsonParser.parseString(rawEncoded).asJsonArray
            jsonArray.get(0).asString
        } catch (_: Exception) {
            rawEncoded
        }

        val decryptedContent = DecryptHelper.decrypt(unwrappedEncoded)

        val m3u8 = decryptedContent.get("source")?.asString
            ?: throw Exception("VOE: decryption failed or 'source' key missing")

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
        return Video(
            source = m3u8,
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
        val service = VoeExtractorService.build(baseUrl, url)
        return service.getSource(url)
    }


    private interface VoeExtractorService {

        companion object {
            fun build(baseUrl: String, originalLink: String): VoeExtractorService {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Referer", originalLink)
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                // Single Retrofit instance — OkHttp follows HTTP redirects automatically,
                // so we don't need to detect the redirect domain from the page HTML.
                // The old approach broke because the first URL in the page (CDN url)
                // was incorrectly used as the redirect base.
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                return retrofit.create(VoeExtractorService::class.java)
            }
        }

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        suspend fun getSource(@Url url: String): Document
    }
}