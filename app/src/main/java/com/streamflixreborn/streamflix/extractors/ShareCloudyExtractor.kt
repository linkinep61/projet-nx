package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

class ShareCloudyExtractor : Extractor() {

    override val name = "ShareCloudy"
    override val mainUrl = "https://sharecloudy.com"

    override suspend fun extract(link: String): Video {
        val service = Extractor.createGsonService<Service>(mainUrl)

        // 2026-11-02 (user : « des fois Yablom passe au rouge, on reclique et il repart ») : les
        //   adresses ShareCloudy sont stables (vérifié depuis le PC : même m3u8 lisible 10 min plus
        //   tard, segments sans jeton), donc le rouge vient d'un raté PASSAGER côté page d'iframe
        //   (réponse vide / erreur réseau). On rejoue une fois après 1,2 s avant de déclarer rouge,
        //   et on journalise ce qu'on a reçu pour pouvoir tracer les prochains cas.
        val regex = Regex("""file:\s*"([^"]+)"""")
        var url: String? = null
        var dernier = ""
        for (essai in 1..2) {
            val reponse = runCatching { service.get(link) }
            reponse.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            val doc = reponse.getOrNull()?.body() ?: ""
            dernier = reponse.fold(
                { "HTTP ${it.code()} ${doc.length} car." },
                { "${it.javaClass.simpleName} ${it.message}" })
            url = regex.find(doc)?.groupValues?.get(1)
            if (url != null) break
            // 2026-11-02 (banc Monte-Cristo, vérifié depuis le PC) : « Vidéo en cours de transfert… »
            //   = l'hébergeur est en train de recevoir le fichier. Ni mort, ni passager à 1 s près :
            //   on le dit tel quel (pas de « not found », donc jamais en liste noire).
            if (doc.contains("en cours de transfert", ignoreCase = true)) {
                throw Exception("ShareCloudy: vidéo en cours de transfert chez l'hébergeur (réessayer plus tard)")
            }
            android.util.Log.w("ShareCloudy", "essai $essai sans adresse ($dernier) : ${doc.replace(Regex("\\s+"), " ").take(160)}")
            if (essai == 1) kotlinx.coroutines.delay(1_200L)
        }
        if (url == null) throw Exception("Cannot find video ($dernier)")

        return Video(url, headers = mapOf("Referer" to mainUrl))
    }

    private interface Service {
        @GET
        suspend fun get(@Url url: String): Response<String>
    }
}