package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026-07-10 — COPIE MIROIR DE TEST (NON CÂBLÉE) de [Stream4FreeResolver].
 *
 * ⚠️ NE REMPLACE PAS l'existant : l'autre session travaille sur le code actuel. Ce fichier est
 *    autonome (objet distinct `Stream4FreeResolverCfTest`), n'est référencé nulle part → zéro impact
 *    sur le comportement / le build. But : développer/valider l'AUTOMATISATION DU CLOUDFLARE +
 *    PRÉ-CHAUFFE au boot sans toucher au résolveur en prod. On mergera quand l'autre session aura fini.
 *
 * Différence vs prod :
 *   1. `resolve()` : OkHttp d'abord (rapide, quand pas de challenge). Si échec / pas de m3u8 / page CF,
 *      FALLBACK sur le WebView CF-bypass partagé ([WebViewResolver.get], STEALTH_UA + STEALTH_JS) qui
 *      REND la page comme un vrai Chrome et passe le challenge « invisible ». On regex ensuite l'URL
 *      directe `data-stream.top/{token}/hls/{chaîne}.m3u8` (le vrai flux).
 *   2. `warmUp()` : pré-résout le CF du domaine au boot (clearanceOnly) → 1er zapping rapide. À lancer
 *      APRÈS les autres warm-ups (WebJs/DessinAnime à 12-25s) pour éviter la contention WebView :
 *      ex. `GlobalScope.launch(IO){ delay(40_000); Stream4FreeResolverCfTest.warmUp() }`.
 */
object Stream4FreeResolverCfTest {
    private const val TAG = "Stream4FreeCfTest"
    private const val BASE_URL = "https://www.stream4free.tv"

    private val M3U8_REGEX = Regex("""https?://[a-z0-9]+\.data-stream\.top/[a-f0-9]+/hls/[^"'\s<>]+\.m3u8""")
    // Marqueurs d'un challenge Cloudflare dans le HTML (page non résolue).
    private val CF_CHALLENGE_REGEX = Regex("""Just a moment|challenge-platform|cf_chl|turnstile|Checking your browser|cf-challenge""", RegexOption.IGNORE_CASE)

    // Token stable (vérifié en direct : même token global pour toutes les chaînes, pas de param
    //   d'expiration) → URLs valables longtemps. TTL 24 h + AUTO-RÉPARATION (vérif de vie au cache-hit).
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private val urlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    fun invalidate(slug: String) { urlCache.remove(slug.removePrefix("stream4free://").trim()) }

    private const val TOK = "b8adf6583b654743edf883d7f987fba7490ddacc8ababc153ce1b6e806691604"
    private fun dsUrl(sv: String, name: String) = "https://${sv}.data-stream.top/${TOK}/hls/${name}.m3u8"

    // 2026-07-10 (user : « mets les liens dans l'app, ils s'auto-refresh avec l'algo token-invalide →
    //   refresh ») : liens data-stream.top RÉSOLUS (token stable), indexés par slug stream4free. On
    //   PRÉ-CHARGE le cache avec → au clic = cache-HIT INSTANTANÉ (sans CF). Si un lien meurt (token
    //   tourné), l'auto-réparation de resolve() invalide + re-résout via le bypass CF (algo existant).
    private val BAKED_RESOLVED: Map<String, String> = mapOf(
        "tf1-live-streaming" to dsUrl("sv7", "tf3-HD"),
        "france-3-live" to dsUrl("sv1", "francetv3"),
        "m6-live-streaming" to dsUrl("sv1", "m6france"),
        "l-equipe-21" to dsUrl("sv7", "lequipe"),
        "lci-chaine-info-direct" to dsUrl("sv0", "lciinfo"),
        "france-5-live" to dsUrl("sv7", "francetv5"),
        "tmc" to dsUrl("sv1", "tmcfrance"),
        "w9-france" to dsUrl("sv7", "w9france"),
        "bfm-tv" to dsUrl("sv7", "bfmwc1"),
        "the-simpsons-france" to dsUrl("sv1", "simpsonsfr"),
        "arte" to dsUrl("sv1", "artefrance"),
        "kaamelott-hd" to dsUrl("sv1", "kaamelott"),
        "tfx" to dsUrl("sv7", "tfx"),
        "stargate-sg1-sga" to dsUrl("sv0", "stargate"),
        "cnews" to dsUrl("sv7", "cnewsfr1"),
        "france-4" to dsUrl("sv1", "francetv4"),
        "tf1-series-films" to dsUrl("sv7", "seriefilmes"),
        "rtl9" to dsUrl("sv7", "rtl9france"),
        "south-park-fr" to dsUrl("sv1", "southpark-fr"),
        "rmc-story" to dsUrl("sv1", "numero23"),
        "camera-cafe-stream" to dsUrl("sv0", "cameracafe"),
        "6ter-france" to dsUrl("sv1", "6ter"),
        "h-integrale" to dsUrl("sv1", "hintegrale"),
        "national-geographic" to dsUrl("sv1", "natgeo"),
        "eurosport" to dsUrl("sv20", "eurosportde"),
        "histoire" to dsUrl("sv0", "chaine-histoire"),
        "l-univers-et-ses-mysteres" to dsUrl("sv1", "lunivers"),
        "cstar" to dsUrl("sv7", "cstars-france"),
        "t18-live" to dsUrl("sv1", "t18france"),
        "france-info-tv" to dsUrl("sv1", "francetvinfo"),
        "tv-sciences" to dsUrl("sv0", "scientifique"),
        "tv5-hd" to dsUrl("sv0", "tv5"),
        "france-24" to dsUrl("sv7", "france24"),
        "euronews-france" to dsUrl("sv0", "euroinfos"),
        "public-senat" to dsUrl("sv7", "lachainep"),
        "family-guy-france" to dsUrl("sv0", "familyguy"),
        "futurama-france" to dsUrl("sv0", "xfuturama"),
        "special-investigation" to dsUrl("sv0", "specialinvest"),
    )

    @Volatile private var preseeded = false
    /** Pré-charge le cache avec les liens résolus en dur (une fois). ts très ancien → l'auto-réparation
     *  vérifie la vie du lien au 1er accès (et re-résout via CF s'il est mort). */
    private fun preseedCache() {
        if (preseeded) return
        preseeded = true
        val seedTs = System.currentTimeMillis()
        for ((slug, url) in BAKED_RESOLVED) urlCache.putIfAbsent(slug, url to seedTs)
        Log.d(TAG, "preseedCache: ${BAKED_RESOLVED.size} liens résolus pré-chargés (lecture directe sans CF)")
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun isStream4FreeUrl(url: String): Boolean = url.startsWith("stream4free://")
    /** Préfixe DÉDIÉ au dossier de test CF (séparé du dossier Stream4Free d'origine). */
    fun isCfUrl(url: String): Boolean = url.startsWith("stream4cf://")

    /** Proxy une URL image via DuckDuckGo image proxy → contourne le CF 403 sur stream4free.tv.
     *  weserv.nl renvoyait 404 (CF le bloque côté serveur). DDG passe. */
    private fun proxyLogo(url: String): String {
        if (url.isBlank() || !url.startsWith("http")) return url
        val clean = url.replace("//images/", "/images/")  // fix double slash
        val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
        return "https://external-content.duckduckgo.com/iu/?u=$encoded&f=1"
    }

    /** Remplace tous les tvg-logo stream4free par des URLs proxifiées weserv.nl (BAKED). */
    private val LOGO_RE = Regex("""tvg-logo="(https?://www\.stream4free\.tv/[^"]+)"""")
    val BAKED_CHANNELS_M3U_PROXIED: String by lazy {
        LOGO_RE.replace(BAKED_CHANNELS_M3U) { mr ->
            "tvg-logo=\"${proxyLogo(mr.groupValues[1])}\""
        }
    }

    /**
     * 2026-07-10 — LISTE EN DUR des 53 chaînes Stream4Free (slugs + logos), source unique de la
     *   LISTE (indépendante du CF/git : le dossier est TOUJOURS peuplé au boot, instantané). Le CF
     *   ne sert QU'à la LECTURE (resolve stream4cf://<slug> → data-stream.top, caché 24h).
     */
    val BAKED_CHANNELS_M3U: String = """#EXTM3U
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/tf1.jpg",TF1
stream4cf://tf1-live-streaming
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/france3.png",France 3
stream4cf://france-3-live
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/m6.png",M6
stream4cf://m6-live-streaming
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/lequipe21.png",L equipe 21
stream4cf://l-equipe-21
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/lci.jpg",LCI Chaine Info Direct
stream4cf://lci-chaine-info-direct
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/france5.png",France 5
stream4cf://france-5-live
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/TMC.png",TMC
stream4cf://tmc
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/w9.jpg",W9 France
stream4cf://w9-france
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/bfmtv.jpg",BFM TV
stream4cf://bfm-tv
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/family.jpg",Family Guy
stream4cf://family-guy-france
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/simpsons.jpg",Simpsons FR
stream4cf://the-simpsons-france
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/arte.png",Arte
stream4cf://arte
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/kaamelott.jpg",Kaamelott
stream4cf://kaamelott-hd
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/tfx.png",TFX
stream4cf://tfx
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/stargate.jpg",Stargate SG1 SGA
stream4cf://stargate-sg1-sga
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/rmc.jpg",RMC Découverte
stream4cf://rmc-decouverte
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/cnews.jpg",CNEWS
stream4cf://cnews
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/france4.png",France 4
stream4cf://france-4
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/tf1series.png",TF1 Series Films
stream4cf://tf1-series-films
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/rtl9.png",RTL9
stream4cf://rtl9
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/southpark.jpg",South Park FR
stream4cf://south-park-fr
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/rmc-story.jpg",Rmc Story
stream4cf://rmc-story
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/cameracafe.jpg",Camera Café
stream4cf://camera-cafe-stream
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/6ter.png",6Ter
stream4cf://6ter-france
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/h.jpg",H Intégrale
stream4cf://h-integrale
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/nationalgeo.jpg",National Geographic
stream4cf://national-geographic
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/eurosport.jpg",Eurosport
stream4cf://eurosport
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/histoire.jpg",Histoire
stream4cf://histoire
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/univers.jpg",L'Univers
stream4cf://l-univers-et-ses-mysteres
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/cstar.jpg",CStar
stream4cf://cstar
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/t18.png",T18
stream4cf://t18-live
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/dbz.jpg",Dragonball-DBZ
stream4cf://dragonball-dbz
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/france-info-radio.png",France Info TV
stream4cf://france-info-tv
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/novo19.webp",Novo19
stream4cf://novo19
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/sexy5.jpg",Adults Streams
stream4cf://sex-live-stream
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/science.jpg",TV Sciences
stream4cf://tv-sciences
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/rmc-life.webp",Rmc Life
stream4cf://rmc-life
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/futurama.jpg",Futurama
stream4cf://futurama-france
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/divers.jpg",Divers Docs
stream4cf://divers-docs
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/nature.jpg",Nature HD
stream4cf://nature-hd
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/tv5.png",TV5 HD
stream4cf://tv5-hd
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/enquetexclu.jpg",Enquete Exclusive
stream4cf://enquete-exclusive
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/specialinvestigation.jpg",Special Investigation
stream4cf://special-investigation
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/france24.png",France 24
stream4cf://france-24
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/antoine.jpg",J irai dormir chez vous
stream4cf://j-irai-dormir-chez-vous
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/70show.jpg",70 Show
stream4cf://70-show
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/garsfille.jpg",Un Gars Une fille
stream4cf://un-gars-une-fille
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/finance.jpg",Finance et Mondialisme
stream4cf://finance-et-mondialisme
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/euronews.jpg",Euronews
stream4cf://euronews-france
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/soda.jpg",Soda
stream4cf://soda
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/dessous-des-cartes.webp",DDC
stream4cf://ddc
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/rts.jpg",RTS2
stream4cf://rts2
#EXTINF:-1 group-title="Stream4Free" tvg-logo="https://www.stream4free.tv//images/avatars/public-senat.jpg",Public Senat
stream4cf://public-senat
""".trimEnd()

    /**
     * @param userInitiated true quand le USER clique sur la chaîne (affichage QR autorisé),
     *                      false en pré-résolution fond (jamais de popup QR).
     */
    suspend fun resolve(src: String, userInitiated: Boolean = false): Video? = withContext(Dispatchers.IO) {
        // 2026-07-10 : pré-chargement des liens en dur RETIRÉ — testé, ils renvoient 403 (token mort).
        //   La vraie source fraîche = le git repoussé par le scraper GitHub Actions (CF côté serveur).
        val slug = src.removePrefix("stream4cf://").removePrefix("stream4free://").trim()
        if (slug.isEmpty()) { Log.w(TAG, "resolve: slug vide"); return@withContext null }

        urlCache[slug]?.let { (cachedUrl, ts) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                // AUTO-RÉPARATION **FAIL-OPEN** (2026-07-10, user : réseau lent à Tahiti) : on vérifie
                //   que le m3u8 caché répond, MAIS on n'invalide QUE si le serveur dit clairement « pas
                //   de flux » (403/404, ou 200 sans #EXTM3U). Si le GET TIMEOUTE / erreur réseau (≠ mort)
                //   → on GARDE le lien et on le joue quand même (sinon un réseau lent jetterait un bon
                //   lien pré-chargé vers le CF qui, lui aussi, timeoute). Retour: true=vivant,
                //   false=confirmé mort, null=incertain(réseau) → traité comme vivant.
                val verdict: Boolean? = runCatching {
                    val req = Request.Builder().url(cachedUrl)
                        .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                        .header("Referer", "$BASE_URL/").header("Origin", BASE_URL).build()
                    client.newCall(req).execute().use { r ->
                        when {
                            r.isSuccessful -> (r.body?.string()?.contains("#EXTM3U") == true)
                            r.code in 400..499 -> false            // confirmé mort (token/chemin invalide)
                            else -> null                            // 5xx/autre → incertain → on garde
                        }
                    }
                }.getOrNull()                                       // exception réseau → null → on garde
                if (verdict != false) {
                    if (verdict == null) Log.d(TAG, "resolve CACHE-HIT (réseau incertain, on JOUE quand même): $slug")
                    else Log.d(TAG, "resolve CACHE-HIT (vivant): $slug → data-stream.top direct")
                    return@withContext videoOf(cachedUrl)
                }
                Log.i(TAG, "resolve: URL cachée CONFIRMÉE MORTE pour $slug → invalidation + re-résolution auto")
                urlCache.remove(slug)
            }
        }

        val pageUrl = "$BASE_URL/$slug"

        // 1) OkHttp (rapide). Marche quand CF ne challenge pas.
        val okHtml = runCatching {
            val req = Request.Builder().url(pageUrl)
                .header("User-Agent", Extractor.DEFAULT_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
                .build()
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
        }.getOrDefault("")

        M3U8_REGEX.find(okHtml)?.value?.let { m3u8 ->
            Log.d(TAG, "resolve OkHttp OK: $slug → $m3u8")
            urlCache[slug] = m3u8 to System.currentTimeMillis()
            return@withContext videoOf(m3u8)
        }

        // 2) FALLBACK CLOUDFLARE : la page est vide / challengée → on la REND dans le WebView CF-bypass.
        val cfSuspected = okHtml.isBlank() || CF_CHALLENGE_REGEX.containsMatchIn(okHtml)
        Log.i(TAG, "resolve $slug : OkHttp sans m3u8 (cfSuspected=$cfSuspected) → fallback WebView CF")
        val wvHtml = runCatching {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity
                ?: com.streamflixreborn.streamflix.StreamFlixApp.instance
            val resolver = WebViewResolver(ctx)
            try {
                resolver.get(
                    url = pageUrl,
                    silent = true,
                    contentMarker = "data-stream.top",   // attend que l'URL du flux soit injectée
                    // 2026-07-10 : le CF de stream4free.tv met ~16-24s à se résoudre À FROID (vu au
                    //   log : Clearance au polling 17). 15s ratait le 1er coup → 30s pour passer du
                    //   1er essai. Une fois cf_clearance chaud, les résolutions suivantes sont rapides.
                    markerTimeoutMs = 30_000L,
                )
            } finally { try { resolver.cleanup() } catch (_: Throwable) {} }
        }.onFailure { Log.w(TAG, "WebView CF get KO: ${it.message}") }.getOrDefault("")

        val m3u8Wv = M3U8_REGEX.find(wvHtml)?.value
        if (m3u8Wv != null) {
            Log.d(TAG, "resolve WebView CF OK: $slug → $m3u8Wv")
            urlCache[slug] = m3u8Wv to System.currentTimeMillis()
            return@withContext videoOf(m3u8Wv)
        }

        // QR bypass — DÉSACTIVÉ pour le moment (à réactiver quand prêt).
        // La condition userInitiated empêche le QR de s'afficher pendant le batch pré-resolve.
        // Seul un clic utilisateur sur une chaîne CF-bloquée déclenchera le QR.
        if (isDeviceTv() && userInitiated) {
            // TODO: RÉACTIVER — décommenter le bloc ci-dessous quand le QR sera prêt
            // Log.i(TAG, "resolve: CF échoué sur TV — lancement QR bypass pour $slug")
            // val m3u8FromPhone = showQrAndWaitForPhoneResolve(slug)
            // if (m3u8FromPhone != null) {
            //     Log.d(TAG, "resolve QR bypass OK: $slug → ${m3u8FromPhone.take(80)}")
            //     urlCache[slug] = m3u8FromPhone to System.currentTimeMillis()
            //     return@withContext videoOf(m3u8FromPhone)
            // }
            // Log.w(TAG, "resolve: QR bypass timeout/annulé pour $slug")
            Log.w(TAG, "resolve: CF échoué sur TV pour $slug (QR bypass désactivé, userInitiated=true)")
        } else if (isDeviceTv()) {
            Log.w(TAG, "resolve: CF échoué sur TV pour $slug (pré-résolution fond, pas de QR)")
        } else {
            Log.w(TAG, "resolve: pas de m3u8 même après WebView CF pour $slug (non-TV)")
        }
        null
    }

    /**
     * Pré-chauffe le Cloudflare du domaine stream4free.tv (clearanceOnly → s'arrête dès le cookie CF,
     * sans rendre une page lourde). À appeler au boot, DÉCALÉ après les autres warm-ups (contention
     * WebView). Rend le 1er zapping Stream4Free rapide.
     */
    suspend fun warmUp() {
        runCatching {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity
                ?: com.streamflixreborn.streamflix.StreamFlixApp.instance
            val resolver = WebViewResolver(ctx)
            try {
                resolver.get(url = "$BASE_URL/", silent = true, clearanceOnly = true, markerTimeoutMs = 15_000L)
                Log.d(TAG, "warmUp: CF stream4free pré-chauffé")
            } finally { try { resolver.cleanup() } catch (_: Throwable) {} }
        }.onFailure { Log.w(TAG, "warmUp KO: ${it.message}") }
    }

    /**
     * BEST-EFFORT (voulu par le user) : scrape la LISTE des chaînes EN DIRECT sur stream4free.tv
     * (home rendue via le WebView-bypass CF), au lieu de dépendre du `.m3u` git. Renvoie un M3U
     * `#EXTINF … stream4cf://<slug>`. Vide si le CF dur n'est pas passé (→ l'appelant retombe sur
     * la liste git ré-préfixée). Le dossier reste ainsi SÉPARÉ du dossier Stream4Free d'origine.
     */
    suspend fun scrapeChannelsM3u(): String = withContext(Dispatchers.IO) {
        val html = runCatching {
            val ctx = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity
                ?: com.streamflixreborn.streamflix.StreamFlixApp.instance
            val resolver = WebViewResolver(ctx)
            // 2026-07-10 : garder 15s. À 30s le scrape MONOPOLISE la WebView CF partagée trop longtemps
            //   au boot → la chaîne cliquée ne peut plus se résoudre → « ça démarre plus » (user). 15s =
            //   le build qui marchait : si le scrape rate, on retombe sur la liste en dur (dossier peuplé)
            //   et chaque chaîne se résout au clic sans contention.
            try { resolver.get(url = "$BASE_URL/tv-live-france", silent = true, markerTimeoutMs = 15_000L) }
            finally { try { resolver.cleanup() } catch (_: Throwable) {} }
        }.onFailure { Log.w(TAG, "scrapeChannelsM3u WebView KO: ${it.message}") }.getOrDefault("")
        if (html.isBlank()) return@withContext ""

        // Chaîne = <a href="/<slug>"> … <img src="/images/menu/<logo>"> … [<span class=image-title>Nom</span>].
        //   On capture slug + LOGO + nom en une passe (structure du menu stream4free).
        val re = Regex(
            """href="/([a-z0-9][a-z0-9\-]{1,40})"[^>]*>\s*<img[^>]+src="([^"]+)"[^>]*?(?:alt="([^"]*)")?[^>]*>\s*(?:<span[^>]*image-title[^>]*>([^<]{1,45})</span>)?""",
            RegexOption.IGNORE_CASE,
        )
        val bad = setOf("forum", "pending-approval", "friends", "login", "register", "index.php",
            "component", "donations", "profile", "search", "home", "accueil", "contact",
            "tv-show-series", "films", "series", "sport", "sports")
        val seen = HashSet<String>()
        val sb = StringBuilder("#EXTM3U\n")
        for (m in re.findAll(html)) {
            val slug = m.groupValues[1].lowercase()
            if (slug in bad) continue
            var logo = m.groupValues[2].trim()
            // Les entrées de MENU/navigation utilisent /images/megamenu/ ou /images/menu/ → PAS des chaînes.
            // Seules les vraies chaînes (grille) utilisent /images/avatars/.
            if (logo.contains("/megamenu/", ignoreCase = true)) continue
            if (logo.contains("/images/menu/", ignoreCase = true)) continue
            if (!seen.add(slug)) continue
            if (logo.startsWith("/")) logo = "$BASE_URL$logo"
            logo = proxyLogo(logo)  // proxy weserv.nl → contourne CF 403
            val name = (m.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
                ?: m.groupValues.getOrNull(3))?.trim().orEmpty().ifBlank { slug }
            val logoAttr = if (logo.startsWith("http")) " tvg-logo=\"$logo\"" else ""
            sb.append("#EXTINF:-1$logoAttr group-title=\"Stream4Free CF\",$name\nstream4cf://$slug\n")
        }
        if (seen.isEmpty()) "" else { Log.d(TAG, "scrapeChannelsM3u: ${seen.size} chaînes LIVE (avec jaquettes)"); sb.toString() }
    }

    private fun videoOf(m3u8Url: String): Video = Video(
        source = m3u8Url,
        headers = mapOf(
            "User-Agent" to Extractor.DEFAULT_USER_AGENT,
            "Referer" to "$BASE_URL/",
            "Origin" to BASE_URL,
        ),
        type = "application/vnd.apple.mpegurl",
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  QR BYPASS via Cloudflare Worker + D1
    //  TV crée une session, affiche un QR vers la page Worker.
    //  N'importe quel téléphone (iPhone/Android, avec ou sans ONYX) scanne,
    //  résout le CF, et poste le m3u8 dans D1.
    //  La TV poll le résultat.
    // ─────────────────────────────────────────────────────────────────────────

    private const val WORKER_API_URL = "https://streamflix-api.logami61250.workers.dev"
    private const val QR_BYPASS_TIMEOUT_MS = 120_000L  // 2 min pour scanner le QR
    private const val QR_POLL_INTERVAL_MS = 3_000L     // poll toutes les 3s
    private val JSON_MEDIA = "application/json".toMediaType()

    @Volatile private var cfQrDialog: android.app.AlertDialog? = null

    private fun isDeviceTv(): Boolean {
        val ctx = com.streamflixreborn.streamflix.StreamFlixApp.instance ?: return false
        return try {
            ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        } catch (_: Throwable) { false }
    }

    /**
     * Crée une session CF bypass sur le Worker D1, affiche un QR code sur la TV,
     * et poll le résultat. N'importe quel téléphone peut scanner le QR.
     */
    private suspend fun showQrAndWaitForPhoneResolve(slug: String): String? {
        val activity = com.streamflixreborn.streamflix.StreamFlixApp.currentActivity ?: run {
            Log.w(TAG, "QR bypass: pas d'activity visible")
            return null
        }

        // 1. Créer la session sur le Worker D1
        val session = createBypassSession(slug)
        if (session == null) {
            Log.e(TAG, "QR bypass: impossible de créer la session Worker")
            return null
        }
        val (token, pageUrl) = session
        Log.d(TAG, "QR bypass: session créée, pageUrl=$pageUrl")

        // 2. Afficher le QR dialog sur le main thread
        val cancelled = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            showCfBypassQrDialog(activity, pageUrl) {
                cancelled.complete(true)
            }
        }

        // 3. Poll le résultat avec timeout
        var pollCount = 0
        val m3u8 = try {
            withTimeoutOrNull(QR_BYPASS_TIMEOUT_MS) {
                while (true) {
                    if (cancelled.isCompleted) return@withTimeoutOrNull null
                    pollCount++
                    val result = pollBypassSession(token)
                    if (result != null) {
                        Log.d(TAG, "QR bypass: résultat reçu via D1 (poll #$pollCount): ${result.take(80)}")
                        return@withTimeoutOrNull result
                    }
                    if (pollCount % 5 == 1) Log.d(TAG, "QR bypass: poll #$pollCount en attente (token=${token.take(12)}…)")
                    delay(QR_POLL_INTERVAL_MS)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } finally {
            withContext(Dispatchers.Main) {
                cfQrDialog?.dismiss()
                cfQrDialog = null
            }
        }

        return m3u8
    }

    /** POST /cf-bypass/create {slug} → {token, pageUrl} */
    private fun createBypassSession(slug: String): Pair<String, String>? {
        val body = JSONObject().apply { put("slug", slug) }.toString()
        val req = Request.Builder()
            .url("$WORKER_API_URL/cf-bypass/create")
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "createBypassSession: HTTP ${r.code}")
                    return null
                }
                val json = JSONObject(r.body?.string() ?: return null)
                val token = json.getString("token")
                val pageUrl = json.getString("pageUrl")
                token to pageUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "createBypassSession KO: ${e.message}")
            null
        }
    }

    /** POST /cf-bypass/poll {token} → {status, m3u8Url?} */
    private fun pollBypassSession(token: String): String? {
        val body = JSONObject().apply { put("token", token) }.toString()
        val req = Request.Builder()
            .url("$WORKER_API_URL/cf-bypass/poll")
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val json = JSONObject(r.body?.string() ?: return null)
                if (json.optString("status") == "resolved") json.getString("m3u8Url") else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "pollBypassSession: ${e.message}")
            null
        }
    }

    /**
     * Dialog QR code adapté TV (Leanback) — taille responsive, focusable pour la télécommande.
     * Le QR pointe vers la page Worker (HTTPS, pas deep link → fonctionne partout).
     */
    private fun showCfBypassQrDialog(
        activity: android.app.Activity,
        qrContent: String,
        onCancel: () -> Unit,
    ) {
        val dm = activity.resources.displayMetrics
        val density = dm.density
        val dialogWidth = (dm.widthPixels * 0.60f).toInt()
        val qrSize = minOf(
            (dialogWidth - (density * 64).toInt()).coerceAtLeast((density * 200).toInt()),
            (dm.heightPixels * 0.45f).toInt().coerceAtLeast((density * 200).toInt()),
        )
        val bitmap = try { QrUtils.generate(qrContent, qrSize) } catch (e: Exception) {
            Log.e(TAG, "QR generation failed: ${e.message}")
            onCancel()
            return
        }

        val imageView = android.widget.ImageView(activity).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(android.graphics.Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            val pad = (density * 8).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val instructionsView = android.widget.TextView(activity).apply {
            text = "Cloudflare bloqué sur TV.\nScannez ce QR code avec n'importe quel téléphone\npour débloquer la chaîne."
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, (density * 12).toInt(), 0, 0)
        }

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(
                (density * 24).toInt(),
                (density * 16).toInt(),
                (density * 24).toInt(),
                (density * 12).toInt(),
            )
            isFocusable = true
            isFocusableInTouchMode = true
        }
        container.addView(imageView, android.widget.LinearLayout.LayoutParams(qrSize, qrSize).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        container.addView(instructionsView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        cfQrDialog = android.app.AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Scan QR — Cloudflare")
            .setView(container)
            .setCancelable(true)
            .setNegativeButton("Annuler") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()

        cfQrDialog?.show()
        cfQrDialog?.window?.setLayout(dialogWidth, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        container.requestFocus()
    }
}
