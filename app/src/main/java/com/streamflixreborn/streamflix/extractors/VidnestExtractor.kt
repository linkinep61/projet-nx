package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import org.jsoup.nodes.Document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import retrofit2.http.GET
import retrofit2.http.Url

class VidnestExtractor : Extractor() {

    override val name = "Vidnest"
    override val mainUrl = "https://vidnest.io"

    // 2026-08-28 (relevé sur 1jour1film0826.online) : `vidnest.fun` sert le MÊME service
    //   mais sous une forme totalement différente — agrégateur indexé TMDB
    //   (`/movie/{tmdbId}`), SPA Next.js, flux HLS signé et daté servi par un CDN tournant
    //   (`cdn100.streamraiwind.stream/img/…/…m3u8?token=…&expires=…`). La page N'EST PAS un
    //   jwplayer : le chemin historique ci-dessous ne s'y applique pas, d'où la branche
    //   WebView dans extract(). Sans cet alias, aucun extracteur n'était sélectionné pour un
    //   lien vidnest.fun → serveur mort.
    override val aliasUrls = listOf("https://vidnest.fun")

    // Le manifeste porte `token=` + `expires=` : le mettre en cache transforme un succès
    //   en 403 dès l'expiration (même piège que Rpmvid, Vidzy, LuluVdo, DoodStream).
    override val cacheTtlMs: Long = 0L

    fun extractSubtitles(text: String): List<Video.Subtitle> {
        val tracksBlock = Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: return emptyList()

        val objectRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)

        return objectRegex.findAll(tracksBlock).mapNotNull { match ->
            val obj = match.groupValues[1]

            val kind = Regex("""kind\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            if (kind != "captions") return@mapNotNull null

            val rawFile = Regex("""file\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            val label = Regex("""label\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            val default = Regex(""""default"\s*:\s*(true|false)""")
                .find(obj)?.groupValues?.get(1)?.toBoolean() ?: false

            if (rawFile == null || label == null) return@mapNotNull null

            val file = Regex("""https://[^\s"']+""")
                .find(rawFile)?.value ?: return@mapNotNull null

            Video.Subtitle(
                file = file,
                label = label,
                initialDefault = default,
                default = if (UserPreferences.serverAutoSubtitlesDisabled) false else default
            )
        }.toList()
    }


    override suspend fun extract(link: String): Video {
        // 2026-08-28 : vidnest.fun (agrégateur TMDB) résout son flux en JS, dans une SPA.
        //   Rien à parser en HTTP direct → on laisse la WebView exécuter la page et on
        //   intercepte le .m3u8. vidnest.io (lecteur jwplayer classique) garde le chemin
        //   historique juste en dessous.
        if (link.contains("vidnest.fun", ignoreCase = true)) {
            // 2026-11-01 : sondage en parallèle des sous-sources (cf. sonderSousSources).
            val vides = sonderSousSources(link)
            if (vides != null && vides.size == SOUS_SOURCES.size) {
                throw Exception("Vidnest (fun) : aucune sous-source n'a ce titre")
            }
            // 2026-11-01 (relevé sur l'Oppo, Bleach S2E47) : quand SEULE Sigma a le titre, la page
            //   passe son flux par des relais qui échouent → 20 s pour rien. Or depuis l'appareil le
            //   flux goodstream répond directement (avec son Referer) → on le lit sans la page.
            val sigma = fluxSigma
            if (vides != null && sigma != null && SOUS_SOURCES.filter { it !in vides } == listOf("hollymoviehd")) {
                android.util.Log.i("VidnestExtractor", "seule Sigma a le titre → lecture directe du flux")
                return Video(source = sigma.first, headers = sigma.second,
                    type = androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            }
            val resolved = com.streamflixreborn.streamflix.utils.WebViewStreamResolver.resolve(
                entryUrl = link,
                referer = "https://vidnest.fun/",
                timeoutMs = 20_000L,
                clickPlay = true,
                attach = true,
                repondreVide = if (vides.isNullOrEmpty()) null else { u ->
                    u.startsWith(API) && vides.any { u.startsWith("$API/$it/") }
                },
            ) ?: throw Exception("Vidnest (fun) : aucun flux capté")

            return Video(
                source = resolved.url,
                headers = resolved.headers.ifEmpty { null },
                type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            )
        }
        val service = Extractor.createJsoupService<Service>(mainUrl)
        val doc = service.get(link)

        val scriptTags = doc.select("script[type=text/javascript]")

        var m3u8: String? = null

        var subtitles: List<Video.Subtitle> = emptyList()

        for (script in scriptTags) {
            val scriptData = script.data()
            if ("jwplayer" in scriptData && "sources" in scriptData && "file" in scriptData) {
                val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                if (match != null) {
                    m3u8 = match.groupValues[1]
                    subtitles = extractSubtitles(scriptData)
                    break
                }
            }
        }

        if (m3u8 == null) {
            throw Exception("Stream URL not found in script tags")
        }

        return Video(
            source = m3u8,
            subtitles = subtitles,
            useServerSubtitleSetting = true
        )
    }

    /**
     * 2026-11-01 (relevé dans Chrome sur Bleach S2E47, user : « trouver à quel moment elles savent
     *   déjà qu'il n'y aura rien ») : la page vidnest.fun interroge 12 sous-sources
     *   `new.vidnest.fun/<nom>/tv|movie/…` L'UNE APRÈS L'AUTRE ; deux d'entre elles (flixhq, ophim)
     *   restent pendues 18 à 30 s avant de répondre 502 → 40 à 50 s pour conclure « rien ».
     *   Interrogées EN MÊME TEMPS, chacune répond « 502 » (rien) en < 1 s, ou 200 + JSON chiffré
     *   (v=2, AES-GCM, clé = SHA-256("<n>|<clé de la page>")) en < 4 s.
     * Rend la liste des sous-sources VIDES, ou null si le sondage n'a rien pu conclure (erreurs
     *   réseau, format changé…) → on retombe alors exactement sur le comportement d'avant.
     */
    private suspend fun sonderSousSources(link: String): Set<String>? {
        val m = Regex("""vidnest\.fun/(movie/\d+|tv/\d+/\d+/\d+)""").find(link) ?: return null
        val chemin = m.groupValues[1]
        val debut = System.currentTimeMillis()
        val resultats = kotlinx.coroutines.coroutineScope {
            SOUS_SOURCES.map { nom ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    nom to kotlinx.coroutines.withTimeoutOrNull(6_000L) { sonder("$API/$nom/$chemin") }
                }
            }.awaitAll()
        }
        val vides = resultats.filter { it.second == false }.map { it.first }.toSet()
        val pleines = resultats.filter { it.second == true }.map { it.first }
        android.util.Log.i("VidnestExtractor", "sondage $chemin en ${System.currentTimeMillis() - debut} ms : " +
            "avec=${pleines} · vides=${vides.size} · sans réponse=${resultats.count { it.second == null }}")
        // Aucune réponse claire du tout → on ne conclut rien.
        if (vides.isEmpty() && pleines.isEmpty()) return null
        // Celles restées muettes 6 s comptent comme vides (ophim reste pendue 30 s quand elle n'a
        //   rien ; un vrai flux répond en < 4 s) : la page cachée ne les attendra pas non plus.
        return SOUS_SOURCES.filter { it !in pleines }.toSet()
    }

    /** true = la sous-source a un flux · false = elle répond « rien » · null = indéterminé. */
    private fun sonder(url: String): Boolean? = runCatching {
        val nonce = android.util.Base64.encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        val req = okhttp3.Request.Builder().url("$url?_n=$nonce")
            .header("Origin", "https://vidnest.fun").header("Referer", "https://vidnest.fun/").build()
        // callTimeout : withTimeoutOrNull n'interrompt pas un appel OkHttp bloquant.
        CLIENT_SONDE.newCall(req).execute().use { r ->
            when {
                r.code == 502 || r.code == 404 -> false
                !r.isSuccessful -> null
                else -> {
                    val corps = r.body?.string().orEmpty()
                    val clair = runCatching { dechiffrer(corps) }.getOrNull() ?: corps
                    if (clair.contains("\"url\"")) {
                        // 2026-11-01 (relevé : Bleach S2E47, S2E1 et Fight Club) : « Sigma »
                        //   (hollymoviehd) répond TOUJOURS avoir un flux ; dans la page il passe par
                        //   des relais qui échouent (et depuis le PC il est refusé, 403), ce qui
                        //   bloquait la conclusion de 20 s. On vérifie donc son flux avant d'y croire
                        //   (depuis l'Oppo il répond : on le lit alors en direct, cf. extract).
                        if (url.contains("/hollymoviehd/")) fluxSigmaJoignable(clair) else true
                    } else if (clair.contains("\"v\"")) null else false
                }
            }
        }
    }.getOrNull()

    /** Flux Sigma vérifié lors du dernier sondage (adresse, en-têtes). */
    @Volatile private var fluxSigma: Pair<String, Map<String, String>>? = null

    /** Le flux GS-25 de Sigma répond-il (avec ses en-têtes) ? */
    private fun fluxSigmaJoignable(clair: String): Boolean = runCatching {
        val streams = org.json.JSONObject(clair).optJSONArray("streams") ?: return false
        val s = (0 until streams.length()).map { streams.getJSONObject(it) }
            .firstOrNull { it.optString("language") == "GS-25" } ?: streams.getJSONObject(0)
        val b = okhttp3.Request.Builder().url(s.getString("url")).header("Range", "bytes=0-1023")
        val entetes = HashMap<String, String>()
        s.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> entetes[k] = h.getString(k); b.header(k, h.getString(k)) } }
        CLIENT_SONDE.newCall(b.build()).execute().use { it.isSuccessful }
            .also { ok -> if (ok) fluxSigma = s.getString("url") to entetes }
    }.getOrDefault(false).also { android.util.Log.d("VidnestExtractor", "Sigma : flux ${if (it) "joignable" else "refusé → ignoré"}") }

    private fun dechiffrer(corps: String): String? {
        val j = org.json.JSONObject(corps)
        if (j.optInt("v") != 2 || !j.has("d")) return null
        val cle = java.security.MessageDigest.getInstance("SHA-256").digest("${j.getString("n")}|$CLE_PAGE".toByteArray())
        val brut = android.util.Base64.decode(j.getString("d"), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(cle, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, brut, 0, 12))
        return String(c.doFinal(brut, 12, brut.size - 12))
    }

    private companion object {
        const val API = "https://new.vidnest.fun"
        val CLIENT_SONDE by lazy {
            com.streamflixreborn.streamflix.utils.NetworkClient.default.newBuilder()
                .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
        }
        // Clé publique du script de la page (déchiffrement des réponses v=2).
        const val CLE_PAGE = "K3p8vQ2mX9rL5wZ0fN7bG1sH4tJ6dC9_y8S3wM1nQ5pL2"
        val SOUS_SOURCES = listOf("yflix", "ophim", "flixhq", "onehd", "rogflix", "rpmvid",
            "nextgencloudfabric", "klikxxi", "hollymoviehd", "vidzee", "vidlink", "allmovies")
    }

    private interface Service {
        @GET
        suspend fun get(@Url url: String): Document
    }
}
