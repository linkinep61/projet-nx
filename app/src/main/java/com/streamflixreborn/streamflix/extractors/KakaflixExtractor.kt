package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

// Gestionnaire generique de proxy de redirection (kokoflix/kakaflix/newPlayer).
// Suit le redirect HTTP puis delegue a l'extracteur du domaine resolu.
// Calque sur upstream streamflix-reborn FrenchStreamProvider L814.
class KakaflixExtractor : Extractor() {

    override val name = "RedirectProxy"
    override val mainUrl = "https://kokoflix.lol"

    override val aliasUrls = listOf(
        "https://kakaflix.lol"
    )

    override val rotatingDomain: List<Regex> = listOf(
        Regex("""newPlayer\.php""")
    )

    override suspend fun extract(link: String): Video {
        Log.d("RedirectProxy", "Resolving proxy: $link")

        // ── 2026-08-06 : ON NE SUIT PLUS LA CHAÎNE DE REDIRECTIONS ───────────────────────
        //   User : « VOE VF HD échoue à chaque fois ». Journal :
        //     RedirectProxy: Resolving proxy: https://kakaflix.lol/voe3/newPlayer.php?id=…
        //     QualityProbe: Too many follow-up requests: 21
        //   Ce relais renvoie vers un domaine VOE (`pamelachangemission.com/e/<id>`) qui
        //   REBOUCLE sur lui-même. Vérifié dans un vrai navigateur : Chrome affiche « vous a
        //   redirigé à de trop nombreuses reprises ». La boucle est donc côté VOE, elle
        //   n'est pas de notre fait et on ne peut pas la casser en la suivant.
        //   Or on n'a AUCUN besoin d'aller au bout : le PREMIER saut donne déjà tout ce qui
        //   compte — le domaine VOE et l'identifiant de la vidéo. On s'arrête donc là et on
        //   passe la main à l'extracteur VOE, qui sait traiter ce lien.
        //   ⚠ NE PAS remettre `followRedirects(true)` : c'est ce qui faisait tomber
        //     l'extraction au bout de 21 sauts au lieu d'aboutir au premier.
        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val resolvedUrl = withContext(Dispatchers.IO) {
            var courant = link
            var url = link
            // Quelques sauts suffisent largement pour sortir du relais ; on s'arrête dès
            //   qu'on quitte kokoflix/kakaflix, ou faute de nouvelle redirection.
            repeat(4) {
                val request = Request.Builder()
                    .url(courant)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    .header("Referer", "https://www.frenchstream.re/")
                    .build()

                val suivant = client.newCall(request).execute().use { reponse ->
                    reponse.header("Location")?.let { entete ->
                        // `Location` peut être relatif : on le résout sur l'URL courante.
                        courant.toHttpUrlOrNull()?.resolve(entete)?.toString() ?: entete
                    }
                } ?: return@repeat

                url = suivant
                courant = suivant
                val horsRelais = !suivant.contains("kokoflix.lol", true) &&
                    !suivant.contains("kakaflix.lol", true)
                if (horsRelais) return@withContext suivant
            }
            url
        }

        Log.d("RedirectProxy", "Resolved: $link -> $resolvedUrl")

        if (resolvedUrl == link ||
            resolvedUrl.contains("kokoflix.lol", ignoreCase = true) ||
            resolvedUrl.contains("kakaflix.lol", ignoreCase = true)) {
            throw Exception("Redirect proxy failed: $link -> $resolvedUrl")
        }

        return Extractor.extract(resolvedUrl)
    }
}
