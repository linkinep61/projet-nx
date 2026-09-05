/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamflixreborn.streamflix.utils.media3;

import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.NalUnitTargetBuffer;
import androidx.media3.extractor.ts.SeiReader;
import androidx.media3.extractor.ts.TsPayloadReader;

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.container.NalUnitUtil.SpsData;
import androidx.media3.container.ParsableNalUnitBitArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Copie de androidx.media3.extractor.ts.H264Reader (media3 1.8.0) avec UN correctif :
 *
 * 2026-09-05 (user « l'image joue en hachuré » sur Vidara, mini ET grand) : avec
 * FLAG_DETECT_ACCESS_UNITS, un flux qui contient DEJA des delimiteurs d'unite
 * d'acces (NAL AUD, type 9 — ffmpeg/x264 en ajoutent toujours en MPEG-TS) produisait
 * DEUX echantillons par image : un echantillon « AUD seul » (6 octets) + l'image
 * reelle, avec le meme horodatage. Preuve logcat Oppo : inputFps=48 pour un flux
 * a 23,976 i/s, renderFps=18, discardFps=6 = 25 % d'images jetees = saccades.
 * Ici : des qu'un AUD est vu dans le flux, la detection par en-tete de slice est
 * desactivee (le flux se decoupe sur ses AUD, comme prevu par la norme). Les flux
 * IPTV SANS AUD gardent la detection par slice (raison d'etre du flag).
 */
@UnstableApi
public final class H264ReaderAud implements ElementaryStreamReader {

  private final SeiReader seiReader;
  private final boolean allowNonIdrKeyframes;
  private final boolean detectAccessUnits;
  private final String containerMimeType;
  private final NalUnitTargetBuffer sps;
  private final NalUnitTargetBuffer pps;
  private final NalUnitTargetBuffer sei;
  private long totalBytesWritten;
  private final boolean[] prefixFlags;

  private String formatId;
  private TrackOutput output;
  private SampleReader sampleReader;

  // State that should not be reset on seek.
  private boolean hasOutputFormat;

  // Per PES packet state that gets reset at the start of each PES packet.
  private long pesTimeUs;

  // State inherited from the TS packet header.
  private boolean randomAccessIndicator;

  // Scratch variables to avoid allocations.
  private final ParsableByteArray seiWrapper;

  /**
   * @param seiReader An SEI reader for consuming closed caption channels.
   * @param allowNonIdrKeyframes Whether to treat samples consisting of non-IDR I slices as
   *     synchronization samples (key-frames).
   * @param detectAccessUnits Whether to split the input stream into access units (samples) based on
   *     slice headers. Pass {@code false} if the stream contains access unit delimiters (AUDs).
   * @param containerMimeType The MIME type of the container holding the stream.
   */
  public H264ReaderAud(
      SeiReader seiReader,
      boolean allowNonIdrKeyframes,
      boolean detectAccessUnits,
      String containerMimeType) {
    this.seiReader = seiReader;
    this.allowNonIdrKeyframes = allowNonIdrKeyframes;
    this.detectAccessUnits = detectAccessUnits;
    this.containerMimeType = containerMimeType;
    prefixFlags = new boolean[3];
    sps = new NalUnitTargetBuffer(NalUnitUtil.H264_NAL_UNIT_TYPE_SPS, 128);
    pps = new NalUnitTargetBuffer(NalUnitUtil.H264_NAL_UNIT_TYPE_PPS, 128);
    sei = new NalUnitTargetBuffer(NalUnitUtil.H264_NAL_UNIT_TYPE_SEI, 128);
    pesTimeUs = C.TIME_UNSET;
    seiWrapper = new ParsableByteArray();
  }

  @Override
  public void seek() {
    totalBytesWritten = 0;
    randomAccessIndicator = false;
    pesTimeUs = C.TIME_UNSET;
    NalUnitUtil.clearPrefixFlags(prefixFlags);
    sps.reset();
    pps.reset();
    sei.reset();
    seiReader.clear();
    if (sampleReader != null) {
      sampleReader.reset();
    }
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
    sampleReader = new SampleReader(output, allowNonIdrKeyframes, detectAccessUnits);
    seiReader.createTracks(extractorOutput, idGenerator);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    this.pesTimeUs = pesTimeUs;
    randomAccessIndicator |= (flags & FLAG_RANDOM_ACCESS_INDICATOR) != 0;
  }

  @Override
  public void consume(ParsableByteArray data) {
    assertTracksCreated();

    int offset = data.getPosition();
    int limit = data.limit();
    byte[] dataArray = data.getData();

    // Append the data to the buffer.
    totalBytesWritten += data.bytesLeft();
    output.sampleData(data, data.bytesLeft());

    // Scan the appended data, processing NAL units as they are encountered
    while (true) {
      int nalUnitOffset = NalUnitUtil.findNalUnit(dataArray, offset, limit, prefixFlags);

      if (nalUnitOffset == limit) {
        // We've scanned to the end of the data without finding the start of another NAL unit.
        nalUnitData(dataArray, offset, limit);
        return;
      }

      // We've seen the start of a NAL unit of the following type.
      int nalUnitType = NalUnitUtil.getNalUnitType(dataArray, nalUnitOffset);

      // Case of a 4 byte start code prefix 0x00000001, recoil NAL unit offset by one byte
      // to avoid previous byte being assigned to the previous access unit.
      int prefixSize = 3;
      if (nalUnitOffset > 0 && dataArray[nalUnitOffset - 1] == 0x00) {
        nalUnitOffset--;
        prefixSize = 4;
      }

      // This is the number of bytes from the current offset to the start of the next NAL unit.
      // It may be negative if the NAL unit started in the previously consumed data.
      int lengthToNalUnit = nalUnitOffset - offset;
      if (lengthToNalUnit > 0) {
        nalUnitData(dataArray, offset, nalUnitOffset);
      }
      int bytesWrittenPastPosition = limit - nalUnitOffset;
      long absolutePosition = totalBytesWritten - bytesWrittenPastPosition;
      // Indicate the end of the previous NAL unit. If the length to the start of the next unit
      // is negative then we wrote too many bytes to the NAL buffers. Discard the excess bytes
      // when notifying that the unit has ended.
      endNalUnit(
          absolutePosition,
          bytesWrittenPastPosition,
          lengthToNalUnit < 0 ? -lengthToNalUnit : 0,
          pesTimeUs);
      // Indicate the start of the next NAL unit.
      startNalUnit(absolutePosition, nalUnitType, pesTimeUs);
      // Continue scanning the data.
      offset = nalUnitOffset + prefixSize;
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    assertTracksCreated();
    if (isEndOfInput) {
      seiReader.flush();
      // Simulate end of current NAL unit and start an AUD one to trigger output of current sample
      endNalUnit(totalBytesWritten, 0, 0, pesTimeUs);
      startNalUnit(totalBytesWritten, NalUnitUtil.H264_NAL_UNIT_TYPE_AUD, pesTimeUs);
      endNalUnit(totalBytesWritten, 0, 0, pesTimeUs);
    }
  }
  private void startNalUnit(long position, int nalUnitType, long pesTimeUs) {
    if (nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_AUD) {
      sampleReader.marquerAudVu();
    }
    if (!hasOutputFormat || sampleReader.needsSpsPps()) {
      sps.startNalUnit(nalUnitType);
      pps.startNalUnit(nalUnitType);
    }
    sei.startNalUnit(nalUnitType);
    sampleReader.startNalUnit(position, nalUnitType, pesTimeUs, randomAccessIndicator);
  }
  private void nalUnitData(byte[] dataArray, int offset, int limit) {
    if (!hasOutputFormat || sampleReader.needsSpsPps()) {
      sps.appendToNalUnit(dataArray, offset, limit);
      pps.appendToNalUnit(dataArray, offset, limit);
    }
    sei.appendToNalUnit(dataArray, offset, limit);
    sampleReader.appendToNalUnit(dataArray, offset, limit);
  }
  private void endNalUnit(long position, int offset, int discardPadding, long pesTimeUs) {
    if (!hasOutputFormat || sampleReader.needsSpsPps()) {
      sps.endNalUnit(discardPadding);
      pps.endNalUnit(discardPadding);
      if (!hasOutputFormat) {
        if (sps.isCompleted() && pps.isCompleted()) {
          List<byte[]> initializationData = new ArrayList<>();
          initializationData.add(Arrays.copyOf(sps.nalData, sps.nalLength));
          initializationData.add(Arrays.copyOf(pps.nalData, pps.nalLength));
          NalUnitUtil.SpsData spsData = NalUnitUtil.parseSpsNalUnit(sps.nalData, 3, sps.nalLength);
          NalUnitUtil.PpsData ppsData = NalUnitUtil.parsePpsNalUnit(pps.nalData, 3, pps.nalLength);
          String codecs =
              CodecSpecificDataUtil.buildAvcCodecString(
                  spsData.profileIdc,
                  spsData.constraintsFlagsAndReservedZero2Bits,
                  spsData.levelIdc);
          output.format(
              new Format.Builder()
                  .setId(formatId)
                  .setContainerMimeType(containerMimeType)
                  .setSampleMimeType(MimeTypes.VIDEO_H264)
                  .setCodecs(codecs)
                  .setWidth(spsData.width)
                  .setHeight(spsData.height)
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorSpace(spsData.colorSpace)
                          .setColorRange(spsData.colorRange)
                          .setColorTransfer(spsData.colorTransfer)
                          .setLumaBitdepth(spsData.bitDepthLumaMinus8 + 8)
                          .setChromaBitdepth(spsData.bitDepthChromaMinus8 + 8)
                          .build())
                  .setPixelWidthHeightRatio(spsData.pixelWidthHeightRatio)
                  .setInitializationData(initializationData)
                  .setMaxNumReorderSamples(spsData.maxNumReorderFrames)
                  .build());
          hasOutputFormat = true;
          seiReader.setReorderingQueueSize(spsData.maxNumReorderFrames);
          sampleReader.putSps(spsData);
          sampleReader.putPps(ppsData);
          sps.reset();
          pps.reset();
        }
      } else if (sps.isCompleted()) {
        NalUnitUtil.SpsData spsData = NalUnitUtil.parseSpsNalUnit(sps.nalData, 3, sps.nalLength);
        seiReader.setReorderingQueueSize(spsData.maxNumReorderFrames);
        sampleReader.putSps(spsData);
        sps.reset();
      } else if (pps.isCompleted()) {
        NalUnitUtil.PpsData ppsData = NalUnitUtil.parsePpsNalUnit(pps.nalData, 3, pps.nalLength);
        sampleReader.putPps(ppsData);
        pps.reset();
      }
    }
    if (sei.endNalUnit(discardPadding)) {
      int unescapedLength = NalUnitUtil.unescapeStream(sei.nalData, sei.nalLength);
      seiWrapper.reset(sei.nalData, unescapedLength);
      seiWrapper.setPosition(4); // NAL prefix and nal_unit() header.
      seiReader.consume(pesTimeUs, seiWrapper);
    }
    boolean sampleIsKeyFrame = sampleReader.endNalUnit(position, offset, hasOutputFormat);
    if (sampleIsKeyFrame) {
      // This is either an IDR frame or the first I-frame since the random access indicator, so mark
      // it as a keyframe. Clear the flag so that subsequent non-IDR I-frames are not marked as
      // keyframes until we see another random access indicator.
      randomAccessIndicator = false;
    }
  }
  private void assertTracksCreated() {
    Assertions.checkStateNotNull(output);
    Util.castNonNull(sampleReader);
  }

  /** Consumes a stream of NAL units and outputs samples. */
  private static final class SampleReader {

    private static final int DEFAULT_BUFFER_SIZE = 128;

    private final TrackOutput output;
    private final boolean allowNonIdrKeyframes;
    private final boolean detectAccessUnits;
    private final SparseArray<NalUnitUtil.SpsData> sps;
    private final SparseArray<NalUnitUtil.PpsData> pps;
    private final ParsableNalUnitBitArray bitArray;

    private byte[] buffer;
    private int bufferLength;

    // Per NAL unit state. A sample consists of one or more NAL units.
    private int nalUnitType;
    private long nalUnitStartPosition;
    private boolean isFilling;
    private long nalUnitTimeUs;
    private SliceHeaderData previousSliceHeader;
    private SliceHeaderData sliceHeader;

    // Per sample state that gets reset at the start of each sample.
    private boolean readingSample;
    /** Vrai des qu'un NAL AUD a ete rencontre : le decoupage par slice devient inutile. */
    private boolean audVu;

    // ---- 2026-09-05 RE-HORODATAGE (flux dont les PTS sont en ordre de DECODAGE) ----
    // Preuve (segment Vidara) : PES = PTS seul, +1 image par paquet, alors que le flux a
    // des B-frames (ordre de decodage I P B b B...). Le decodeur materiel rend les images
    // dans l'ordre d'affichage avec ces PTS melanges → 1 image sur 4 « en retard » →
    // jetee par ExoPlayer → saccades. ffmpeg/VLC re-trient les PTS en interne, pas
    // MediaCodec. Ici : PTS affichage = PTS(IDR) + (POC / pas) x duree d'image.
    // Ne s'active QUE si on constate un POC qui recule avec un PTS qui avance (jamais
    // le cas d'un flux sain, ou les B ont un PTS inferieur au P decode avant).
    private static final int POC_INCONNU = Integer.MIN_VALUE;
    private final int[] pocFifo = new int[64];
    private final boolean[] idrFifo = new boolean[64];
    private final long[] posFifo = new long[64];
    private int pocFifoTete;
    private int pocFifoTaille;
    private int prevPocMsb;
    private int prevPocLsb;
    private long idrSampleTimeUs = C.TIME_UNSET;
    private long lastSampleTimeUs = C.TIME_UNSET;
    private int lastSamplePoc;
    private boolean lastPocValide;
    private long dureeImageUs = C.TIME_UNSET;
    private int pasPoc = Integer.MAX_VALUE;
    private int preuvesCasse;
    private boolean corrigerPts;

    private void pousserPoc(int poc, boolean idr, long positionNal) {
      if (pocFifoTaille >= pocFifo.length) {
        // Trop de retard : on abandonne proprement (pas de correction).
        pocFifoTaille = 0;
        pocFifoTete = 0;
        corrigerPts = false;
        return;
      }
      int i = (pocFifoTete + pocFifoTaille) % pocFifo.length;
      pocFifo[i] = poc;
      idrFifo[i] = idr;
      posFifo[i] = positionNal;
      pocFifoTaille++;
    }

    /** Calcule le PTS a emettre pour l'echantillon en cours de sortie (ordre de decodage). */
    private long ptsCorrige(long sampleTimeUs, long debutEchantillon, long finEchantillon) {
      // Jette les entrees anterieures a l'echantillon (ne devrait pas arriver).
      while (pocFifoTaille > 0 && posFifo[pocFifoTete] < debutEchantillon) {
        pocFifoTete = (pocFifoTete + 1) % pocFifo.length;
        pocFifoTaille--;
      }
      if (pocFifoTaille == 0 || posFifo[pocFifoTete] >= finEchantillon) {
        // Echantillon sans slice (SPS/PPS/SEI seuls) : inchange.
        return sampleTimeUs;
      }
      int poc = pocFifo[pocFifoTete];
      boolean idr = idrFifo[pocFifoTete];
      pocFifoTete = (pocFifoTete + 1) % pocFifo.length;
      pocFifoTaille--;
      if (poc == POC_INCONNU) {
        lastPocValide = false;
        lastSampleTimeUs = sampleTimeUs;
        return sampleTimeUs;
      }
      if (lastSampleTimeUs != C.TIME_UNSET) {
        long d = sampleTimeUs - lastSampleTimeUs;
        if (d > 1_000 && d < 200_000 && (dureeImageUs == C.TIME_UNSET || d < dureeImageUs)) {
          dureeImageUs = d;
        }
      }
      if (idr) {
        idrSampleTimeUs = sampleTimeUs;
      } else if (lastPocValide) {
        int dp = Math.abs(poc - lastSamplePoc);
        if (dp > 0 && dp < pasPoc) {
          pasPoc = dp;
        }
        if (!corrigerPts && poc < lastSamplePoc && sampleTimeUs > lastSampleTimeUs
            && ++preuvesCasse >= 2) {
          corrigerPts = true;
          android.util.Log.w("H264ReaderAud",
              "PTS en ordre de decodage detectes (POC " + lastSamplePoc + " -> " + poc
              + " mais PTS qui avance) : re-horodatage par POC active, duree image="
              + dureeImageUs + "us");
        }
      }
      lastSamplePoc = poc;
      lastPocValide = true;
      lastSampleTimeUs = sampleTimeUs;
      if (corrigerPts && idrSampleTimeUs != C.TIME_UNSET && dureeImageUs != C.TIME_UNSET
          && pasPoc != Integer.MAX_VALUE && poc >= 0) {
        return idrSampleTimeUs + ((long) poc * dureeImageUs) / pasPoc;
      }
      return sampleTimeUs;
    }

    public void marquerAudVu() {
      audVu = true;
    }
    private long samplePosition;
    private long sampleTimeUs;
    private boolean sampleIsKeyframe;
    private boolean randomAccessIndicator;

    public SampleReader(
        TrackOutput output, boolean allowNonIdrKeyframes, boolean detectAccessUnits) {
      this.output = output;
      this.allowNonIdrKeyframes = allowNonIdrKeyframes;
      this.detectAccessUnits = detectAccessUnits;
      sps = new SparseArray<>();
      pps = new SparseArray<>();
      previousSliceHeader = new SliceHeaderData();
      sliceHeader = new SliceHeaderData();
      buffer = new byte[DEFAULT_BUFFER_SIZE];
      bitArray = new ParsableNalUnitBitArray(buffer, 0, 0);
      reset();
    }

    public boolean needsSpsPps() {
      return detectAccessUnits;
    }

    public void putSps(NalUnitUtil.SpsData spsData) {
      sps.append(spsData.seqParameterSetId, spsData);
    }

    public void putPps(NalUnitUtil.PpsData ppsData) {
      pps.append(ppsData.picParameterSetId, ppsData);
    }

    public void reset() {
      isFilling = false;
      readingSample = false;
      sliceHeader.clear();
      pocFifoTaille = 0;
      pocFifoTete = 0;
      lastSampleTimeUs = C.TIME_UNSET;
      lastPocValide = false;
      // corrigerPts, dureeImageUs, pasPoc : proprietes du flux, conservees.
    }

    public void startNalUnit(
        long position, int type, long pesTimeUs, boolean randomAccessIndicator) {
      nalUnitType = type;
      nalUnitTimeUs = pesTimeUs;
      nalUnitStartPosition = position;
      this.randomAccessIndicator = randomAccessIndicator;
      if ((allowNonIdrKeyframes && nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR)
          || nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR
          || nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR
          || nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_PARTITION_A) {
        // Store the previous header and prepare to populate the new one.
        SliceHeaderData newSliceHeader = previousSliceHeader;
        previousSliceHeader = sliceHeader;
        sliceHeader = newSliceHeader;
        sliceHeader.clear();
        bufferLength = 0;
        isFilling = true;
      }
    }

    /**
     * Called to pass stream data. The data passed should not include the 3 byte start code.
     *
     * @param data Holds the data being passed.
     * @param offset The offset of the data in {@code data}.
     * @param limit The limit (exclusive) of the data in {@code data}.
     */
    public void appendToNalUnit(byte[] data, int offset, int limit) {
      if (!isFilling) {
        return;
      }
      int readLength = limit - offset;
      if (buffer.length < bufferLength + readLength) {
        buffer = Arrays.copyOf(buffer, (bufferLength + readLength) * 2);
      }
      System.arraycopy(data, offset, buffer, bufferLength, readLength);
      bufferLength += readLength;

      bitArray.reset(buffer, 0, bufferLength);
      if (!bitArray.canReadBits(8)) {
        return;
      }
      bitArray.skipBit(); // forbidden_zero_bit
      int nalRefIdc = bitArray.readBits(2);
      bitArray.skipBits(5); // nal_unit_type

      // Read the slice header using the syntax defined in ITU-T Recommendation H.264 (2013)
      // subsection 7.3.3.
      if (!bitArray.canReadExpGolombCodedNum()) {
        return;
      }
      bitArray.readUnsignedExpGolombCodedInt(); // first_mb_in_slice
      if (!bitArray.canReadExpGolombCodedNum()) {
        return;
      }
      int sliceType = bitArray.readUnsignedExpGolombCodedInt();
      // (copie) : plus de raccourci « AUD present → on ignore le reste » : on a besoin
      // du POC de chaque image pour le re-horodatage.
      if (!bitArray.canReadExpGolombCodedNum()) {
        return;
      }
      int picParameterSetId = bitArray.readUnsignedExpGolombCodedInt();
      if (pps.indexOfKey(picParameterSetId) < 0) {
        // We have not seen the PPS yet, so don't try to decode the slice header.
        isFilling = false;
        return;
      }
      NalUnitUtil.PpsData ppsData = pps.get(picParameterSetId);
      NalUnitUtil.SpsData spsData = sps.get(ppsData.seqParameterSetId);
      if (spsData.separateColorPlaneFlag) {
        if (!bitArray.canReadBits(2)) {
          return;
        }
        bitArray.skipBits(2); // colour_plane_id
      }
      if (!bitArray.canReadBits(spsData.frameNumLength)) {
        return;
      }
      boolean fieldPicFlag = false;
      boolean bottomFieldFlagPresent = false;
      boolean bottomFieldFlag = false;
      int frameNum = bitArray.readBits(spsData.frameNumLength);
      if (!spsData.frameMbsOnlyFlag) {
        if (!bitArray.canReadBits(1)) {
          return;
        }
        fieldPicFlag = bitArray.readBit();
        if (fieldPicFlag) {
          if (!bitArray.canReadBits(1)) {
            return;
          }
          bottomFieldFlag = bitArray.readBit();
          bottomFieldFlagPresent = true;
        }
      }
      boolean idrPicFlag = nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR;
      int idrPicId = 0;
      if (idrPicFlag) {
        if (!bitArray.canReadExpGolombCodedNum()) {
          return;
        }
        idrPicId = bitArray.readUnsignedExpGolombCodedInt();
      }
      int picOrderCntLsb = 0;
      int deltaPicOrderCntBottom = 0;
      int deltaPicOrderCnt0 = 0;
      int deltaPicOrderCnt1 = 0;
      if (spsData.picOrderCountType == 0) {
        if (!bitArray.canReadBits(spsData.picOrderCntLsbLength)) {
          return;
        }
        picOrderCntLsb = bitArray.readBits(spsData.picOrderCntLsbLength);
        if (ppsData.bottomFieldPicOrderInFramePresentFlag && !fieldPicFlag) {
          if (!bitArray.canReadExpGolombCodedNum()) {
            return;
          }
          deltaPicOrderCntBottom = bitArray.readSignedExpGolombCodedInt();
        }
      } else if (spsData.picOrderCountType == 1 && !spsData.deltaPicOrderAlwaysZeroFlag) {
        if (!bitArray.canReadExpGolombCodedNum()) {
          return;
        }
        deltaPicOrderCnt0 = bitArray.readSignedExpGolombCodedInt();
        if (ppsData.bottomFieldPicOrderInFramePresentFlag && !fieldPicFlag) {
          if (!bitArray.canReadExpGolombCodedNum()) {
            return;
          }
          deltaPicOrderCnt1 = bitArray.readSignedExpGolombCodedInt();
        }
      }
      sliceHeader.setAll(
          spsData,
          nalRefIdc,
          sliceType,
          frameNum,
          picParameterSetId,
          fieldPicFlag,
          bottomFieldFlagPresent,
          bottomFieldFlag,
          idrPicFlag,
          idrPicId,
          picOrderCntLsb,
          deltaPicOrderCntBottom,
          deltaPicOrderCnt0,
          deltaPicOrderCnt1);
      isFilling = false;
      if (sliceHeader.isFirstVclNalUnitOfPicture(previousSliceHeader)) {
        int poc = POC_INCONNU;
        if (spsData.picOrderCountType == 0 && !fieldPicFlag) {
          int maxLsb = 1 << spsData.picOrderCntLsbLength;
          int msb;
          if (idrPicFlag) {
            prevPocMsb = 0;
            prevPocLsb = 0;
            msb = 0;
          } else if (picOrderCntLsb < prevPocLsb && prevPocLsb - picOrderCntLsb >= maxLsb / 2) {
            msb = prevPocMsb + maxLsb;
          } else if (picOrderCntLsb > prevPocLsb && picOrderCntLsb - prevPocLsb > maxLsb / 2) {
            msb = prevPocMsb - maxLsb;
          } else {
            msb = prevPocMsb;
          }
          poc = msb + picOrderCntLsb;
          if (nalRefIdc != 0) {
            prevPocMsb = msb;
            prevPocLsb = picOrderCntLsb;
          }
        }
        pousserPoc(poc, idrPicFlag, nalUnitStartPosition);
      }
    }

    public boolean endNalUnit(long position, int offset, boolean hasOutputFormat) {
      if (nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_AUD
          || (detectAccessUnits
              && !audVu
              && sliceHeader.isFirstVclNalUnitOfPicture(previousSliceHeader))) {
        // If the NAL unit ending is the start of a new sample, output the previous one.
        if (hasOutputFormat && readingSample) {
          int nalUnitLength = (int) (position - nalUnitStartPosition);
          outputSample(offset + nalUnitLength);
        }
        samplePosition = nalUnitStartPosition;
        sampleTimeUs = nalUnitTimeUs;
        sampleIsKeyframe = false;
        readingSample = true;
      }
      setSampleIsKeyframe();
      // Reset NAL unit type to avoid stale state
      nalUnitType = NalUnitUtil.H264_NAL_UNIT_TYPE_UNSPECIFIED;
      return sampleIsKeyframe;
    }

    private void setSampleIsKeyframe() {
      boolean treatIFrameAsKeyframe =
          allowNonIdrKeyframes ? sliceHeader.isISlice() : randomAccessIndicator;
      sampleIsKeyframe |=
          nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR
              || (treatIFrameAsKeyframe && nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR);
    }

    private void outputSample(int offset) {
      if (sampleTimeUs == C.TIME_UNSET || nalUnitStartPosition == samplePosition) {
        return;
      }
      @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
      int size = (int) (nalUnitStartPosition - samplePosition);
      output.sampleMetadata(
          ptsCorrige(sampleTimeUs, samplePosition, nalUnitStartPosition), flags, size, offset, null);
    }

    private static final class SliceHeaderData {

      private static final int SLICE_TYPE_I = 2;
      private static final int SLICE_TYPE_ALL_I = 7;

      private boolean isComplete;
      private boolean hasSliceType;

      @Nullable private SpsData spsData;
      private int nalRefIdc;
      private int sliceType;
      private int frameNum;
      private int picParameterSetId;
      private boolean fieldPicFlag;
      private boolean bottomFieldFlagPresent;
      private boolean bottomFieldFlag;
      private boolean idrPicFlag;
      private int idrPicId;
      private int picOrderCntLsb;
      private int deltaPicOrderCntBottom;
      private int deltaPicOrderCnt0;
      private int deltaPicOrderCnt1;

      public void clear() {
        hasSliceType = false;
        isComplete = false;
      }

      public void setSliceType(int sliceType) {
        this.sliceType = sliceType;
        hasSliceType = true;
      }

      public void setAll(
          SpsData spsData,
          int nalRefIdc,
          int sliceType,
          int frameNum,
          int picParameterSetId,
          boolean fieldPicFlag,
          boolean bottomFieldFlagPresent,
          boolean bottomFieldFlag,
          boolean idrPicFlag,
          int idrPicId,
          int picOrderCntLsb,
          int deltaPicOrderCntBottom,
          int deltaPicOrderCnt0,
          int deltaPicOrderCnt1) {
        this.spsData = spsData;
        this.nalRefIdc = nalRefIdc;
        this.sliceType = sliceType;
        this.frameNum = frameNum;
        this.picParameterSetId = picParameterSetId;
        this.fieldPicFlag = fieldPicFlag;
        this.bottomFieldFlagPresent = bottomFieldFlagPresent;
        this.bottomFieldFlag = bottomFieldFlag;
        this.idrPicFlag = idrPicFlag;
        this.idrPicId = idrPicId;
        this.picOrderCntLsb = picOrderCntLsb;
        this.deltaPicOrderCntBottom = deltaPicOrderCntBottom;
        this.deltaPicOrderCnt0 = deltaPicOrderCnt0;
        this.deltaPicOrderCnt1 = deltaPicOrderCnt1;
        isComplete = true;
        hasSliceType = true;
      }

      public boolean isISlice() {
        return hasSliceType && (sliceType == SLICE_TYPE_ALL_I || sliceType == SLICE_TYPE_I);
      }

      private boolean isFirstVclNalUnitOfPicture(SliceHeaderData other) {
        if (!isComplete) {
          return false;
        }
        if (!other.isComplete) {
          return true;
        }
        // See ISO 14496-10 subsection 7.4.1.2.4.
        SpsData spsData = Assertions.checkStateNotNull(this.spsData);
        SpsData otherSpsData = Assertions.checkStateNotNull(other.spsData);
        return frameNum != other.frameNum
            || picParameterSetId != other.picParameterSetId
            || fieldPicFlag != other.fieldPicFlag
            || (bottomFieldFlagPresent
                && other.bottomFieldFlagPresent
                && bottomFieldFlag != other.bottomFieldFlag)
            || (nalRefIdc != other.nalRefIdc && (nalRefIdc == 0 || other.nalRefIdc == 0))
            || (spsData.picOrderCountType == 0
                && otherSpsData.picOrderCountType == 0
                && (picOrderCntLsb != other.picOrderCntLsb
                    || deltaPicOrderCntBottom != other.deltaPicOrderCntBottom))
            || (spsData.picOrderCountType == 1
                && otherSpsData.picOrderCountType == 1
                && (deltaPicOrderCnt0 != other.deltaPicOrderCnt0
                    || deltaPicOrderCnt1 != other.deltaPicOrderCnt1))
            || idrPicFlag != other.idrPicFlag
            || (idrPicFlag && idrPicId != other.idrPicId);
      }
    }
  }
}
