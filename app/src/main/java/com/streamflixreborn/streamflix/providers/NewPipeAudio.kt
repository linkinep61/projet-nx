package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.providers.FileSearchProvider.AudioResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Request as OkRequest

/**
 * NewPipeAudio — 2e source MUSIQUE (2026-07-24) : recherche + extraction AUDIO YouTube via
 * NewPipeExtractor (lib JVM native, PAS d'instance tierce contrairement à Piped → robuste).
 *
 * Complète FileSearch : FileSearch = mp3 bruts d'open-directories ; NewPipe = tout le reste
 * (tout ce qui est sur YouTube = quasi toute la musique FR). Audio seul, pas de vidéo.
 *
 * Flux :
 *   - search(q) → StreamInfoItem (titre/artiste/durée) + url = page watch YouTube (PAS jouable direct).
 *   - resolveAudioUrl(watchUrl) → URL audio DIRECTE (m4a/webm progressif) → jouée par ExoPlayer.
 *     Résolue paresseusement à la lecture (ResolvingDataSource) pour supporter l'enchaînement.
 */
object NewPipeAudio {
    private const val TAG = "NewPipeAudio"
    @Volatile private var initialized = false
    private val resolveCache = ConcurrentHashMap<String, String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    // Downloader NewPipe adossé à OkHttp.
    private class NpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val builder = OkRequest.Builder().url(request.url())
            for ((name, values) in request.headers()) {
                for (v in values) builder.addHeader(name, v)
            }
            val data = request.dataToSend()
            when (val method = request.httpMethod()) {
                "GET" -> builder.get()
                "POST" -> builder.post((data ?: ByteArray(0)).toRequestBody())
                "HEAD" -> builder.head()
                else -> builder.method(method, data?.toRequestBody())
            }
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val headers = HashMap<String, MutableList<String>>()
                for (n in resp.headers.names()) headers[n] = resp.headers.values(n).toMutableList()
                return Response(resp.code, resp.message, headers, body, resp.request.url.toString())
            }
        }
    }

    @Synchronized
    private fun ensureInit() {
        if (initialized) return
        NewPipe.init(NpDownloader(), Localization("fr", "FR"), ContentCountry("FR"))
        initialized = true
    }

    fun isYouTubeUrl(u: String): Boolean =
        u.contains("youtube.com/watch", true) || u.contains("youtu.be/", true) ||
            u.contains("music.youtube.com", true)

    /** Recherche de morceaux (YouTube Music « songs »). url = page watch (résolue à la lecture).
     *  Pagine jusqu'à `limit` (les pages YouTube font ~20 items). */
    suspend fun search(query: String, limit: Int = 150, maxPages: Int = 8): List<AudioResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        try {
            ensureInit()
            val service = ServiceList.YouTube
            val handler = service.searchQHFactory.fromQuery(
                q, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "",
            )
            val extractor = service.getSearchExtractor(handler)
            extractor.fetchPage()
            val out = ArrayList<AudioResult>()
            val seen = HashSet<String>()
            fun consume(items: List<org.schabi.newpipe.extractor.InfoItem>) {
                for (item in items) {
                    if (item !is StreamInfoItem) continue
                    val url = item.url ?: continue
                    if (!seen.add(url)) continue
                    val name = item.name ?: continue
                    val artist = item.uploaderName ?: ""
                    val dur = item.duration
                    val durStr = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else ""
                    val base = if (artist.isNotBlank()) "$name — $artist" else name
                    val title = if (durStr.isNotBlank()) "$base ($durStr)" else base
                    out.add(AudioResult(title = title, url = url, size = "YouTube"))
                    if (out.size >= limit) return
                }
            }
            var page = extractor.initialPage
            consume(page.items)
            var guard = 0
            while (out.size < limit && page.hasNextPage() && guard++ < maxPages) {
                page = extractor.getPage(page.nextPage)
                consume(page.items)
            }
            Log.i(TAG, "search '$q' → ${out.size} morceaux YouTube")
            out
        } catch (e: Exception) {
            Log.w(TAG, "search KO: ${e.message}")
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // 2026-08-13 — VIDÉO (user : « fusionner les 2 pour avoir plus de contenu d'un coup »).
    //   Les résultats YouTube rejoignent la grille du dossier Rutube. Rien de neuf côté
    //   infrastructure : même NewPipe, même downloader, même init — seulement la recherche
    //   « vidéos » (au lieu de « morceaux ») et l'extraction du flux VIDÉO.
    //
    //   ⚠ PAS DE PUB : on ne charge jamais le lecteur YouTube (c'est LUI qui insère les
    //   coupures) — on lit l'URL du fichier servi par le CDN. Même principe que Rutube.
    //
    //   ⚠ On prend les flux « progressifs » (image + son dans UN seul fichier), que YouTube
    //   plafonne à 360p. C'est volontaire pour cette 1ʳᵉ version : lecture immédiate, aucun
    //   assemblage de pistes. Le HD demanderait de faire fusionner vidéo et audio séparés
    //   par ExoPlayer — à faire seulement si la définition gêne à l'usage.
    // ══════════════════════════════════════════════════════════════════════════════════

    /** Un résultat vidéo YouTube prêt pour la grille du dossier. */
    /** Dernière panne de l'extracteur NewPipe (null = il va bien). Alimente la fiche
     *  de diagnostic du dossier — on la garde même quand le secours a sauvé la mise. */
    @Volatile var derniereErreurNewPipe: Throwable? = null

    /**
     * ── RECHERCHE DE SECOURS (2026-08-14) ────────────────────────────────────────────
     * Même résultat que NewPipe, mais avec les seuls outils qui marchent partout :
     * une requête HTTP et du JSON (comme Rutube, qui lui n'a jamais posé problème).
     * On lit `ytInitialData`, la structure que la page de résultats embarque déjà.
     * Aucun appel à l'extracteur, donc rien qui puisse manquer sur une vieille ROM.
     */
    private fun rechercheSecours(query: String, limit: Int): List<VideoResult> {
        val url = "https://www.youtube.com/results?search_query=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&hl=fr&gl=FR"
        val req = OkRequest.Builder().url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            )
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("Cookie", "CONSENT=YES+cb")  // évite la page de consentement UE
            .build()
        val html = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            r.body?.string() ?: return emptyList()
        }
        val marqueur = "ytInitialData = "
        val debut = html.indexOf(marqueur).takeIf { it >= 0 }?.plus(marqueur.length)
            ?: return emptyList()
        val fin = html.indexOf(";</script>", debut).takeIf { it > debut } ?: return emptyList()
        val racine = org.json.JSONObject(html.substring(debut, fin).trim())
        val sections = racine.optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return emptyList()
        val out = ArrayList<VideoResult>()
        for (i in 0 until sections.length()) {
            val items = sections.optJSONObject(i)
                ?.optJSONObject("itemSectionRenderer")
                ?.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                val v = items.optJSONObject(j)?.optJSONObject("videoRenderer") ?: continue
                val id = v.optString("videoId").takeIf { it.length == 11 } ?: continue
                val titre = v.optJSONObject("title")
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    .orEmpty()
                    .ifBlank {
                        v.optJSONObject("title")?.optJSONObject("accessibility")
                            ?.optJSONObject("accessibilityData")?.optString("label").orEmpty()
                    }
                if (titre.isBlank()) continue
                val auteur = v.optJSONObject("ownerText")
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                // "3:42" ou "1:02:03" → secondes
                val secondes = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                    .split(":").mapNotNull { it.trim().toIntOrNull() }
                    .fold(0) { acc, n -> acc * 60 + n }
                out.add(
                    VideoResult(
                        videoId = id,
                        title = titre,
                        author = auteur,
                        durationS = secondes,
                        thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    ),
                )
                if (out.size >= limit) {
                    Log.i(TAG, "recherche de secours '$query' → ${out.size} vidéos")
                    return out
                }
            }
        }
        Log.i(TAG, "recherche de secours '$query' → ${out.size} vidéos")
        return out
    }

    data class VideoResult(
        val videoId: String,
        val title: String,
        val author: String,
        val durationS: Int,
        val thumbnail: String,
    )

    /** Identifiant de la vidéo depuis une URL watch (v=… ou youtu.be/…). */
    fun videoIdOf(watchUrl: String): String? =
        Regex("(?:v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})").find(watchUrl)?.groupValues?.get(1)

    /** Recherche VIDÉOS YouTube (clips, films, extraits…), paginée. */
    suspend fun searchVideos(query: String, limit: Int = 60, maxPages: Int = 3): List<VideoResult> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isBlank()) return@withContext emptyList()
            try {
                ensureInit()
                val service = ServiceList.YouTube
                val handler = service.searchQHFactory.fromQuery(
                    q, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "",
                )
                val extractor = service.getSearchExtractor(handler)
                extractor.fetchPage()
                val out = ArrayList<VideoResult>()
                val seen = HashSet<String>()
                fun consume(items: List<org.schabi.newpipe.extractor.InfoItem>) {
                    for (item in items) {
                        if (item !is StreamInfoItem) continue
                        val url = item.url ?: continue
                        val vid = videoIdOf(url) ?: continue
                        if (!seen.add(vid)) continue
                        out.add(
                            VideoResult(
                                videoId = vid,
                                title = item.name ?: continue,
                                author = item.uploaderName ?: "",
                                durationS = item.duration.toInt(),
                                // Vignette DÉDUITE de l'identifiant : URL stable, aucune
                                //   dépendance à l'API d'images de la bibliothèque.
                                thumbnail = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                            ),
                        )
                        if (out.size >= limit) return
                    }
                }
                var page = extractor.initialPage
                consume(page.items)
                var guard = 0
                while (out.size < limit && page.hasNextPage() && guard++ < maxPages) {
                    page = extractor.getPage(page.nextPage)
                    consume(page.items)
                }
                Log.i(TAG, "searchVideos '$q' → ${out.size} vidéos YouTube")
                derniereErreurNewPipe = null
                if (out.isEmpty()) rechercheSecours(q, limit) else out
            } catch (t: Throwable) {
                // Throwable et pas Exception : sur certaines ROMs (un Samsung et un vieil
                //   Oppo constatés par le user le 2026-08-14) l'extracteur peut lever une
                //   Error — elle traversait tous les catch et tuait l'appli.
                //   On garde la trace (fiche de diagnostic, appui long sur l'icône de
                //   source) ET on bascule sur la recherche de secours, qui n'utilise que
                //   HTTP + JSON : ces appareils gardent donc YouTube au lieu de n'avoir rien.
                derniereErreurNewPipe = t
                Log.w(TAG, "searchVideos KO (${t.javaClass.simpleName}) → recherche de secours", t)
                try {
                    rechercheSecours(q, limit)
                } catch (t2: Throwable) {
                    Log.w(TAG, "recherche de secours KO: ${t2.javaClass.simpleName} ${t2.message}")
                    emptyList()
                }
            }
        }

    // ══════════════════════════════════════════════════════════════════════════════════
    // HD / 4K (2026-08-13, 2ᵉ tentative — user : « pourquoi tu répares pas pour qu'il
    //   accepte le 4K ? »)
    //
    //   La HD n'existe chez YouTube que sous forme de pistes SÉPARÉES (image seule + son
    //   seul). On décrit donc les deux dans un petit manifeste DASH local qu'ExoPlayer
    //   assemble — le module DASH est déjà embarqué dans l'app.
    //
    //   1ʳᵉ tentative : rejet « Source error » en 28 ms, donc AVANT tout accès réseau →
    //   le manifeste lui-même était invalide. Deux causes possibles, toutes deux
    //   silencieuses, maintenant traitées :
    //     • une DURÉE à zéro (`se.length` pas toujours renseigné) → chronologie vide ;
    //     • des PLAGES D'OCTETS à -1 (NewPipe les laisse ainsi quand il ne les connaît
    //       pas) → `range="-1-…"` illisible.
    //   On valide donc les quatre bornes ET la durée, et on journalise le manifeste
    //   produit : si ça échoue encore, le log dit exactement pourquoi.
    // ══════════════════════════════════════════════════════════════════════════════════

    private val manifestCache = ConcurrentHashMap<String, String>()

    /** User-Agent de l'app iOS — les flux rendus par la voie de secours attendent celui-ci. */
    const val UA_IOS = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X; fr_FR)"

    /**
     * ── LECTURE DE SECOURS (2026-08-14) ──────────────────────────────────────────────
     * user : « sur Bbox 4K la recherche ne crashe plus, mais lancer une vidéo YouTube
     * fait planter l'appli — pas avec Rutube. » Le crash est instantané : c'est
     * l'extracteur qui tombe (Error) avant tout accès réseau.
     * Ce chemin-ci ne l'utilise pas du tout : une requête HTTP à l'API interne de
     * YouTube, en se présentant comme l'application iOS, rend un manifeste HLS
     * adaptatif (toutes les qualités, jusqu'au 1080p+) qu'ExoPlayer lit nativement.
     * Que du HTTP et du JSON — comme Rutube, qui n'a jamais posé problème nulle part.
     * @return (url à jouer, type de flux) ou null si YouTube refuse.
     */
    /**
     * ── IDENTITÉS TENTÉES, DANS CET ORDRE (2026-08-15) ──────────────────────────────
     * Mesuré depuis la ligne du user, en demandant trois morceaux d'une même vidéo :
     *   • iPhone      : 1ᵉʳ morceau OK (206), les suivants REFUSÉS (403)
     *   • casque VR   : les trois morceaux OK (206), y compris la fin du fichier
     * D'où la coupure vers la minute avec l'identité iPhone : YouTube ne sert que le
     * début aux clients qu'il n'authentifie pas complètement. Le casque VR, lui, donne
     * accès au fichier entier — c'est donc lui qu'on essaie en premier.
     * Il exige en revanche un « visitorData » (cf. `visiteur()`), sans quoi il répond
     * « connectez-vous pour confirmer que vous n'êtes pas un robot ».
     */
    private val CLIENTS_SECOURS: List<Triple<String, String, String>> = listOf(
        // nom interne, version, User-Agent
        Triple("ANDROID_VR", "1.62.27",
            "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12; GB) gzip"),
        Triple("IOS", "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X; fr_FR)"),
        Triple("MWEB", "2.20240726.00.00",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile Safari/604.1"),
    )

    /** Jeton de visiteur exigé par le client VR. Récupéré une fois, gardé pour la session. */
    @Volatile private var visiteurCache: String? = null

    private fun visiteur(): String? {
        visiteurCache?.let { return it }
        return try {
            val req = OkRequest.Builder()
                .url("https://www.youtube.com/watch?v=jNQXAC9IVRw&hl=fr")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                )
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .header("Cookie", "CONSENT=YES+cb")
                .build()
            val html = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string() ?: return null
            }
            val v = Regex("\"visitorData\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?.replace("\\u0026", "&")
            if (v.isNullOrBlank()) {
                Log.w(TAG, "visitorData introuvable")
                null
            } else {
                visiteurCache = v
                Log.i(TAG, "visitorData obtenu (${v.length} caractères)")
                v
            }
        } catch (t: Throwable) {
            Log.w(TAG, "visitorData KO: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun contexteClient(nom: String, version: String): String = when (nom) {
        "IOS" -> """"clientName":"IOS","clientVersion":"$version","deviceMake":"Apple",""" +
            """"deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22D82""""
        "ANDROID_VR" -> """"clientName":"ANDROID_VR","clientVersion":"$version","deviceMake":"Oculus",""" +
            """"deviceModel":"Quest 3","osName":"Android","osVersion":"12","androidSdkVersion":32"""
        else -> """"clientName":"$nom","clientVersion":"$version""""
    }

    /**
     * ── LECTURE DE SECOURS (2026-08-14) ──────────────────────────────────────────────
     * user : « sur Bbox 4K, lancer une vidéo YouTube fait planter l'appli — pas avec
     * Rutube », puis « ça ne plante plus mais les vidéos ne se lisent pas ».
     * L'extracteur tombe sur ces appareils : ce chemin-ci ne l'utilise pas du tout.
     * On demande le flux à l'API interne de YouTube en se présentant successivement
     * comme plusieurs clients officiels — YouTube n'ouvre pas ses flux à n'importe
     * lequel, et son humeur varie selon l'appareil qui demande. Le premier qui répond
     * gagne ; sinon on garde par écrit ce que chacun a répondu, pour le diagnostic.
     * Que du HTTP et du JSON — comme Rutube, qui marche partout.
     * @return (url à jouer, type de flux) ou null si tous ont refusé.
     */
    /**
     * Assemble un manifeste DASH à partir des pistes séparées rendues par l'API interne de
     * YouTube (JSON), sans passer par l'extracteur. Même principe que la voie normale, mais
     * les bornes d'octets viennent du JSON (`initRange` / `indexRange`) au lieu de la lib.
     * H.264 + AAC uniquement : décodés en matériel par toutes les box et tablettes.
     */
    private fun manifesteDepuisPistesSeparees(
        videoId: String,
        flux: org.json.JSONObject,
        racine: org.json.JSONObject,
    ): String? {
        return try {
            val pistes = flux.optJSONArray("adaptiveFormats") ?: return null
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            data class Piste(
                val url: String, val codecs: String, val bitrate: Int,
                val largeur: Int, val hauteur: Int, val fps: Int, val echantillonnage: Int,
                val initDebut: Long, val initFin: Long, val idxDebut: Long, val idxFin: Long,
            )
            val videos = ArrayList<Piste>()
            val audios = ArrayList<Piste>()
            for (i in 0 until pistes.length()) {
                val f = pistes.optJSONObject(i) ?: continue
                val url = f.optString("url")
                if (url.isBlank()) continue                    // piste chiffrée → inutilisable ici
                val mime = f.optString("mimeType")
                // mimeType ressemble à : video/mp4; codecs="avc1.4d401f"
                val codecs = mime.substringAfter("codecs=", "").trim('"', ' ')
                val init = f.optJSONObject("initRange") ?: continue
                val idx = f.optJSONObject("indexRange") ?: continue
                val piste = Piste(
                    url = url,
                    codecs = codecs,
                    bitrate = f.optInt("bitrate", 0),
                    largeur = f.optInt("width", 0),
                    hauteur = f.optInt("height", 0),
                    fps = f.optInt("fps", 30),
                    echantillonnage = f.optString("audioSampleRate").toIntOrNull() ?: 44100,
                    initDebut = init.optString("start").toLongOrNull() ?: -1,
                    initFin = init.optString("end").toLongOrNull() ?: -1,
                    idxDebut = idx.optString("start").toLongOrNull() ?: -1,
                    idxFin = idx.optString("end").toLongOrNull() ?: -1,
                )
                if (piste.initDebut < 0 || piste.initFin <= piste.initDebut ||
                    piste.idxDebut < 0 || piste.idxFin <= piste.idxDebut
                ) continue                                     // bornes inutilisables
                when {
                    mime.startsWith("video/mp4") && codecs.startsWith("avc") -> videos.add(piste)
                    mime.startsWith("audio/mp4") && codecs.contains("mp4a") -> audios.add(piste)
                }
            }
            val audio = audios.maxByOrNull { it.bitrate } ?: return null
            val listeVideo = videos.sortedBy { it.hauteur }
            if (listeVideo.isEmpty()) return null
            val dureeS = (
                flux.optJSONArray("adaptiveFormats")?.optJSONObject(0)
                    ?.optString("approxDurationMs")?.toLongOrNull()?.div(1000)
                    ?: racine.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull()
                    ?: 0L
                )
            if (dureeS <= 0) return null
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append(
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
                    "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" " +
                    "mediaPresentationDuration=\"PT${dureeS}S\" minBufferTime=\"PT1.5S\">\n<Period>\n",
            )
            sb.append("<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\" subsegmentAlignment=\"true\">\n")
            for ((i, v) in listeVideo.withIndex()) {
                sb.append(
                    "<Representation id=\"v$i\" codecs=\"${v.codecs}\" " +
                        "bandwidth=\"${if (v.bitrate > 0) v.bitrate else 800000}\" " +
                        "width=\"${v.largeur}\" height=\"${v.hauteur}\" " +
                        "frameRate=\"${if (v.fps > 0) v.fps else 30}\">\n",
                )
                sb.append("<BaseURL>${esc(v.url)}</BaseURL>\n")
                sb.append("<SegmentBase indexRange=\"${v.idxDebut}-${v.idxFin}\">\n")
                sb.append("<Initialization range=\"${v.initDebut}-${v.initFin}\"/>\n")
                sb.append("</SegmentBase>\n</Representation>\n")
            }
            sb.append("</AdaptationSet>\n")
            sb.append("<AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\" subsegmentAlignment=\"true\">\n")
            sb.append(
                "<Representation id=\"a0\" codecs=\"${audio.codecs.ifBlank { "mp4a.40.2" }}\" " +
                    "bandwidth=\"${if (audio.bitrate > 0) audio.bitrate else 128000}\" " +
                    "audioSamplingRate=\"${audio.echantillonnage}\">\n",
            )
            sb.append(
                "<AudioChannelConfiguration " +
                    "schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\"/>\n",
            )
            sb.append("<BaseURL>${esc(audio.url)}</BaseURL>\n")
            sb.append("<SegmentBase indexRange=\"${audio.idxDebut}-${audio.idxFin}\">\n")
            sb.append("<Initialization range=\"${audio.initDebut}-${audio.initFin}\"/>\n")
            sb.append("</SegmentBase>\n</Representation>\n</AdaptationSet>\n")
            sb.append("</Period>\n</MPD>\n")
            val f = java.io.File(
                com.streamflixreborn.streamflix.StreamFlixApp.instance.cacheDir,
                "yt_secours_$videoId.mpd",
            )
            f.writeText(sb.toString())
            Log.i(TAG, "secours DASH $videoId : ${listeVideo.size} pistes vidéo " +
                "(max ${listeVideo.last().hauteur}p), durée ${dureeS}s")
            android.net.Uri.fromFile(f).toString()
        } catch (t: Throwable) {
            Log.w(TAG, "manifeste de secours KO: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    fun lectureSecours(videoId: String): Triple<String, String, String>? {
        val journal = StringBuilder()
        journal.append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
            .append(" — Android ").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        derniereErreurNewPipe?.let {
            journal.append("extracteur : ").append(it.javaClass.simpleName)
                .append(" — ").append(it.message).append('\n')
        }
        // 2026-08-15 : si le jeton de visiteur est périmé ou refusé, le client VR répond
        //   « connectez-vous… » et on retombait SILENCIEUSEMENT sur l'identité iPhone —
        //   c'est-à-dire sur la coupure à une minute, sans que personne comprenne pourquoi.
        //   On lui laisse donc une seconde chance avec un jeton tout neuf avant de passer
        //   au client suivant.
        // Le client VR est tenté DEUX fois : si son jeton de visiteur était périmé, le
        //   premier essai l'invalide et le second repart avec un jeton neuf. Sans ça on
        //   retombait silencieusement sur l'identité iPhone, donc sur la coupure à une minute.
        val essais = listOf(CLIENTS_SECOURS.first()) + CLIENTS_SECOURS
        for ((nom, version, ua) in essais) {
            try {
                // Le client VR n'est servi que si la requête présente un jeton de visiteur.
                val jeton = if (nom == "ANDROID_VR") visiteur() else null
                val champVisiteur = jeton?.let { ",\"visitorData\":\"" + it + "\"" } ?: ""
                val corps = """{"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,""" +
                    """"context":{"client":{${contexteClient(nom, version)},""" +
                    """"hl":"fr","gl":"FR","utcOffsetMinutes":0$champVisiteur}}}"""
                val req = OkRequest.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .post(corps.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", ua)
                    .header("X-YouTube-Client-Version", version)
                    .header("Accept-Language", "fr-FR,fr;q=0.9")
                    .apply { jeton?.let { header("X-Goog-Visitor-Id", it) } }
                    .build()
                val txt = client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        journal.append(nom).append(" : HTTP ").append(r.code).append('\n')
                        return@use null
                    }
                    r.body?.string()
                } ?: continue
                val racine = org.json.JSONObject(txt)
                val flux = racine.optJSONObject("streamingData")
                if (flux == null) {
                    val st = racine.optJSONObject("playabilityStatus")
                    val statut = st?.optString("status").orEmpty()
                    journal.append(nom).append(" : ").append(statut)
                        .append(" — ").append(st?.optString("reason")).append('\n')
                    if (nom == "ANDROID_VR" && statut == "LOGIN_REQUIRED") {
                        visiteurCache = null            // jeton périmé → on en redemande un
                        Log.w(TAG, "client VR refusé → nouveau jeton de visiteur au prochain essai")
                    }
                    continue
                }
                flux.optString("hlsManifestUrl").takeIf { it.isNotBlank() }?.let {
                    journal.append(nom).append(" : OK (HLS adaptatif)\n")
                    Log.i(TAG, "lecture de secours $videoId → HLS via $nom")
                    return Triple(it, androidx.media3.common.MimeTypes.APPLICATION_M3U8, ua)
                }
                // 2026-08-15 — LE CAS RÉEL, mesuré sur la tablette du user (MediaPad M5,
                //   Android 9) : le client iPhone répond « OK » mais ne donne NI manifeste
                //   HLS NI fichier tout-en-un — uniquement des PISTES SÉPARÉES (image seule
                //   + son seul), 27 d'entre elles avec une adresse directe. Je ne regardais
                //   que les deux premières formes, d'où « flux présent mais aucune URL ».
                //   On assemble donc ces pistes dans un petit manifeste DASH local, comme le
                //   fait déjà la voie normale pour la HD.
                manifesteDepuisPistesSeparees(videoId, flux, racine)?.let {
                    journal.append(nom).append(" : OK (pistes séparées → DASH)\n")
                    Log.i(TAG, "lecture de secours $videoId → DASH via $nom")
                    return Triple(it, androidx.media3.common.MimeTypes.APPLICATION_MPD, ua)
                }
                val formats = flux.optJSONArray("formats")
                var meilleur: String? = null
                var hauteur = -1
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.optJSONObject(i) ?: continue
                        val u = f.optString("url")
                        if (u.isBlank()) continue
                        val h = f.optInt("height")
                        if (h > hauteur) { hauteur = h; meilleur = u }
                    }
                }
                if (meilleur != null) {
                    journal.append(nom).append(" : OK (mp4 ").append(hauteur).append("p)\n")
                    Log.i(TAG, "lecture de secours $videoId → mp4 ${hauteur}p via $nom")
                    return Triple(meilleur, androidx.media3.common.MimeTypes.VIDEO_MP4, ua)
                }
                journal.append(nom).append(" : flux présent mais aucune URL directe\n")
            } catch (t: Throwable) {
                journal.append(nom).append(" : ").append(t.javaClass.simpleName)
                    .append(" — ").append(t.message).append('\n')
            }
        }
        Log.w(TAG, "lecture de secours $videoId : tous les clients ont refusé\n$journal")
        return null
    }

    /** @return (uri à jouer, vrai si c'est un manifeste DASH) — repli 360p si HD impossible. */
    fun resolveVideoPlayable(videoId: String): Pair<String, Boolean>? {
        manifestCache[videoId]?.let { return it to true }
        try {
            ensureInit()
            val se = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            se.fetchPage()
            val duree = se.length
            // H.264 uniquement : décodé en matériel par toutes les box, Chromecast comprise.
            val videos = se.videoOnlyStreams
                .filter { !it.content.isNullOrBlank() && (it.codec ?: "").startsWith("avc") }
                .sortedBy { it.height }
            val audio = se.audioStreams
                .filter { !it.content.isNullOrBlank() && (it.codec ?: "").contains("mp4a") }
                .maxByOrNull { it.averageBitrate }

            if (duree <= 0) {
                Log.w(TAG, "HD $videoId : durée inconnue ($duree) → repli 360p")
            } else if (videos.isEmpty() || audio == null) {
                Log.w(TAG, "HD $videoId : pistes séparées absentes → repli 360p")
            } else {
                val mpd = construireManifesteDash(videos, audio, duree)
                if (mpd != null) {
                    val f = java.io.File(
                        com.streamflixreborn.streamflix.StreamFlixApp.instance.cacheDir,
                        "yt_$videoId.mpd",
                    )
                    f.writeText(mpd)
                    val uri = android.net.Uri.fromFile(f).toString()
                    manifestCache[videoId] = uri
                    Log.i(TAG, "HD $videoId → DASH ${videos.last().height}p max, durée ${duree}s")
                    return uri to true
                }
            }
        } catch (t: Throwable) {
            // Throwable : sur certaines box/ROMs (Bbox 4K, Samsung, vieil Oppo — 2026-08-14)
            //   l'extracteur lève une Error DÈS le lancement de la vidéo. Elle traversait
            //   tous les catch et tuait l'appli instantanément. Ici elle est absorbée :
            //   l'appelant bascule alors sur la lecture de secours.
            derniereErreurNewPipe = t
            Log.w(TAG, "HD $videoId KO: ${t.javaClass.simpleName} ${t.message} → repli")
        }
        return try {
            resolveVideoUrl(videoId)?.let { it to false }
        } catch (t: Throwable) {
            derniereErreurNewPipe = t
            Log.w(TAG, "repli 360p KO: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun construireManifesteDash(
        videos: List<org.schabi.newpipe.extractor.stream.VideoStream>,
        audio: AudioStream,
        dureeS: Long,
    ): String? {
      return try {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        /** Les 4 bornes doivent être connues ET cohérentes, sinon la piste est inutilisable. */
        fun bornesValides(iS: Int, iE: Int, xS: Int, xE: Int) =
            iS >= 0 && iE > iS && xS >= 0 && xE > xS
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
                "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" " +
                "mediaPresentationDuration=\"PT${dureeS}S\" minBufferTime=\"PT1.5S\">\n<Period>\n",
        )
        sb.append("<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\" subsegmentAlignment=\"true\">\n")
        var nbVideo = 0
        for ((i, v) in videos.withIndex()) {
            val t = v.itagItem ?: continue
            if (!bornesValides(t.initStart, t.initEnd, t.indexStart, t.indexEnd)) {
                Log.w(TAG, "  piste ${t.height}p écartée : bornes ${t.initStart}-${t.initEnd}/${t.indexStart}-${t.indexEnd}")
                continue
            }
            sb.append(
                "<Representation id=\"v$i\" codecs=\"${t.codec ?: "avc1.4d401f"}\" " +
                    "bandwidth=\"${if (t.bitrate > 0) t.bitrate else 800000}\" " +
                    "width=\"${t.width}\" height=\"${t.height}\" " +
                    "frameRate=\"${if (t.fps > 0) t.fps else 30}\">\n",
            )
            sb.append("<BaseURL>${esc(v.content)}</BaseURL>\n")
            sb.append("<SegmentBase indexRange=\"${t.indexStart}-${t.indexEnd}\">\n")
            sb.append("<Initialization range=\"${t.initStart}-${t.initEnd}\"/>\n")
            sb.append("</SegmentBase>\n</Representation>\n")
            nbVideo++
        }
        sb.append("</AdaptationSet>\n")
        if (nbVideo == 0) { Log.w(TAG, "aucune piste vidéo exploitable"); return null }

        val a = audio.itagItem ?: return null
        if (!bornesValides(a.initStart, a.initEnd, a.indexStart, a.indexEnd)) {
            Log.w(TAG, "piste audio écartée : bornes ${a.initStart}-${a.initEnd}/${a.indexStart}-${a.indexEnd}")
            return null
        }
        sb.append("<AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\" subsegmentAlignment=\"true\">\n")
        sb.append(
            "<Representation id=\"a0\" codecs=\"${a.codec ?: "mp4a.40.2"}\" " +
                "bandwidth=\"${if (a.bitrate > 0) a.bitrate else 128000}\" " +
                "audioSamplingRate=\"${if (a.sampleRate > 0) a.sampleRate else 44100}\">\n",
        )
        sb.append(
            "<AudioChannelConfiguration " +
                "schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\"/>\n",
        )
        sb.append("<BaseURL>${esc(audio.content)}</BaseURL>\n")
        sb.append("<SegmentBase indexRange=\"${a.indexStart}-${a.indexEnd}\">\n")
        sb.append("<Initialization range=\"${a.initStart}-${a.initEnd}\"/>\n")
        sb.append("</SegmentBase>\n</Representation>\n</AdaptationSet>\n")
        sb.append("</Period>\n</MPD>\n")
        sb.toString()
      } catch (e: Exception) {
        Log.w(TAG, "construireManifesteDash KO: ${e.message}"); null
      }
    }

    /** Résout un identifiant vidéo → URL de flux DIRECTE (image + son). Caché en session. */
    fun resolveVideoUrl(videoId: String): String? {
        val watch = "https://www.youtube.com/watch?v=$videoId"
        resolveCache["v::$videoId"]?.let { return it }
        return try {
            ensureInit()
            val se = ServiceList.YouTube.getStreamExtractor(watch)
            se.fetchPage()
            val muxed = se.videoStreams.filter { !it.content.isNullOrBlank() }
            val best = muxed.maxByOrNull { it.height }
            val url = best?.content
            if (!url.isNullOrBlank()) {
                resolveCache["v::$videoId"] = url
                Log.i(TAG, "resolveVideoUrl $videoId → ${best.height}p")
                url
            } else {
                Log.w(TAG, "resolveVideoUrl $videoId : aucun flux progressif")
                null
            }
        } catch (t: Throwable) {
            derniereErreurNewPipe = t
            Log.w(TAG, "resolveVideoUrl KO: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    /** Résout une URL watch YouTube → URL audio DIRECTE (m4a/webm progressif). Caché en session. */
    fun resolveAudioUrl(watchUrl: String): String? {
        resolveCache[watchUrl]?.let { return it }
        return try {
            ensureInit()
            val se = ServiceList.YouTube.getStreamExtractor(watchUrl)
            se.fetchPage()
            val audios: List<AudioStream> = se.audioStreams
            val progressive = audios.filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank()
            }
            val pool = progressive.ifEmpty { audios.filter { !it.content.isNullOrBlank() } }
            val best = pool.maxByOrNull { it.averageBitrate }
            val url = best?.content
            if (!url.isNullOrBlank()) { resolveCache[watchUrl] = url; url } else null
        } catch (e: Exception) {
            Log.w(TAG, "resolve KO: ${e.message}")
            null
        }
    }
}
