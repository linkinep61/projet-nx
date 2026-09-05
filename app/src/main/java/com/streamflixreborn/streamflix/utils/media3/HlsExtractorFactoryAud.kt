package com.streamflixreborn.streamflix.utils.media3

import android.net.Uri
import android.text.TextUtils
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.EOFException
import java.io.IOException

/**
 * 2026-09-05 (user « l'image joue en hachuré » sur Vidara, mini ET grand player).
 *
 * POURQUOI : le mini et le grand (TV) construisent leurs HlsMediaSource avec
 * `DefaultHlsExtractorFactory(FLAG_ALLOW_NON_IDR_KEYFRAMES or FLAG_DETECT_ACCESS_UNITS)`.
 * Ces flags sont necessaires pour l'IPTV Xtream (segments sans IDR, sans AUD).
 * Mais le H264Reader de Media3, avec FLAG_DETECT_ACCESS_UNITS, DOUBLE les
 * echantillons d'un flux qui contient deja des NAL AUD (= tout MPEG-TS produit par
 * ffmpeg : Vidara, Filemoon, upbolt...) : un echantillon « AUD seul » + l'image.
 * Preuve logcat Oppo : decodeur c2.mtk.avc.decoder inputFps=48 pour un flux a
 * 23,976 i/s, renderFps=18, discardFps=6 → 25 % d'images jetees = saccades. Le flux
 * lui-meme est propre (720 images / 30,024 s, 1 slice/image, PTS reguliers).
 *
 * QUOI : meme logique que DefaultHlsExtractorFactory (memes flags, meme sniff,
 * meme fallback TS), mais le lecteur H264 des segments TS est [H264ReaderAud] :
 * detection par slice UNIQUEMENT tant qu'aucun AUD n'a ete vu. Tout le reste
 * (fMP4, WebVTT, ADTS, AC3, MP3) est delegue a la fabrique Media3 d'origine.
 */
@UnstableApi
class HlsExtractorFactoryAud(
    private val payloadReaderFactoryFlags: Int,
    private val exposeCea608WhenMissingDeclarations: Boolean,
) : HlsExtractorFactory {

    private val delegue = DefaultHlsExtractorFactory(payloadReaderFactoryFlags, exposeCea608WhenMissingDeclarations)
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var parseSubtitlesDuringExtraction = false

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory {
        this.subtitleParserFactory = subtitleParserFactory
        delegue.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): HlsExtractorFactory {
        this.parseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction
        delegue.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): HlsExtractorFactory {
        delegue.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format = delegue.getOutputTextFormat(sourceFormat)

    @Throws(IOException::class)
    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor {
        // 1) Un segment TS ? On le sniffe nous-memes avec notre lecteur H264.
        val ts = creerTsExtractor(format, muxedCaptionFormats, timestampAdjuster)
        sniffingExtractorInput.resetPeekPosition()
        val estTs = sniffSansBruit(ts, sniffingExtractorInput)
        if (estTs) {
            // Constructeur public a 3 args : pour un TsExtractor (reutilisable, jamais
            // recree) la fabrique de sous-titres ne sert qu'a recreate() → sans effet ici.
            return BundledHlsMediaChunkExtractor(ts, format, timestampAdjuster)
        }
        // 2) Sinon (fMP4, WebVTT, audio brut...) : la fabrique Media3 d'origine.
        return delegue.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId)
    }

    private fun creerTsExtractor(
        format: Format,
        muxedCaptionFormatsIn: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
    ): TsExtractor {
        var flags = DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM or payloadReaderFactoryFlags
        val muxedCaptionFormats: List<Format> = when {
            muxedCaptionFormatsIn != null -> {
                flags = flags or DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
                muxedCaptionFormatsIn
            }
            exposeCea608WhenMissingDeclarations ->
                listOf(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build())
            else -> emptyList()
        }
        val codecs = format.codecs
        if (!TextUtils.isEmpty(codecs)) {
            if (!MimeTypes.containsCodecsCorrespondingToMimeType(codecs, MimeTypes.AUDIO_AAC)) {
                flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM
            }
            if (!MimeTypes.containsCodecsCorrespondingToMimeType(codecs, MimeTypes.VIDEO_H264)) {
                flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM
            }
        }
        var extractorFlags = 0
        var spf = subtitleParserFactory
        if (!parseSubtitlesDuringExtraction) {
            spf = SubtitleParser.Factory.UNSUPPORTED
            extractorFlags = extractorFlags or TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
        }
        return TsExtractor(
            TsExtractor.MODE_HLS,
            extractorFlags,
            spf,
            timestampAdjuster,
            FabriquePayloadAud(flags, muxedCaptionFormats),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
    }

    private fun sniffSansBruit(extractor: Extractor, input: ExtractorInput): Boolean {
        var ok = false
        try {
            ok = extractor.sniff(input)
        } catch (_: EOFException) {
        } finally {
            input.resetPeekPosition()
        }
        return ok
    }

    /** Fabrique de lecteurs TS : identique a DefaultTsPayloadReaderFactory sauf pour H.264. */
    private class FabriquePayloadAud(
        private val flags: Int,
        private val closedCaptionFormats: List<Format>,
    ) : TsPayloadReader.Factory {
        private val delegue = DefaultTsPayloadReaderFactory(flags, closedCaptionFormats)

        override fun createInitialPayloadReaders(): android.util.SparseArray<TsPayloadReader> =
            delegue.createInitialPayloadReaders()

        override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
            if (streamType != TsExtractor.TS_STREAM_TYPE_H264) return delegue.createPayloadReader(streamType, esInfo)
            if (flags and DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM != 0) return null
            // Sous-titres incrustes (CEA-608/708) : on reprend la liste calculee par la
            // fabrique d'origine quand elle sait la lire, sinon nos formats declares.
            val formatsCc = closedCaptionFormats
            return PesReader(
                H264ReaderAud(
                    SeiReader(formatsCc, MimeTypes.VIDEO_MP2T),
                    flags and DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES != 0,
                    flags and DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS != 0,
                    MimeTypes.VIDEO_MP2T,
                )
            )
        }
    }
}
