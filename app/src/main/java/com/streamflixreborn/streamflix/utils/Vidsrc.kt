package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video

/**
 * 2026-10-22 (user : « moviesfree.cv… on essaie de l'ajouter pour voir si on matche du contenu
 * qu'on n'avait pas, gros catalogue, ça nous ferait du VOSTFR traduit en plus ») — lecteur
 * VIDSRC (celui de moviesfree.cv), par identifiant TMDB :
 *   vidsrc.sh/embed/movie/{tmdb}  ·  vidsrc.sh/embed/tv/{tmdb}/{saison}/{épisode}
 *
 * Vérifié au navigateur (Fight Club, Game of Thrones) : audio anglais ; la page fabrique
 * l'adresse de sa playlist par un script chiffré (WASM), mais la playlist elle-même
 * (nectarandnectary…/pl/H4sI…) se lit SANS en-tête particulier, et ses morceaux
 * (« page-N.html ») sont du flux TS ordinaire. Donc : une WebView invisible lance leur lecteur
 * (un toucher au centre, le bouton play est dans un cadre d'un autre site), on capte la
 * playlist, et c'est notre lecteur qui joue. Sous-titres : ceux de l'appli (OpenSubtitles
 * français recalé, sinon traduction).
 */
object Vidsrc {
    private const val TAG = "Vidsrc"
    const val BASE = "https://vidsrc.sh"

    fun lien(tmdb: String, estUnFilm: Boolean, saison: Int, episode: Int): String =
        if (estUnFilm) "$BASE/embed/movie/$tmdb" else "$BASE/embed/tv/$tmdb/${saison.coerceAtLeast(1)}/$episode"

    /**
     * 2026-10-25 (user : « deux sources TMDB qui s'affichent pour tout et n'importe quoi, même s'ils
     * n'ont pas la source ») — Vidsrc était proposé à l'aveugle (ex. WWE Raw S34E01 → « Media not
     * found »). Son lecteur interroge data.vidsrc.sh/api.php (type, tmdb, saison, épisode) qui
     * répond {"status_code":404} quand il n'a rien, 200 + titre quand il l'a (relevé au navigateur :
     * GoT S01E01 et Fight Club → 200 ; WWE Raw S34E01 et GoT saison 99 → 404). Réponse de quelques
     * octets → contrôle léger avant d'afficher le serveur.
     * Retour : true = présent, false = absent (on cache le serveur), null = pas de réponse nette
     * (on garde le serveur, comme avant, pour ne rien perdre si l'API change).
     */
    suspend fun disponible(tmdb: String, estUnFilm: Boolean, saison: Int, episode: Int): Boolean? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = if (estUnFilm) "https://data.vidsrc.sh/api.php?type=movie&tmdb=$tmdb"
                else "https://data.vidsrc.sh/api.php?type=tv&tmdb=$tmdb&season=${saison.coerceAtLeast(1)}&episode=$episode"
            try {
                val client = NetworkClient.default.newBuilder()
                    .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder().url(url)
                    .header("Referer", "$BASE/")
                    .build()
                client.newCall(req).execute().use { r ->
                    val corps = r.body?.string().orEmpty()
                    // 2026-11-24 : l'API renvoie désormais « "status_code":"200" » (texte entre guillemets)
                    //   → on accepte le nombre avec ou sans guillemets.
                    val code = Regex(""""status_code"\s*:\s*"?(\d+)"?""").find(corps)?.groupValues?.get(1)?.toIntOrNull()
                    val res = when (code) {
                        200 -> true
                        404 -> false
                        else -> null
                    }
                    Log.d(TAG, "disponible ${url.substringAfter("api.php?")} → HTTP ${r.code}, status_code=$code → $res")
                    res
                }
            } catch (e: Exception) {
                Log.d(TAG, "disponible : pas de réponse (${e.message}) → serveur gardé")
                null
            }
        }

    suspend fun video(server: Video.Server): Video {
        val r = WebViewStreamResolver.resolve(
            entryUrl = server.src.substringBefore('#'),
            referer = "https://moviesfree.cv/",
            timeoutMs = 25_000L,
            attach = true,
            toucherCentre = true,
            // 2026-11-24 (banc : « flux non capté », Chrome lit) : la liste de lecture ne commence plus
            //   par « /pl/H4sI » (relevé : /pl/lph8…, morceaux déguisés en page-N.html) → on vise
            //   n'importe quel « /pl/ » suivi d'un long identifiant, sans dépendre de son début.
            motifsUrlFlux = listOf(Regex("""/pl/[A-Za-z0-9_\-]{40,}""")),
            clickPlay = true,
            // 2026-10-22 (user : « il arrive vite mais parfois il met du temps à se lancer, comme
            //   s'il arrivait même s'il n'avait pas de contenu ») — Vidsrc est proposé sans savoir si
            //   l'épisode existe chez lui (ex. The Iceblade Sorcerer S02E02, diffusé mais absent). Vérifié
            //   au navigateur : la page prend le titre de l'œuvre (« … · S01 E01 ») quand elle l'a, et
            //   garde « Player » quand elle ne l'a pas. → abandon au bout de 12 s au lieu de 25.
            // 2026-11-24 (banc Zootopie 2, « flux non capté » alors que Chrome lit le film) : l'ancien
            //   motif `(?i)(player|joueur|lecteur)?` (groupe FACULTATIF) correspondait à n'importe quel
            //   titre → abandon systématique à 12 s, même quand la page avait trouvé l'œuvre. On ne vise
            //   plus que le titre resté générique (« Player », ou vide).
            abandonSiTitre = Regex("""(?i)^\s*(player|joueur|lecteur)?\s*$"""),
            delaiAbandonMs = 12_000L,
        ) ?: throw Exception("Vidsrc : flux non capté (titre inconnu ou lecteur indisponible)")
        Log.i(TAG, "flux capté : ${r.url.substringBefore("/pl/")}/pl/… (en-têtes vus : ${r.headers.keys})")
        // 2026-11-13 (bilan des sources : « MoviesFree, lectures réussies 18 % contre 75 %, erreur
        //   principale : refusé ») — la playlist ne se lit PLUS sans en-tête : notre lecteur recevait
        //   403 (vu au banc). On transmet donc ceux que leur propre lecteur envoie (Referer, Origin).
        fun entete(nom: String) = r.headers.entries.firstOrNull { it.key.equals(nom, true) }?.value
        return Video(
            source = r.url,
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOfNotNull(
                "User-Agent" to entete("User-Agent"),
                "Referer" to entete("Referer"),
                "Origin" to entete("Origin"),
            ),
        )
    }

    private fun mapOfNotNull(vararg p: Pair<String, String?>): Map<String, String> =
        p.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
}
