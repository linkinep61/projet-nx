package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 2026-10-27 (user : « plus fiable, moins de poids, et plus rapide que les 2 minutes ») —
 * ÉCOUTE EN AVANCE pour le recalage des sous-titres.
 *
 * Avant : on n'écoutait que le son de la lecture, au rythme du film → 1er calage après ~60
 * répliques entendues (1 à 2 min, plus si générique).
 * Ici : un 2e lecteur, invisible et muet, lit SEULEMENT le son des prochaines minutes du film,
 * plus vite que le temps réel (sa sortie son « avale » tout sans jouer : le lecteur avance aussi
 * vite que le réseau et le décodage le permettent). Chaque trame de 16 ms passe dans TEN VAD.
 * Mémoire : rien n'est gardé du son, seulement « voix / pas voix » par tranche de 200 ms.
 * Le lecteur est libéré dès la fin de l'écoute.
 */
object EcouteAvance {
    private const val TAG = "EcouteAvance"
    private const val PAS = EcouteVoix.PAS
    /** Minutes de film écoutées d'avance, et temps maximum accordé à l'écoute. */
    const val DUREE_MS = 6 * 60_000L
    private const val DELAI_MAX_MS = 150_000L

    class Resultat(val voix: IntArray, val silences: IntArray, val couvertS: Long, val dureeReelleMs: Long)

    /** Découpe le son reçu (n'importe quel format PCM) en trames 16 kHz mono pour TEN VAD. */
    private class Analyseur(private val vad: TenVad) {
        var sr = 0; var ch = 0; var enc = C.ENCODING_INVALID
        private var ratio = 1.0; private var phase = 0.0; private var acc = 0.0; private var accN = 0
        private val trame = ShortArray(TenVad.TRAME); private var n = 0; private var debutMs = 0.0
        val parle = HashMap<Int, Int>(); val total = HashMap<Int, Int>()
        @Volatile var finMs = 0L

        fun format(s: Int, c: Int, e: Int) { sr = s; ch = c; enc = e; ratio = if (s > 0) s / 16000.0 else 1.0 }

        fun analyser(b0: ByteBuffer, mediaUs: Long) {
            if (sr <= 0 || ch <= 0) return
            val flottant = enc == C.ENCODING_PCM_FLOAT
            if (!flottant && enc != C.ENCODING_PCM_16BIT) return
            val b = b0.duplicate().order(ByteOrder.nativeOrder())
            val taille = if (flottant) 4 else 2
            var tMs = mediaUs / 1000.0
            val pasMs = 1000.0 / sr
            if (n > 0 && kotlin.math.abs(tMs - (debutMs + n * 1000.0 / 16000)) > 200) { n = 0; phase = 0.0; acc = 0.0; accN = 0 }
            while (b.remaining() >= taille * ch) {
                var somme = 0.0
                for (c in 0 until ch) somme += if (flottant) b.float * 32767.0 else b.short.toDouble()
                if (n == 0 && accN == 0) debutMs = tMs
                acc += somme / ch; accN++
                tMs += pasMs; phase += 1.0
                if (phase >= ratio) {
                    phase -= ratio
                    trame[n++] = (acc / accN).toInt().coerceIn(-32768, 32767).toShort()
                    acc = 0.0; accN = 0
                    if (n == TenVad.TRAME) {
                        n = 0
                        val bin = (debutMs.toLong() / PAS).toInt()
                        val voix = try { vad.voix(trame) } catch (_: Throwable) { false }
                        synchronized(this) {
                            total[bin] = (total[bin] ?: 0) + 1
                            if (voix) parle[bin] = (parle[bin] ?: 0) + 1
                        }
                    }
                }
            }
            finMs = tMs.toLong()
        }

        fun resultat(dureeReelleMs: Long): Resultat = synchronized(this) {
            val pleins = total.keys.filter { (total[it] ?: 0) >= 5 }
            val uns = pleins.filter { (parle[it] ?: 0) * 2 >= (total[it] ?: 0) }
            val sil = pleins.filter { (parle[it] ?: 0) * 4 < (total[it] ?: 0) }
            Resultat(uns.sorted().toIntArray(), sil.sorted().toIntArray(), total.size * PAS / 1000, dureeReelleMs)
        }
    }

    /** Sortie son qui ne joue rien : elle analyse et « avale » chaque tampon tout de suite. */
    private class SortieAvaleuse(base: AudioSink, private val a: Analyseur) : ForwardingAudioSink(base) {
        @Volatile private var finUs = Long.MIN_VALUE
        @Volatile private var decalageUs = 0L
        private var fini = false
        override fun configure(f: androidx.media3.common.Format, taille: Int, sorties: IntArray?) {
            a.format(f.sampleRate, f.channelCount, f.pcmEncoding)
            super.configure(f, taille, sorties)
        }
        override fun setOutputStreamOffsetUs(o: Long) { decalageUs = o; super.setOutputStreamOffsetUs(o) }
        override fun handleBuffer(buffer: ByteBuffer, ptsUs: Long, n: Int): Boolean {
            a.analyser(buffer, ptsUs - decalageUs)
            val octets = if (a.enc == C.ENCODING_PCM_FLOAT) 4 else 2
            val frames = if (a.ch > 0) buffer.remaining() / (octets * a.ch) else 0
            if (a.sr > 0) finUs = ptsUs + frames * 1_000_000L / a.sr
            buffer.position(buffer.limit())
            return true
        }
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
            if (finUs == Long.MIN_VALUE) AudioSink.CURRENT_POSITION_NOT_SET else finUs
        override fun hasPendingData(): Boolean = false
        override fun playToEndOfStream() { fini = true }
        override fun isEnded(): Boolean = fini
        override fun flush() { finUs = Long.MIN_VALUE; fini = false; super.flush() }
    }

    private var lecteur: ExoPlayer? = null
    private var vad: TenVad? = null
    private val principal = Handler(Looper.getMainLooper())

    val disponible: Boolean get() = TenVad.creer()?.also { it.fermer() } != null

    /**
     * Écoute [video] de [debutMs] à [debutMs] + [DUREE_MS], puis appelle [surFini] (fil principal)
     * avec les tranches de voix, ou null si l'écoute a échoué. À appeler sur le fil principal.
     */
    fun lancer(context: Context, video: com.streamflixreborn.streamflix.models.Video, debutMs: Long, surFini: (Resultat?) -> Unit) {
        arreter()
        val v = TenVad.creer() ?: run { surFini(null); return }
        vad = v
        val a = Analyseur(v)
        val depart = System.currentTimeMillis()
        val rendus = object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(c: Context, flottant: Boolean, params: Boolean): AudioSink =
                SortieAvaleuse(DefaultAudioSink.Builder(c).build(), a)
        }
        val entetes = video.headers.orEmpty()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(entetes.filterKeys { !it.equals("User-Agent", true) })
            .apply { entetes.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { setUserAgent(it.value) } }
        // 2026-10-27 (photo mémoire sur la télé) : avec le tampon par défaut (50 s), ce lecteur
        //   gardait ~11 Mo de vidéo d'avance alors que le son est consommé aussitôt → petit tampon.
        val tampon = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 1_000, 1_000)
            .setTargetBufferBytes(4 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(context.applicationContext, rendus)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(tampon)
            .build()
        lecteur = p
        // Son seulement, et la plus petite qualité possible (moins de données à télécharger).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setForceLowestBitrate(true)
            .build()
        p.volume = 0f
        val type = video.type.orEmpty().lowercase()
        val mime = when {
            "mpegurl" in type || ".m3u8" in video.source.lowercase() -> MimeTypes.APPLICATION_M3U8
            "dash" in type || ".mpd" in video.source.lowercase() -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        p.setMediaItem(MediaItem.Builder().setUri(video.source).apply { mime?.let { setMimeType(it) } }.build(), debutMs)
        var termine = false
        fun finir(ok: Boolean, raison: String) {
            if (termine) return
            termine = true
            val duree = System.currentTimeMillis() - depart
            val r = if (ok) a.resultat(duree) else null
            Log.i(TAG, "écoute en avance terminée ($raison) en ${duree / 1000} s : " +
                (r?.let { "${it.couvertS} s de film, ${it.voix.size} tranches de voix, ${it.silences.size} de silence" } ?: "échec"))
            arreter()
            surFini(r)
        }
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { finir(a.total.size > 0, "erreur ${error.errorCodeName}") }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) finir(true, "fin du film") }
        })
        p.prepare()
        p.play()
        Log.i(TAG, "écoute en avance lancée à ${debutMs / 1000} s (${DUREE_MS / 60_000} min, détecteur TEN VAD)")
        val surveille = object : Runnable {
            override fun run() {
                if (termine) return
                val ecoute = a.finMs - debutMs
                when {
                    ecoute >= DUREE_MS -> finir(true, "${DUREE_MS / 60_000} min écoutées")
                    System.currentTimeMillis() - depart > DELAI_MAX_MS -> finir(a.total.size > 0, "délai dépassé, ${ecoute / 1000} s écoutées")
                    else -> principal.postDelayed(this, 500)
                }
            }
        }
        principal.postDelayed(surveille, 500)
    }

    fun arreter() {
        principal.removeCallbacksAndMessages(null)
        lecteur?.let { runCatching { it.stop(); it.release() } }
        lecteur = null
        vad?.fermer()
        vad = null
    }
}
