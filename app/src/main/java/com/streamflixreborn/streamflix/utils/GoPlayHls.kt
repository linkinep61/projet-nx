package com.streamflixreborn.streamflix.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 2026-10-30 (user : GoPlay « la vidéo que tu as lancée est noire et n'a pas de son », puis
 * « quand on remet au début, ça devient rouge ») — HLS fMP4 du serveur Youku de GoPlay.
 * Relevé dans le navigateur :
 *   · la playlist média déclare `#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="identity",…` et le
 *     segment d'init déclare un chiffrement `cbcs` (encv/enca + sinf/tenc, pssh Widevine) ;
 *   · MAIS les échantillons sont EN CLAIR : décodés tels quels avec WebCodecs (image nette, son
 *     correct, segments 1 à 4) — et les « déchiffrer » les abîme.
 *   ExoPlayer, lui, croit le flux chiffré : image noire / « ERROR_DRM_CANNOT_HANDLE ».
 *   · les playlists (`/stream/…m3u8?d=…`) ne servent qu'UNE fois : un 2e appel = 403.
 *
 * Cette source, branchée uniquement sur les flux GoPlay (Video.drmType = "goplay-hls") :
 *   1. garde chaque playlist lue et la ressert aux appels suivants (relance, retour au début) ;
 *   2. relève l'adresse des segments d'init (#EXT-X-MAP) et y retire la déclaration de
 *      chiffrement : `encv`/`enca` → format d'origine (`frma`), `sinf` et `pssh` → `free`
 *      (mêmes tailles, rien à décaler). Les segments médias passent tels quels.
 */
@UnstableApi
object GoPlayHls {
    private const val TAG = "GoPlayHls"

    private val segmentsInit = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Playlists déjà lues (URL → contenu), les 12 dernières. */
    private val cachePlaylists = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 12
    }
    private val MAP = Regex("""#EXT-X-MAP:[^\n]*URI="([^"]+)"""")
    /** User-Agent du flux en cours (celui qui lit les playlists), posé par GoPlay.video. */
    @Volatile var uaFlux: String? = null

    /** Segments hors goplay.su (ex. bpcdn.viu.com) : client sans cookies ni en-têtes GoPlay. */
    private val clientExterne by lazy {
        NetworkClient.default.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    private fun indexDe(d: ByteArray, motif: String, depuis: Int = 0): Int {
        val m = motif.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in depuis..d.size - m.size) { for (j in m.indices) if (d[i + j] != m[j]) continue@outer; return i }
        return -1
    }

    /** Segment d'init : retire la déclaration de chiffrement (les échantillons sont en clair). */
    private fun nettoyerInit(src: ByteArray): ByteArray {
        val d = src.copyOf()
        val f = indexDe(d, "frma")
        val estVideo = indexDe(d, "encv") >= 0
        val origine = if (f >= 0) String(d, f + 4, 4, Charsets.ISO_8859_1) else if (estVideo) "avc1" else "mp4a"
        var change = false
        for (enc in listOf("encv", "enca")) { val e = indexDe(d, enc); if (e >= 0) { origine.toByteArray(Charsets.ISO_8859_1).copyInto(d, e); change = true } }
        for (boite in listOf("sinf", "pssh")) {
            var p = indexDe(d, boite)
            while (p >= 0) { "free".toByteArray().copyInto(d, p); change = true; p = indexDe(d, boite, p + 4) }
        }
        if (change) Log.d(TAG, "init ${if (estVideo) "vidéo" else "audio"} : chiffrement déclaré retiré (format $origine)")
        return d
    }

    class Source(private val amont: DataSource) : DataSource {
        private var memoire: ByteArray? = null
        private var pos = 0
        private var uri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = amont.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            val u = dataSpec.uri.toString()
            val playlist = dataSpec.uri.path?.endsWith(".m3u8") == true
            val init = u in segmentsInit
            // 2026-10-30 (Oppo, film GoPlay « The First Ride » : serveur s2 → segments .ts chez
            //   bpcdn.viu.com, TOUS en 403 dans l'appli alors que le même segment répond 200 dans le
            //   navigateur, avec ou sans Referer). Le lecteur leur envoyait les en-têtes prévus pour
            //   goplay.su : Cookie GoPlay, Origin, et sec-ch-ua « Android WebView ». Les segments
            //   hébergés HORS goplay.su sont donc téléchargés ici, avec seulement UA + Referer.
            val hote = dataSpec.uri.host.orEmpty()
            if (!playlist && !init && !hote.endsWith("goplay.su")) {
                val octets = segmentExterne(dataSpec)
                memoire = octets
                pos = dataSpec.position.toInt().coerceIn(0, octets.size)
                val reste = octets.size - pos
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, reste.toLong()) else reste.toLong()
            }
            if (!playlist && !init) {
                memoire = null
                return try { amont.open(dataSpec) } catch (e: Exception) {
                    Log.w(TAG, "échec segment ${dataSpec.uri.host}${dataSpec.uri.path?.takeLast(40)} : ${e.javaClass.simpleName} ${e.message?.take(80)}")
                    throw e
                }
            }
            val enCache = if (playlist) synchronized(cachePlaylists) { cachePlaylists[u] } else null
            val tout = if (enCache != null) {
                Log.d(TAG, "playlist ${dataSpec.uri.lastPathSegment} resservie depuis la mémoire")
                enCache
            } else {
                try {
                    amont.open(dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
                } catch (e: Exception) {
                    Log.w(TAG, "échec ${dataSpec.uri.lastPathSegment} (${if (playlist) "playlist" else "init"}) : ${e.message}"); throw e
                }
                try { lireTout() } finally { amont.close() }.also { lu ->
                    if (playlist) synchronized(cachePlaylists) { cachePlaylists[u] = lu }
                    if (playlist) {
                        val txt = String(lu, Charsets.UTF_8)
                        Log.d(TAG, "DIAG playlist ${dataSpec.uri.lastPathSegment} lue (${lu.size} o, ENDLIST=${txt.contains("#EXT-X-ENDLIST")}, " +
                            "KEY=${Regex("#EXT-X-KEY[^\\n]*").find(txt)?.value?.take(90)}) 1re entrée=${txt.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(90)}")
                    }
                }
            }
            val m = if (playlist) {
                MAP.findAll(String(tout, Charsets.UTF_8)).forEach { segmentsInit.add(resoudre(u, it.groupValues[1])) }
                tout
            } else nettoyerInit(tout)
            memoire = m
            pos = dataSpec.position.toInt().coerceIn(0, m.size)
            val reste = m.size - pos
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, reste.toLong()) else reste.toLong()
        }

        private fun segmentExterne(dataSpec: DataSpec): ByteArray {
            // 2026-10-30 — mesuré sur une adresse de segment refusée dans l'appli, redemandée depuis
            //   le PC : sans en-tête → 200 ; UA seul → 200 ; Origin seul → 200 ; Referer goplay.su
            //   seul → 403. bpcdn.viu.com refuse les liens venant de GoPlay (anti-hotlink) — le site,
            //   lui, n'envoie pas de Referer à Viu. Donc : User-Agent seulement, JAMAIS de Referer.
            val req = okhttp3.Request.Builder().url(dataSpec.uri.toString())
                .header("User-Agent", uaFlux ?: WebViewResolver.STEALTH_UA)
                .build()
            clientExterne.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "segment ${dataSpec.uri.host}${dataSpec.uri.path?.takeLast(30)} : HTTP ${r.code}")
                    throw androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
                        r.code, r.message, null, r.headers.toMultimap(), dataSpec, ByteArray(0))
                }
                return r.body?.bytes() ?: ByteArray(0)
            }
        }

        private fun resoudre(base: String, ref: String): String =
            if (ref.startsWith("http")) ref else java.net.URI(base).resolve(ref).toString()

        private fun lireTout(): ByteArray {
            val sortie = java.io.ByteArrayOutputStream()
            val tampon = ByteArray(16 * 1024)
            while (true) {
                val n = amont.read(tampon, 0, tampon.size)
                if (n == C.RESULT_END_OF_INPUT) break
                sortie.write(tampon, 0, n)
            }
            return sortie.toByteArray()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val m = memoire ?: return amont.read(buffer, offset, length)
            if (pos >= m.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, m.size - pos)
            System.arraycopy(m, pos, buffer, offset, n); pos += n
            return n
        }

        override fun getUri(): Uri? = uri
        override fun getResponseHeaders(): Map<String, List<String>> = if (memoire != null) emptyMap() else amont.responseHeaders
        override fun close() { if (memoire == null) amont.close() else memoire = null }

        class Factory(private val amont: DataSource.Factory) : DataSource.Factory {
            override fun createDataSource(): DataSource = Source(amont.createDataSource())
        }
    }
}
