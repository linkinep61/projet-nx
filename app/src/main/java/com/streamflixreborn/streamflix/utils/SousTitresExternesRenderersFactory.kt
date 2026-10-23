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

    // 2026-10-22 : AudioSink de Media3 + recopie du son pour EcouteVoix (recalage sur les voix).
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): androidx.media3.exoplayer.audio.AudioSink? =
        EcouteVoix.audioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)

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

/**
 * 2026-10-23 (user : Pluto · VF « tombé rouge directement », Le Carnaval des âmes) : le DASH
 *   de Pluto est assemblé en PLUSIEURS périodes (film + pubs insérées). `MergingMediaSource`
 *   exige le même nombre de périodes partout → IllegalMergeException dès qu'on lui greffe un
 *   .srt OpenSubtitles (une seule période). Sur ces flux, pas de fusion de sous-titres externes.
 */
fun fluxMultiPeriodes(mediaItem: androidx.media3.common.MediaItem): Boolean =
    mediaItem.localConfiguration?.uri?.host?.contains("pluto.tv", ignoreCase = true) == true

/** Partagé avec [Av1RenderersFactory] : même correctif, deux hiérarchies de fabriques. */
@UnstableApi
fun activerDecodageHeriteSurTextRenderers(out: ArrayList<Renderer>, depuis: Int) {
    for (i in depuis until out.size) {
        (out[i] as? TextRenderer)?.experimentalSetLegacyDecodingEnabled(true)
    }
}

/**
 * 2026-10-22 (user : « si je vais dans sous-titres, j'ai que télécharger… ce n'est pas les
 * sous-titres intégrés », Vidara VOSTFR sur téléphone) : `HlsMediaSource` / `DashMediaSource`
 * construits à la main IGNORENT `MediaItem.subtitleConfigurations`. Le TV le savait déjà
 * (fusion faite dans PlayerTvFragment) ; le lecteur mobile, non → les .vtt Vidara (et toute
 * piste externe greffée sur un HLS : VOE, NetMirror…) n'arrivaient jamais dans le menu.
 * On fusionne donc la source avec une source par sous-titre. Le renderer doit accepter le
 * décodage hérité (cf. [activerDecodageHeriteSurTextRenderers]).
 */
@UnstableApi
fun avecSousTitresExternes(
    source: androidx.media3.exoplayer.source.MediaSource,
    mediaItem: androidx.media3.common.MediaItem,
    fabrique: androidx.media3.datasource.DataSource.Factory,
): androidx.media3.exoplayer.source.MediaSource {
    val confs = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
    if (confs.isEmpty()) return source
    if (fluxMultiPeriodes(mediaItem)) {
        android.util.Log.d("PlayerDebug", "sous-titres externes NON fusionnés : flux multi-périodes (Pluto)")
        return source
    }
    val f = androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(fabrique)
    val pistes = confs.map { f.createMediaSource(it, androidx.media3.common.C.TIME_UNSET) }
    android.util.Log.d("PlayerDebug", "sous-titres externes fusionnés : ${pistes.size}")
    return androidx.media3.exoplayer.source.MergingMediaSource(source, *pistes.toTypedArray())
}
