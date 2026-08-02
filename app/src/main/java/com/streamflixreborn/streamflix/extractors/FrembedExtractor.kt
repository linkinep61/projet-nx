package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.text.replaceFirstChar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FrembedExtractor (var newUrl: String = "") : Extractor() {

    override val name = "Frembed"
    // 2026-07-22 : frembed.cyou / .hair morts → défaut = frembed.asia (repli si newUrl vide ;
    //   en pratique le provider passe déjà son baseUrl résolu).
    val defaultUrl = "https://frembed.asia"
    override var mainUrl = newUrl.ifBlank { defaultUrl }

    data class listLinks (
        // 2026-06-13 (user "il y a quelques serveurs qui ne marchent pas,
        //   regarde si ils n'ont pas mis de nouveaux serveurs") :
        //   Frembed API retourne pour les series un champ `link` (sans
        //   suffixe = serveur "principal" / alias VF). C'etait ignore par
        //   l'app → 1 serveur perdu sur chaque episode de serie. On l'ajoute
        //   en tete (= position 0 dans toServers, donc le 1er apparait).
        //   Pareil pour link_vostfr et link_vo qui existent aussi sur certaines
        //   fiches. Annotation @SerializedName car Kotlin convertit
        //   underscore en camelCase par defaut.
        val link: String?=null,
        @com.google.gson.annotations.SerializedName("link_vostfr") val linkMainVostfr: String?=null,
        @com.google.gson.annotations.SerializedName("link_vo") val linkMainVo: String?=null,
        val link1: String?=null,
        val link2: String?=null,
        val link3: String?=null,
        val link4: String?=null,
        val link5: String?=null,
        val link6: String?=null,
        val link7: String?=null,
        val link1vostfr: String?=null,
        val link2vostfr: String?=null,
        val link3vostfr: String?=null,
        val link4vostfr: String?=null,
        val link5vostfr: String?=null,
        val link6vostfr: String?=null,
        val link7vostfr: String?=null,
        val link1vo: String?=null,
        val link2vo: String?=null,
        val link3vo: String?=null,
        val link4vo: String?=null,
        val link5vo: String?=null,
        val link6vo: String?=null,
        val link7vo: String?=null,
        // 2026-06-29 (REPAIR — user "1080p/qualité") : label qualité par version
        //   renvoyé par api/films (ex "HD", "1080p"). Affiché sur le nom du serveur.
        val quality: String?=null,
        @com.google.gson.annotations.SerializedName("qualityVostfr") val qualityVostfr: String?=null,
        @com.google.gson.annotations.SerializedName("qualityVo") val qualityVo: String?=null,
    )

    // 2026-06-29 (REPAIR — restauré depuis la version riche) : sources VIP natives
    //   (Premium sans pub + Free VF) servies par api/streaming/player → m3u8 directs
    //   senpai-stream (pass-through, joués tels quels par ExoPlayer).
    data class StreamingPlayerSource(val label: String?=null, val url: String?=null, val mime: String?=null)
    data class StreamingPlayerResponse(val title: String?=null, val sources: List<StreamingPlayerSource>?=null)

    /** ★ sur les serveurs natifs Frembed (URL sur le domaine frembed.*). */
    private fun isFrembedNative(url: String): Boolean = url.contains("frembed.", ignoreCase = true)

    private fun getExtractorName(url: String): String {
        // Try to identify using the central extractor registry first
        val serviceName = Extractor.identifyServiceName(url)
        if (serviceName != null) return serviceName

        return url.substringAfter("://")
            .substringBefore("/")
            .substringBefore(".")
            .replace("crystaltreatmenteast", "voe")
            .replace("lauradaydo", "voe")
            .replace("lancewhosedifficult", "voe")
            .replace("dianaavoidthey", "voe")
            .replace("jefferycontrolmodel", "voe")
            .replace("myvidplay", "dood")
            .replace("playmogo", "dood")
            .replaceFirstChar { it.uppercase() }
    }

    fun listLinks.toServers(): List<Video.Server> {
        // 2026-06-13 : `link`, `link_vostfr`, `link_vo` ajoutes en TETE de chaque
        //   bloc langue. Pour From S2E5 par exemple : link (VF) + link1-7 (VF)
        //   + link_vostfr (VOSTFR) + link1-7 vostfr + link_vo (VO) + link1-7 vo
        //   = jusqu'a 24 sources possibles au lieu de 21.
        return listOf(link, link1, link2, link3, link4, link5, link6, link7,
                      linkMainVostfr, link1vostfr, link2vostfr, link3vostfr, link4vostfr, link5vostfr, link6vostfr, link7vostfr,
                      linkMainVo, link1vo, link2vo, link3vo, link4vo, link5vo, link6vo, link7vo)
            .mapIndexedNotNull { index, data ->
                if (data.isNullOrEmpty()) return@mapIndexedNotNull null
                val lang = when { index < 8 -> "French"
                                  index < 16 -> "VOSTFR"
                                  else -> "VO" }
                (if (data.startsWith("/")) mainUrl.removeSuffix("/") + data else data).let {
                    Video.Server(id = "link$index", name = "${if (isFrembedNative(it)) "★ " else ""}${getExtractorName(it)} ($lang)", src = it)
                }
            }
    }

    private interface Service {
        companion object {
            private const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

            /** Client for API calls — follows redirects, sends Origin header */
            fun build(baseUrl: String): Service {
                val apiClient = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val url = request.url
                        val origin = "${url.scheme}://${url.host}"
                        val b = request.newBuilder()
                            .header("Referer", "$origin/")
                            .header("Origin", origin)
                        // 2026-06-29 (REPAIR — user "eux aussi protégés par le bypass
                        //   cloudflare") : injecte le cookie cf_clearance (posé par le
                        //   bypass WebView du provider) → frembid + hôtes CF acceptent.
                        val cookies = try {
                            android.webkit.CookieManager.getInstance().getCookie("$origin/")
                        } catch (e: Exception) { null }
                        if (!cookies.isNullOrBlank()) b.header("Cookie", cookies)
                        chain.proceed(b.build())
                    }
                    .dns(DnsResolver.doh)
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(apiClient)
                    .build()
                    .create(Service::class.java)
            }

            /** Client for stream link resolution — no redirects, captures Location header */
            fun buildStreamResolver(baseUrl: String): Service {
                val resolverClient = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val url = request.url
                        val referer = "${url.scheme}://${url.host}/"
                        val newRequest = request.newBuilder()
                            .header("Referer", referer)
                            .build()
                        chain.proceed(newRequest)
                    }
                    .dns(DnsResolver.doh)
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(resolverClient)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET("api/films")
        suspend fun getMovieLinks(
            @Query("id") id: String,
            @Query("idType") idType: String = "tmdb",
            @Header("User-Agent") userAgent: String = USER_AGENT,
            @Header("Content-Type") contentType: String = "application/json"
        ): listLinks

        // 2026-06-29 (REPAIR — restauré) : sources VIP natives (Premium/Free VF).
        @GET("api/streaming/player")
        suspend fun getStreamingPlayer(
            @Query("tmdb") tmdb: String,
            @Query("type") type: String,
            @Query("sa") sa: Int? = null,
            @Query("ep") ep: Int? = null,
            @Header("User-Agent") userAgent: String = USER_AGENT
        ): StreamingPlayerResponse

        @GET("api/series")
        suspend fun getTvShowLinks(
            @Query("id") id: String,
            @Query("sa") sa: Int,
            @Query("epi") epi: Int,
            @Query("idType") idType: String = "tmdb",
            @Header("User-Agent") userAgent: String = USER_AGENT,
            @Header("Content-Type") contentType: String = "application/json"
        ): listLinks

        @GET
        suspend fun getStreamLinks(
            @Url url: String,
            @Header("User-Agent") userAgent: String = USER_AGENT
        ): Response<listLinks>
    }

    private val service = Service.build(mainUrl)
    private val streamResolver = Service.buildStreamResolver(mainUrl)

    override suspend fun extract(link: String): Video {
        throw Exception("None")
    }

    // Reliability ranking: lower = better
    private val reliabilityOrder = mapOf(
        "Vidara" to 1,
        "Vidsonic" to 2,
        "Rpmvid" to 3,
        "StreamWish" to 4,
        "Streamix" to 4,
        "Voe" to 5,
        "Dood" to 6,
        "Filemoon" to 10,
        "Netu" to 99,   // Last: cfglobalcdn CDN often blocked by French ISPs
    )
    private val defaultReliability = 7

    /**
     * 2026-06-29 (REPAIR — restauré depuis la version riche) : sources VIP natives
     *   (★ Frembed Premium sans pub + ★ Frembed Free VF) via `api/streaming/player`
     *   → m3u8 directs senpai (pass-through). Elles passent en tête de liste.
     *
     * 2026-08-08 : extrait dans sa propre fonction pour pouvoir être lancé EN PARALLÈLE de
     *   la résolution des redirections (il était exécuté après, en pure série).
     */
    private suspend fun recupererSourcesVip(videoType: Video.Type): List<Video.Server> = try {
        val tmdb = when (videoType) {
            is Video.Type.Movie -> videoType.id
            is Video.Type.Episode -> videoType.tvShow.id
        }
        val type = if (videoType is Video.Type.Episode) "serie" else "movie"
        val sa = (videoType as? Video.Type.Episode)?.season?.number
        val ep = (videoType as? Video.Type.Episode)?.number
        val resp = service.getStreamingPlayer(tmdb, type, sa, ep)
        Log.d("FrembedExtractor", "Native VIP sources: ${resp.sources?.size ?: 0}")
        resp.sources.orEmpty().mapNotNull { s ->
            val u = s.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Video.Server(id = "vip_${s.label}", name = "★ Frembed ${s.label ?: "VIP"} (French)", src = u)
        }
    } catch (e: Exception) {
        Log.w("FrembedExtractor", "VIP fetch failed: ${e.message}"); emptyList()
    }

    suspend fun servers(videoType: Video.Type): List<Video.Server> {
        Log.d("FrembedExtractor", "servers() called, mainUrl=$mainUrl, videoType=$videoType")
        return try {
            // 2026-08-08 (user : « Frembed fonctionnait dans l'application comme sur le site,
            //   je vois pas les serveurs natifs ») — CORRECTION D'ORDRE, PAS D'AJOUT.
            //   `api/films` / `api/series` (les hébergeurs Voe/Uqload/Dood/Netu = bouton
            //   « Lecteur Gratuit » du site) et `api/streaming/player` (les sources natives
            //   ★ Premium / ★ Free VF = bouton « Lecteur VIP ») sont DEUX endpoints
            //   INDÉPENDANTS. Le premier lançait une HttpException qui faisait sortir tout
            //   le `try` — donc le VIP, pourtant déjà protégé par son propre catch, n'était
            //   jamais appelé.
            //   Mesuré sur Star Trek (tmdb 253) S1E1 :
            //     api/series?id=253&sa=1&epi=1        → 404 {"error":"Series not found"}
            //     api/streaming/player?tmdb=253&...   → 200, 2 sources : « Premium » et
            //                                           « Free VF » (+ seasonData complet)
            //   ⚠ CORRIGÉ LE JOUR MÊME : j'en avais conclu « Frembed A la série en VF ». FAUX —
            //   ces deux sources étaient des entrées orphelines, cf. l'affinage ci-dessous.
            //   Ce qui reste vrai et utile : les deux endpoints sont INDÉPENDANTS, et une panne
            //   de l'un ne doit pas emporter l'autre.
            // 2026-08-08, AFFINAGE (user : « donc j'ai des mauvais matchs, tu peux réparer ») —
            //   ON DISTINGUE « ABSENT DU CATALOGUE » DE « ENDPOINT EN PANNE ».
            //   Le correctif précédent gardait les sources natives QUELLE QUE SOIT la raison de
            //   l'échec. Résultat sur Star Trek (1966) : deux serveurs ★ affichés pour une série
            //   que Frembed n'a pas. Vérifié dans le navigateur du user : leur recherche ne rend
            //   QUE des dérivées (Discovery, Picard, Voyager… 11 séries, aucune de 1966), la page
            //   `/embed/serie/253` répond « Error 404 — Page Not Found », et les URLs annoncées
            //   par `streaming/player` pointent sur un seau de stockage vide
            //   (« Erreur 403 — Ce compartiment ne peut pas être consulté »).
            //   Or Frembed le dit lui-même : `api/series` répond `404 Series not found`. C'est
            //   sa façon d'annoncer « je n'ai pas cette œuvre » — et dans ce cas ses sources
            //   natives sont des entrées orphelines.
            //   Règle : 404 ⇒ absent du catalogue ⇒ on n'interroge MÊME PAS le VIP.
            //           toute autre panne (réseau, 5xx, timeout) ⇒ on garde le VIP, qui est
            //           indépendant — c'est la moitié utile du correctif précédent, conservée.
            var absentDuCatalogue = false
            val ret = try {
                when (videoType) {
                    is Video.Type.Movie -> service.getMovieLinks(videoType.id)
                    is Video.Type.Episode -> service.getTvShowLinks(videoType.tvShow.id, videoType.season.number, videoType.number)
                }
            } catch (e: Exception) {
                val code = (e as? retrofit2.HttpException)?.code()
                absentDuCatalogue = code == 404
                if (absentDuCatalogue) {
                    Log.i("FrembedExtractor", "Frembed déclare ne pas avoir cette œuvre (404) → aucune source, VIP compris")
                } else {
                    Log.w("FrembedExtractor", "hébergeurs indisponibles (${e.javaClass.simpleName}: ${e.message}) → on poursuit avec les seules sources natives VIP")
                }
                null
            }
            if (absentDuCatalogue) return emptyList()
            Log.d("FrembedExtractor", "API response: link1=${ret?.link1?.take(60)}, link2=${ret?.link2?.take(60)}, link3=${ret?.link3?.take(60)}")
            val initialServers = ret?.toServers() ?: emptyList()
            Log.d("FrembedExtractor", "Initial servers: ${initialServers.size} found")
            var vipEnAttente: List<Video.Server> = emptyList()

            // 2026-08-08 (user : « les serveurs qui répondent rapidement doivent arriver
            //   rapidement ») — L'APPEL VIP PASSE EN PARALLÈLE.
            //   Mesuré sur « Obsession » (Chromecast) : l'API répond à T0+2,7 s, la résolution
            //   des redirections se termine vers +5,8 s, PUIS seulement partait l'appel VIP,
            //   qui coûtait encore ~2 s — le tout en série. Ces deux étapes ne dépendent pas
            //   l'une de l'autre : la chaîne de redirections travaille sur les liens de
            //   `toServers()`, l'appel VIP sur `api/streaming/player`. On les lance ensemble et
            //   on ne paie plus que la plus longue des deux.
            val resolvedServers = coroutineScope {
                val vipDiffere = async(Dispatchers.IO) { recupererSourcesVip(videoType) }
                val resolus = initialServers.map { server ->
                    async(Dispatchers.IO) {
                        try {
                            // 2026-06-29 (REPAIR) : la nouvelle API redirige en CHAÎNE
                            //   (frembed.bond → frembed.hair → hôte réel). On suit la
                            //   chaîne jusqu'à atteindre un hôte non-frembed (Voe/Dood…).
                            var currentSrc = server.src
                            var finalUrl: String? = null
                            for (hop in 0 until 5) {
                                val response = streamResolver.getStreamLinks(currentSrc)
                                val redirect = response.headers()["Location"]
                                if (redirect.isNullOrEmpty()) break
                                val full = when {
                                    redirect.startsWith("//") -> "https:$redirect"
                                    redirect.startsWith("/") -> {
                                        val u = java.net.URL(currentSrc); "${u.protocol}://${u.host}$redirect"
                                    }
                                    else -> redirect
                                }
                                if (!isFrembedNative(full)) { finalUrl = full; break }
                                currentSrc = full
                            }
                            if (finalUrl != null) {
                                val lang = server.name.substringAfter(" (").substringBefore(")")
                                val star = if (server.name.trimStart().startsWith("★")) "★ " else ""
                                server.copy(
                                    name = "$star${getExtractorName(finalUrl!!)} ($lang)",
                                    src = finalUrl!!
                                )
                            } else {
                                server
                            }
                        } catch (e: Exception) {
                            server
                        }
                    }
                }.awaitAll()
                vipEnAttente = vipDiffere.await()
                resolus
            }
            val vipServers = vipEnAttente

            // 2026-06-29 (REPAIR) : on retire les hosters NON résolus (src toujours
            //   sur frembed/api/stream = injouables « No extractors found ») pour
            //   éviter les doublons « ★ Frembed » inutiles. Le VIP reste le primaire.
            val playableHosters = resolvedServers.filter {
                !(isFrembedNative(it.src) && it.src.contains("api/stream"))
            }
            // Compound sort: language priority (French > VOSTFR > VO), then reliability
            (vipServers + playableHosters).sortedWith(compareBy<Video.Server> { server ->
                val name = server.name.uppercase()
                when {
                    name.contains("FRENCH") || (name.contains("VF") && !name.contains("VOSTFR")) -> 0
                    name.contains("VOSTFR") || name.contains("VOST") -> 2
                    name.contains("VO") -> 3
                    else -> 1
                }
            }.thenBy { server ->
                val serviceName = Extractor.identifyServiceName(server.src)
                reliabilityOrder[serviceName] ?: defaultReliability
            })
        } catch (e: Exception) {
            Log.e("FrembedExtractor", "servers() FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun server(videoType: Video.Type): Video.Server {
        return this.servers(videoType).first()
    }
}