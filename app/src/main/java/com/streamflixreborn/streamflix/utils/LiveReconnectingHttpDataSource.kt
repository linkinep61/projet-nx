package com.streamflixreborn.streamflix.utils

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * 2026-05-10 : DataSource wrapper qui reconnecte automatiquement sur EOF
 * pour les URLs live MPEG-TS (Xtream-codes Vegeta/Ola/WiTv...).
 *
 * Problème : ces serveurs envoient `Connection: close` après ~1-2 secondes
 * de données. ExoPlayer ProgressiveMediaSource reçoit EOF → STATE_ENDED.
 * VLC et les autres lecteurs gèrent ça en réouvrant automatiquement une
 * nouvelle requête HTTP. Ce wrapper fait pareil.
 *
 * Activation : on détecte au moment du `open()` si l'URL est une live
 * progressive (contient /live/ et finit par .ts). Si oui, sur EOF on
 * ferme + réouvre + retry read. Sinon (HLS segments, VOD, etc.), on
 * laisse l'EOF passer normalement.
 */
class LiveReconnectingHttpDataSource(
    private val wrapped: HttpDataSource,
) : HttpDataSource by wrapped {

    private var currentSpec: DataSpec? = null
    private var enableReconnect = false
    private var reconnectAttempts = 0

    override fun open(dataSpec: DataSpec): Long {
        currentSpec = dataSpec
        raccord = TsRaccord() // nouvelle ouverture demandée par le lecteur : il gère lui-même sa position
        val urlFull = dataSpec.uri.toString().lowercase()
        val urlPath = urlFull.substringBefore('?')

        // 2026-05-11 : Détection RESTRICTIVE Xtream IPTV continu.
        //
        // Avant : on activait reconnect dès qu'on voyait `/live/` + `.ts`. Mais
        // les segments HLS individuels (TF1 via netplus.ch, etc.) ont aussi ce
        // pattern. Résultat : après chaque segment HLS d'1 MB, le wrapper essayait
        // de re-fetch le même segment → ExoPlayer bloqué en BUFFERING.
        //
        // Nouvelle heuristique : un VRAI Xtream IPTV continu a :
        //   - `/live/USER/PASS/STREAM_ID.ts` (credentials en path)
        //   - PAS de marqueurs HLS (`seq=`, `chunk-`, `_seg_`, `.m3u8`, `tok_`)
        //   - Souvent sur port custom (8080, 25461, 80 IP-based)
        //
        // Un segment HLS Live a typiquement :
        //   - `seq=NNN` ou `chunk-NNN` dans le path/query
        //   - `tok_XXXX/` token signé en début de path
        //   - Hosts CDN classiques (cloudfront, akamaized, cdn-*, cache*.host.tld)
        //
        // Si MARQUEUR HLS détecté → désactive reconnect, ExoPlayer gère la
        // séquence des segments lui-même via HlsMediaSource.
        // 2026-06-08 (user "mini marche, grand player boucle sur Canal 4") :
        //   le pattern `name-12345.ts` (= segment HLS classique numéroté) était
        //   détecté à tort comme "Xtream live progressive" → reconnect en
        //   boucle qui rewind le même segment au début. La VRAIE distinction :
        //     - HLS segment chunked : `cwnxpjnjlive-2090.ts` (basename + - + N)
        //     - Xtream live progressive : `12345.ts` (juste l'ID chaîne)
        //   On regarde le filename : s'il contient un - ou _ + chiffres avant
        //   .ts, c'est un segment HLS → pas de reconnect.
        val filenameNoExt = urlPath.substringAfterLast('/').substringBefore('.')
        val isHlsNumberedSegment = filenameNoExt.matches(Regex(".+[-_]\\d+$"))

        val hasHlsMarker = urlFull.contains("seq=") ||
            urlPath.contains("/tok_") ||
            urlPath.contains("/chunk-") ||
            urlPath.contains("/chunk_") ||
            urlPath.contains("_seg_") ||
            urlPath.contains("/seg-") ||
            urlPath.contains("/segment-") ||
            urlPath.contains(".m4s") ||
            isHlsNumberedSegment ||  // ← name-N.ts ou name_N.ts
            // HLS hosts CDN connus
            urlFull.contains("akamaized.net") ||
            urlFull.contains("cloudfront.net") ||
            urlFull.contains("netplus.ch") ||
            urlFull.contains("diff.tf1.fr") ||
            urlFull.contains("ftven.fr") ||
            urlFull.contains("bct.nextradiotv.com") ||
            // 2026-10-21 : segments Pluto (siloh-ns1.plutotv.net/live/…/123456.ts) pris à tort
            //   pour du Xtream → le même segment rejoué en boucle (chaînes « Nouveau sur Pluto TV »).
            urlFull.contains("plutotv.net") ||
            urlFull.contains("pluto.tv")

        // 2026-10-27 (user : « Auto Moto charge 10 secondes et coupe — on doit bien avoir une
        //   mécanique pour réparer ce genre de flux ») : c'est CE wrapper, mais il ne
        //   reconnaissait que la forme longue `/live/USER/PASS/ID.ts`. Dric4rTV (World Live, et
        //   maintenant le dossier Sport) donne la forme COURTE `http://hôte:PORT/USER/PASS/ID`,
        //   sans /live/ ni extension — même flux TS continu, que le panel coupe toutes les
        //   ~10 s. Mesuré sur la TV : STATE_ENDED toutes les 10-13 s, puis rechargement complet
        //   du lecteur → coupure visible (et 403 si la reconnexion est trop rapide).
        val estXtreamCourt = Regex("""^https?://[^/]+:\d+/[^/]+/[^/]+/\d+/?$""").matches(urlPath)
        val aRelais = XtreamRelais.origineDe(dataSpec.uri.toString()) != null
        enableReconnect = aRelais || (!hasHlsMarker && (
            (urlPath.contains("/live/") && urlPath.endsWith(".ts")) || estXtreamCourt ||
                urlPath.contains("/play/mpegts/")))
        reconnectAttempts = 0
        if (enableReconnect) {
            Log.d(TAG, "Live progressive (Xtream) detected, auto-reconnect enabled for ${dataSpec.uri}")
        } else if (urlPath.contains("/live/") && urlPath.endsWith(".ts")) {
            // Pattern /live/.ts mais avec marqueurs HLS → log pour debug
            Log.v(TAG, "HLS segment (not Xtream) detected, reconnect DISABLED for ${dataSpec.uri}")
        }
        // v64 : si Stalker play.php renvoie 456 (MAC rate-limit), désactive
        //   reconnect — sinon boucle infinie de retry qui sature le CPU.
        try {
            val longueur = try {
                wrapped.open(dataSpec)
            } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                // 2026-10-27 (user : « j'ai eu une coupure ») — mesuré sur la TV : quand le lecteur
                //   REPART DE ZÉRO (passage mini → grand lecteur, interruption, nouvel essai), il
                //   rouvre l'adresse de son MediaItem, c.-à-d. le PREMIER jeton, périmé → 403 en
                //   boucle (6 essais), écran noir. Adresse à jeton connue du relais → on redemande
                //   un jeton neuf et on ouvre celui-là. Toute autre adresse : erreur rendue telle quelle.
                val orig = if (e.responseCode in setOf(401, 403, 404, 410))
                    XtreamRelais.origineDe(dataSpec.uri.toString()) else null
                val neuf = orig?.let { XtreamRelais.nouveauJeton(it, dataSpec.httpRequestHeaders["User-Agent"]) }
                    ?: throw e
                Log.d(TAG, "Jeton périmé (HTTP ${e.responseCode}) à l'ouverture → jeton neuf")
                try { wrapped.close() } catch (_: Exception) {}
                val specNeuve = dataSpec.buildUpon().setUri(android.net.Uri.parse(neuf)).build()
                currentSpec = specNeuve
                wrapped.open(specNeuve)
            }
            // 2026-10-27 (user : « attention, c'est du générique, faut pas casser autre chose ») :
            //   garde-fou sur les formes ajoutées ce jour (adresse courte, /play/mpegts/, relais).
            //   On ne garde la reconnexion que si le serveur annonce bien un FLUX (TS / binaire) :
            //   playlist HLS, DASH, page HTML ou JSON → comportement d'avant, EOF rendu tel quel.
            if (enableReconnect && !(urlPath.contains("/live/") && urlPath.endsWith(".ts"))) {
                val ct = runCatching {
                    wrapped.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value?.firstOrNull().orEmpty().lowercase()
                }.getOrDefault("")
                if (ct.contains("mpegurl") || ct.contains("dash") || ct.contains("html") ||
                    ct.contains("json") || ct.contains("xml") || ct.startsWith("text/")) {
                    Log.d(TAG, "Pas un flux continu (Content-Type=$ct) — reconnexion désactivée")
                    enableReconnect = false
                }
            }
            return longueur
        } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 456 || e.responseCode == 401 || e.responseCode == 402 || e.responseCode == 451) {
                Log.w(TAG, "Hard fail HTTP ${e.responseCode} on ${dataSpec.uri} — disabling reconnect to prevent CPU saturation")
                enableReconnect = false
            }
            throw e
        }
    }

    /** 2026-10-27 : raccord TS après reconnexion (jette les doublons du tampon serveur, cf. TsRaccord). */
    private var raccord = TsRaccord()

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        while (true) {
            if (raccord.aSortie()) return raccord.servir(buffer, offset, readLength)
            if (!(enableReconnect && raccord.enFiltrage())) break
            val r = try {
                raccord.filtrer { b, o, l -> wrapped.read(b, o, l) }
            } catch (e: Exception) {
                val annule = Thread.currentThread().isInterrupted ||
                    generateSequence(e as Throwable?) { it.cause }.any {
                        it is java.io.InterruptedIOException && it.message?.contains("interrupted", true) == true ||
                            it is InterruptedException
                    }
                if (annule) throw e
                C.RESULT_END_OF_INPUT
            }
            if (r == C.RESULT_END_OF_INPUT) return lireApresFin(buffer, offset, readLength)
            if (r > 0) reconnectAttempts = 0
        }
        val result = lireDirect(buffer, offset, readLength)
        if (result == C.RESULT_END_OF_INPUT) return lireApresFin(buffer, offset, readLength)
        return result
    }

    private fun lireDirect(buffer: ByteArray, offset: Int, readLength: Int): Int {
        val result = try {
            wrapped.read(buffer, offset, readLength)
        } catch (e: Exception) {
            // 2026-05-10 v2 : ÉLARGI à toutes les exceptions réseau (pas juste
            // HttpDataSourceException). Connection: close peut déclencher
            // SocketException, IOException, EOFException, etc. — toutes doivent
            // déclencher le reconnect sur live progressive.
            // 2026-10-27 : « interrupted » = c'est le LECTEUR qui annule ce chargement (changement
            //   de chaîne, passage au grand lecteur, seek). Se reconnecter là ouvrait une connexion
            //   fantôme sur le compte Xtream (mesuré à 14:07:58 : reconnexion réussie… puis 403 en
            //   boucle sur la reprise). On rend l'annulation telle quelle.
            val annule = Thread.currentThread().isInterrupted ||
                generateSequence(e as Throwable?) { it.cause }.any {
                    it is java.io.InterruptedIOException && it.message?.contains("interrupted", true) == true ||
                        it is InterruptedException
                }
            if (enableReconnect && annule) throw e
            if (enableReconnect) {
                Log.d(TAG, "Read exception (${e.javaClass.simpleName}) on live URL → treating as EOF for reconnect: ${e.message}")
                C.RESULT_END_OF_INPUT
            } else throw e
        }
        if (result > 0 && enableReconnect) {
            raccord.observer(buffer, offset, result)
            reconnectAttempts = 0
        }
        return result
    }

    /** Fin de flux : reconnexion (jeton neuf, raccord TS), puis lecture via [read]. */
    private fun lireApresFin(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (!enableReconnect || reconnectAttempts >= MAX_RECONNECTS) return C.RESULT_END_OF_INPUT
        run {
            val spec = currentSpec ?: return C.RESULT_END_OF_INPUT
            reconnectAttempts++
            try {
                Log.d(TAG, "EOF on live URL — reconnecting (attempt #$reconnectAttempts)")
                try { wrapped.close() } catch (_: Exception) {}
                // Nouvelle requête sur la même URL — le serveur ouvre une nouvelle session.
                // Pas de Range header — on veut le live edge frais.
                // 2026-10-27 : adresse à jeton Xtream (/play/mpegts/<jeton>) → le jeton ne
                //   resservira pas (403). On en redemande un neuf à l'adresse d'origine.
                val uriFraiche = XtreamRelais.origineDe(spec.uri.toString())?.let { orig ->
                    val ua = spec.httpRequestHeaders["User-Agent"]
                    XtreamRelais.nouveauJeton(orig, ua)?.also { Log.d(TAG, "Jeton Xtream neuf obtenu pour la reconnexion") }
                }?.let { android.net.Uri.parse(it) } ?: spec.uri
                val freshSpec = DataSpec.Builder()
                    .setUri(uriFraiche)
                    .setHttpRequestHeaders(spec.httpRequestHeaders)
                    .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                    .build()
                // 2026-10-27 : le panel peut refuser une reconnexion IMMÉDIATE (HTTP 403 : l'ancienne
                //   connexion du compte est encore comptée quelques instants). On réessaie 4 fois en
                //   espaçant (0,3 / 0,6 / 1,2 / 2,4 s) : le tampon du lecteur couvre l'attente, l'image
                //   ne s'arrête pas.
                var ouvert = false
                var derniere: Exception? = null
                for (essai in 0 until 5) {
                    try {
                        wrapped.open(freshSpec)
                        ouvert = true
                        break
                    } catch (e: Exception) {
                        derniere = e
                        try { wrapped.close() } catch (_: Exception) {}
                        if (essai == 4) break
                        val attente = 300L shl essai
                        Log.d(TAG, "Reconnexion refusée (${e.message?.take(60)}) — nouvel essai dans $attente ms")
                        Thread.sleep(attente)
                    }
                }
                if (!ouvert) throw derniere ?: java.io.IOException("reconnexion impossible")
                currentSpec = freshSpec // le jeton neuf sert de base à la coupure suivante
                Log.d(TAG, "Reconnect success — raccord TS en cours")
                raccord.commencer()
            } catch (e: Exception) {
                if (e is InterruptedException) throw java.io.InterruptedIOException("interrupted")
                Log.w(TAG, "Reconnect failed: ${e.message}")
                // Don't throw — let ExoPlayer handle EOF normally if reconnect fails.
                return C.RESULT_END_OF_INPUT
            }
        }
        // Lecture sur la connexion neuve (filtrée par le raccord si des PTS étaient connus).
        return read(buffer, offset, readLength)
    }

    companion object {
        private const val TAG = "LiveReconnectDS"
        private const val MAX_RECONNECTS = 100  // gros budget : 100 reconnects = ~100s de stream si chaque connexion = 1s
    }

    /** Factory wrapper. */
    class Factory(private val wrapped: HttpDataSource.Factory) : HttpDataSource.Factory {
        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            wrapped.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }

        override fun createDataSource(): HttpDataSource =
            LiveReconnectingHttpDataSource(wrapped.createDataSource())
    }
}
