package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

// Extractor pour sendvid.com / embed page.
//
// Format : la page contient un <source src="https://videos2.sendvid.com/X/Y/Z.mp4?validfrom=...">
// directement dans le HTML. URL signée avec hash + ip + validity window (~4h).
// 2026-05-12 : ajouté pour supporter AnimeSiteProvider (2 lecteurs sur 5 utilisent sendvid).
//
// 2026-07-17 — FAIL-FAST. Diagnostic (test direct Chrome) : sendvid.com RÉSOUT en DNS
//   (185.107.82.195/196, CDN videos2 → 185.107.92.224) mais l'origine (hébergeur NL,
//   HORS Cloudflare) NE RÉPOND PLUS : la connexion pend jusqu'au timeout. Injoignable
//   depuis au moins certains réseaux/FAI (même profil que Wiflix/OTF depuis Tahiti).
//   Avec le client global (connect/read/call = 30 s), l'utilisateur qui choisit Sendvid
//   attendait JUSQU'À 30 s avant l'échec et le basculement → gel perçu = « le serveur
//   déconne ». On ne peut pas ressusciter une origine morte, mais on peut échouer VITE :
//   client dédié à connect 7 s / call 12 s → un serveur sain répond bien en dessous, un
//   serveur mort/blackholé lâche en quelques secondes et l'auto-switch prend le relais.
class SendvidExtractor : Extractor() {
    override val name = "SendVid"
    override val mainUrl = "https://sendvid.com"

    private companion object {
        /** Sous ce poids, le MP4 servi est le bouche-trou de Sendvid (~36 Ko), pas un épisode. */
        const val TAILLE_MIN_OCTETS = 2L * 1024 * 1024
    }

    /** Client dédié à timeout COURT (dérive du global : garde le pool + DoH). */
    private val fastClient: OkHttpClient by lazy {
        sharedClient.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(link)
            .header("Referer", "$mainUrl/")
            .header("User-Agent", DEFAULT_USER_AGENT)
            .build()

        val html = try {
            fastClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("SendVid: HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            // Timeout/refus = origine injoignable. Message court, l'auto-switch enchaîne.
            Log.d("SendvidExtractor", "injoignable: ${e.message}")
            throw Exception("SendVid: serveur injoignable (${e.message})")
        }

        val document = Jsoup.parse(html, link)

        // Le tag <source src="..."> contient l'URL MP4 directement
        val sourceUrl = document.selectFirst("source[src]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("video source")?.attr("src")?.takeIf { it.isNotBlank() }
            // Fallback : regex sur tout le HTML au cas où le DOM est différent
            ?: Regex("""<source[^>]+src="([^"]+\.mp4[^"]*)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"'\s]+sendvid\.com/[^"'\s]+\.mp4[^"'\s]*)""").find(html)?.value
            ?: throw Exception("SendVid: aucune source MP4 trouvée dans la page embed")

        // 2026-10-09 — FAUSSE VIDÉO. Quand le fichier est HS côté Sendvid, la page embed répond
        //   quand même 200 (status.json bloqué en "encoding", progress 0) et le CDN sert un MP4
        //   bouche-trou de ~36 Ko (quelques secondes). Le lecteur le lisait jusqu'au bout, croyait
        //   l'épisode terminé et passait à l'ÉPISODE SUIVANT au lieu de changer de serveur.
        //   → on sonde la taille du MP4 (HEAD) : un vrai épisode pèse des dizaines de Mo ; sous
        //   2 Mo c'est le bouche-trou → échec, l'auto-switch bascule sur un autre serveur.
        //   Si la sonde elle-même échoue (réseau), on ne bloque pas : on laisse le lecteur essayer.
        val taille: Long? = try {
            val head = Request.Builder()
                .url(sourceUrl)
                .head()
                .header("Referer", "$mainUrl/")
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()
            fastClient.newCall(head).execute().use { resp ->
                if (resp.isSuccessful) resp.header("Content-Length")?.toLongOrNull() else null
            }
        } catch (e: Exception) {
            Log.d("SendvidExtractor", "sonde taille impossible: ${e.message}")
            null
        }
        if (taille != null && taille in 1 until TAILLE_MIN_OCTETS) {
            Log.d("SendvidExtractor", "MP4 bouche-trou ($taille octets) → serveur HS")
            throw Exception("SendVid: vidéo indisponible (fichier de remplacement)")
        }

        Video(
            source = sourceUrl,
            type = "video/mp4",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to DEFAULT_USER_AGENT,
            ),
        )
    }
}
