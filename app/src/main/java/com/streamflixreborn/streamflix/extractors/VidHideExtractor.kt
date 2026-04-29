package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.UserPreferences
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URL

class VidHideExtractor: Extractor() {
    override val name = "VidHide"
    override val mainUrl = "https://dhtpre.com"

    override val aliasUrls = listOf(
        "https://peytonepre.com",
        "https://vidhideplus.com/",
        "https://mivalyo",
        "https://dinisglows",
        "https://dingtezuni.com",
        "https://dintezuvio.com",
        "https://minochinos.com",
        "https://minochinoos.com",
        "https://moflix-stream.click",
        "https://filelions.to"
    )

    companion object {
        private const val TAG = "VidHideExtractor"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
    }

    override suspend fun extract(link: String): Video {
        Log.d(TAG, "extract() link=$link")
        val mainLink = URL(link).protocol + "://" + URL(link).host
        val fallback = URL(link).protocol + "://" + URL(link).host
        val referer = try {
            UserPreferences.currentProvider?.baseUrl ?: fallback
        } catch (_: Throwable) {
            fallback
        }
        val origin = referer
        Log.d(TAG, "referer=$referer origin=$origin")

        val service = Extractor.createJsoupService<Service>(mainLink, referer)
        val source = service.getSource(
            url = link,
            referer = referer,
            origin = origin,
            userAgent = Extractor.DEFAULT_USER_AGENT
        )
        Log.d(TAG, "HTML length=${source.toString().length}")

        val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
            .find(source.toString())?.let { it.groupValues[1] }

        if (packedJS == null) {
            Log.e(TAG, "Packed JS not found! HTML preview: ${source.toString().take(500)}")
            throw Exception("Packed JS not found")
        }
        Log.d(TAG, "Packed JS found, length=${packedJS.length}")

        val unPacked = JsUnpacker(packedJS).unpack()
        if (unPacked == null) {
            Log.e(TAG, "Unpacked is null")
            throw Exception("Unpacked is null")
        }
        Log.d(TAG, "Unpacked JS: ${unPacked.take(300)}")

        val links = mutableMapOf<String, String>()
        Regex("""["'](hls\d*|file|src|source)["']\s*:\s*["']([^"']+)["']""")
            .findAll(unPacked)
            .forEach {
                links[it.groupValues[1]] = it.groupValues[2]
            }
        Log.d(TAG, "Found links: $links")

        // Prefer hls2 (full CDN URL) over hls4 (relative path on embed domain)
        val finalUrl = links["hls2"] ?: links["hls4"] ?: links["hls3"] ?: links["hls"]
            ?: links["file"] ?: links["src"] ?: links["source"]

        if (finalUrl == null) {
            Log.e(TAG, "No HLS link found in unpacked JS")
            throw Exception("No HLS link found")
        }

        val completeUrl = if (finalUrl.startsWith("/")) {
            val baseUrl = URL(link).protocol + "://" + URL(link).host
            baseUrl + finalUrl
        } else {
            finalUrl
        }
        Log.d(TAG, "Final URL: $completeUrl")

        return Video(source = completeUrl)
    }

    private interface Service {
        @GET
        suspend fun getSource(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("Origin") origin: String,
            @Header("User-Agent") userAgent: String = Extractor.DEFAULT_USER_AGENT
        ): Document
    }
}
