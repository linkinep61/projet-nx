package com.streamflixreborn.streamflix.utils.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor

/**
 * 2026-11-12 — Lecteur YU (yourupload) de VoirAnime : le fichier est un AVI (XviD + MP3) servi sous
 * le nom « video.mp4 ». Dans l'ordre par défaut de Media3, le détecteur MP3 passe AVANT l'AVI ; il
 * trouve une trame MP3 au début du bloc `movi` et accepte le fichier : seule la piste son est lue,
 * puis la durée tombe à 0,5 s (journal du banc : « container=audio/mpeg », période 1572 s → 0,50 s).
 *
 * On place donc l'extracteur AVI en tête. Il ne reconnaît que les fichiers commençant par
 * « RIFF….AVI » : pour tous les autres formats, il refuse aussitôt et l'ordre habituel reprend.
 */
@OptIn(UnstableApi::class)
class ExtracteursAviDabord(
    private val base: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = aviDabord(base.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        aviDabord(base.createExtractors(uri, responseHeaders))

    private fun aviDabord(liste: Array<Extractor>): Array<Extractor> =
        liste.sortedBy { if (it is AviExtractor) 0 else 1 }.toTypedArray()
}
