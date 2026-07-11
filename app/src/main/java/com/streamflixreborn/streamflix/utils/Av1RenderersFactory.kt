package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.av1.Dav1dLibrary
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * 2026-07-05 (user "VLC décode l'AV1 1080p fluide, reproduis-le in-app") :
 *   RenderersFactory qui ajoute le décodeur logiciel AV1 **dav1d** (celui de VLC, compilé depuis
 *   media3 1.9 + libdav1d.a arm64) EN PLUS des décodeurs standards (NextRenderersFactory = MediaCodec
 *   HW + FFmpeg logiciel). dav1d est ajouté APRÈS le renderer MediaCodec : ExoPlayer garde donc le
 *   décodeur MATÉRIEL quand il gère l'AV1 (ex. OPPO, HW AV1 = FORMAT_HANDLED), et bascule sur dav1d
 *   quand le HW ne peut pas (ex. Chromecast sabrina : AV1 1080p = EXCEEDS_CAPABILITIES → dav1d prend
 *   le relais, FORMAT_HANDLED, décodage fluide). Le .so libdav1dJNI.so est packagé en jniLibs/arm64-v8a.
 */
@UnstableApi
class Av1RenderersFactory(context: Context) : NextRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        // 2026-07-05 : diagnostic — charge explicitement le .so pour LOGUER l'erreur exacte
        //   (media3 LibraryLoader avale l'UnsatisfiedLinkError silencieusement).
        try {
            System.loadLibrary("dav1dJNI")
            android.util.Log.i("Av1RenderersFactory", "System.loadLibrary(dav1dJNI) OK")
        } catch (t: Throwable) {
            android.util.Log.e("Av1RenderersFactory", "System.loadLibrary(dav1dJNI) ÉCHEC: ${t.message}")
        }
        try {
            if (Dav1dLibrary.isAvailable()) {
                // Ajouté À LA FIN → priorité au HW quand il gère l'AV1, dav1d en repli logiciel.
                out.add(
                    Libdav1dVideoRenderer(
                        allowedVideoJoiningTimeMs,
                        eventHandler,
                        eventListener,
                        /* maxDroppedFramesToNotify= */ 50,
                    )
                )
                android.util.Log.i("Av1RenderersFactory", "dav1d (Libdav1dVideoRenderer) ajouté au pipeline")
            } else {
                android.util.Log.w("Av1RenderersFactory", "dav1d indisponible (libdav1dJNI.so manquant ?)")
            }
        } catch (t: Throwable) {
            android.util.Log.w("Av1RenderersFactory", "dav1d non ajouté: ${t.message}")
        }
    }
}
