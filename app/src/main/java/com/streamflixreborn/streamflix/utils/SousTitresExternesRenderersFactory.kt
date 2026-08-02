package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * 2026-08-08 — CAUSE RÉELLE du serveur « NetMirror [VOSTFR] » qui n'a JAMAIS lu une image.
 *
 * Trace exacte relevée dans le journal du user :
 *   java.lang.IllegalStateException: Legacy decoding is disabled, can't handle
 *   application/x-subrip samples (expected application/x-media3-cues)
 *       at TextRenderer.assertLegacyDecodingEnabledIfRequired(TextRenderer.java:616)
 * → remonté au player en ERROR_CODE_FAILED_RUNTIME_CHECK dès la 1re image, d'où le rouge.
 *
 * Depuis Media3 1.4, les sous-titres sont analysés PENDANT l'extraction et le TextRenderer
 * n'accepte plus que des `application/x-media3-cues`. Or un `SingleSampleMediaSource` — ce
 * qu'on fabrique pour greffer les .srt NetMirror sur un flux HLS — livre l'échantillon BRUT
 * (`application/x-subrip`). Le renderer refuse, sans rapport avec le réseau ni l'extraction :
 * les 27 pistes étaient bien récupérées.
 *
 * `experimentalSetLegacyDecodingEnabled(true)` est l'interrupteur officiel prévu par Media3
 * pour ce cas. ⚠ Il vit sur `TextRenderer`, PAS sur `DefaultRenderersFactory` (vérifié au
 * javap sur media3-exoplayer 1.8.0) — d'où cette fabrique qui le pose sur le renderer.
 * Sans effet sur les flux dont les sous-titres sont déjà dans le conteneur.
 */
@UnstableApi
class SousTitresExternesRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val avant = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        activerDecodageHeriteSurTextRenderers(out, avant)
    }
}

/** Partagé avec [Av1RenderersFactory] : même correctif, deux hiérarchies de fabriques. */
@UnstableApi
internal fun activerDecodageHeriteSurTextRenderers(out: ArrayList<Renderer>, depuis: Int) {
    for (i in depuis until out.size) {
        (out[i] as? TextRenderer)?.experimentalSetLegacyDecodingEnabled(true)
    }
}
