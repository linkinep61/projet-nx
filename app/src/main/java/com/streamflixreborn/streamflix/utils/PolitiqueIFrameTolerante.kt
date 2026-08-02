package com.streamflixreborn.streamflix.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * 2026-08-08 (user, sur « J1F · upbolt.to (VF) » affiché en rouge : « donc il fonctionne,
 *   pourquoi elle est tombée en échec chez nous ? ») — PISTE I-FRAME DÉCLARÉE MAIS ABSENTE.
 *
 * VÉRIFIÉ EN DIRECT : la page `upbolt.to/e/ovdb7pxvsoym` lit le film sans problème
 *   (1 h 49 min, image nette). Le serveur n'est donc pas mort. Ce qui échouait chez nous :
 *
 *     DIAG-403 code=403 uri=https://edge02.upbolt.to/hls2/…/iframes-v1-a1.m3u8
 *     Unhandled VOD player error (ERROR_CODE_IO_BAD_HTTP_STATUS) — auto-switching
 *
 *   `iframes-v1-a1.m3u8` n'est PAS la vidéo : c'est la piste I-frame, celle qui alimente les
 *   vignettes de la barre de progression. Le master la déclare (`EXT-X-I-FRAME-STREAM-INF`)
 *   mais le serveur ne la sert pas. Leur lecteur ne la demande jamais ; ExoPlayer si, prend un
 *   403, le retente six fois avec backoff (~15 s perdues) puis considère la SOURCE ENTIÈRE
 *   comme morte — alors que les variantes vidéo, elles, répondent parfaitement.
 *
 * Cette politique :
 *   • ne retente PAS un échec portant sur une piste I-frame (plus de 15 s gâchées) ;
 *   • l'exclut (`FALLBACK_TYPE_TRACK`) au lieu de faire tomber le serveur ;
 *   • laisse le comportement d'origine intact pour tout le reste.
 *
 * Vaut pour TOUS les hébergeurs qui déclarent cette piste sans la servir, pas seulement
 *   upbolt — vu le nombre de serveurs marqués rouges à tort, il y en a probablement d'autres.
 */
/**
 * 2026-08-08 (user : « il doit pas passer rouge à tort, ça c'est pas possible… ils seront
 *   rouges que si c'est vraiment cassé ») — QU'EST-CE QUI CASSE VRAIMENT UN SERVEUR ?
 *
 * Un serveur ne doit être déclaré mort que si c'est SA vidéo qui ne se lit pas. Une erreur
 * sur un fichier ANNEXE — piste I-frame des vignettes, sous-titre, miniatures — ne dit rien
 * sur la santé du flux : upbolt en est la preuve, il lisait parfaitement le film sur son
 * propre site pendant qu'on l'affichait en rouge.
 *
 * Le risque a d'ailleurs grandi le même jour : on attache désormais jusqu'à 27 sous-titres
 * externes par serveur NetMirror. Sans ce garde-fou, un seul `.srt` en échec suffirait à
 * faire passer au rouge un serveur qui joue très bien.
 */
object FichiersAnnexes {
    /** `true` si l'URL désigne un fichier accessoire, pas le flux vidéo. */
    fun estAnnexe(uri: Uri?): Boolean {
        val s = uri?.toString()?.lowercase() ?: return false
        return s.contains("iframes-") || s.contains("/iframes/") || s.contains("i-frame") ||
            s.endsWith(".srt") || s.endsWith(".vtt") || s.endsWith(".ass") || s.endsWith(".ssa") ||
            s.contains("/subtitle") || s.contains("subscdn") ||
            s.contains("thumbnail") || s.contains("/sprite")
    }

    /** Remonte la chaîne des causes pour retrouver l'URL réellement en échec. */
    fun uriEnEchec(erreur: Throwable?): Uri? {
        var e: Throwable? = erreur
        var profondeur = 0
        while (e != null && profondeur < 8) {
            (e as? androidx.media3.datasource.HttpDataSource.HttpDataSourceException)
                ?.let { return it.dataSpec.uri }
            e = e.cause
            profondeur++
        }
        return null
    }
}

@UnstableApi
class PolitiqueIFrameTolerante : DefaultLoadErrorHandlingPolicy() {

    private fun estPisteIFrame(uri: Uri?): Boolean {
        val s = uri?.toString()?.lowercase() ?: return false
        return s.contains("iframes-") || s.contains("/iframes/") || s.contains("i-frame")
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (estPisteIFrame(loadErrorInfo.loadEventInfo.uri)) {
            Log.d(TAG, "piste I-frame en échec → ignorée, aucun retry : ${loadErrorInfo.loadEventInfo.uri}")
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        if (estPisteIFrame(loadErrorInfo.loadEventInfo.uri) &&
            fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)
        ) {
            Log.d(TAG, "piste I-frame exclue 10 min au lieu de faire échouer le serveur")
            return LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                600_000L,
            )
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    private companion object {
        const val TAG = "PolitiqueIFrame"
    }
}
