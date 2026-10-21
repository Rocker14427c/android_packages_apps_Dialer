/*
 * Copyright (C) 2026 The AxionOS Project
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

package com.android.incallui.voicefx.dsp;

/**
 * Pitch shifter with an <b>independent</b> vocal-tract (formant) control.
 *
 * <p>This is the part that decides whether a preset sounds like a human being or like a chipmunk.
 * A delay-line/granular shifter (what the old engine used) resamples the signal, so raising the
 * pitch by {@code r} also moves every formant up by {@code r}. A real female voice is not a male
 * voice played 1.5x faster: the glottal pitch roughly doubles while the vocal tract only shortens
 * by 15-20 %. So pitch and envelope have to be controlled separately.
 *
 * <h3>How it works</h3>
 *
 * <ol>
 *   <li><b>Analysis</b>: 1024-sample Hann frames every 256 samples (75 % overlap), per-bin
 *       instantaneous frequency from the phase difference of consecutive frames.
 *   <li><b>Pitch</b>: a phase vocoder time-stretches the frame stream. Synthesis frames are laid
 *       down every {@code Hs = round(256 * alpha)} samples, and a cubic resampler downstream reads
 *       the stretched stream {@code alpha} times faster, which is the pitch shift. Phases are
 *       locked per spectral peak (identity phase locking, Laroche &amp; Dolson) which removes most
 *       of the "phasiness" a plain vocoder has on speech.
 *   <li><b>Formants</b>: the spectral envelope is estimated by smoothing {@code log|X(f)|} with a
 *       constant-Q (third-octave) window, then resampled along frequency. The envelope is warped by
 *       {@code formantRatio / alpha} inside this stage, so that after the {@code alpha} resampling
 *       the net formant movement is exactly {@code formantRatio}. Each frame is energy normalised,
 *       so moving the formants never changes loudness.
 * </ol>
 *
 * <p>Overlap-add is normalised by the accumulated squared window. That makes the reconstruction
 * exact for any synthesis hop (the hop follows the pitch ratio and the adaptive pitch target), and
 * it is also what keeps a sudden pitch change from producing a level jump.
 *
 * <p>Everything is allocation-free after construction.
 */
public final class PitchFormantShifter {

  /**
   * Analysis/synthesis frame length.
   *
   * <p>The frequency resolution of the vocoder has to resolve the harmonics of the *lowest* voice we
   * convert from. At 48 kHz a 1024 point frame gives 47 Hz bins, so the 110 Hz spacing of a male
   * voice is only two bins and neighbouring partials share a peak region: identity phase locking
   * cannot separate them and the converted voice becomes hoarse. 2048 points (23 Hz bins) resolves
   * four or five bins per partial, which is what makes the upward (female) shift clean.
   */
  private static final int FRAME = 2048;
  private static final int ANALYSIS_HOP = FRAME / 4;
  private static final int HALF = FRAME / 2;
  private static final int BINS = HALF + 1;
  private static final double TWO_PI = 2.0 * Math.PI;
  private static final float HOP_SCALE = (float) (TWO_PI / FRAME);

  /** Samples of overlap-add that are thrown away at start-up, while the window weight fills in. */
  private static final int EMIT_START = FRAME;
  private static final float PEAK_FLOOR = 0.0005f;
  /** Half width (in bins) of the constant-Q envelope smoother: at least this many bins, and
   * otherwise this fraction of the bin index, so low formants are not smeared away. */
  private static final int ENV_MIN_BINS = 6;
  private static final float ENV_Q_FRACTION = 0.25f;

  private static final int IN_RING = FRAME * 2;
  private static final int IN_MASK = IN_RING - 1;
  private static final int OLA_RING = 4096;
  private static final int OLA_MASK = OLA_RING - 1;

  private final Fft fft = new Fft(FRAME);
  private final float[] window = new float[FRAME];
  private final float[] expectedAdvance = new float[BINS];

  // Analysis state.
  private final float[] inRing = new float[IN_RING];
  private long inWrite;
  private long frameStart;

  private final float[] re = new float[FRAME];
  private final float[] im = new float[FRAME];
  private final float[] magnitude = new float[BINS];
  private final float[] phase = new float[BINS];
  private final float[] previousPhase = new float[BINS];
  private final float[] deviation = new float[BINS];
  private final float[] accumulatedPhase = new float[BINS];
  private final int[] peakRegion = new int[BINS];
  private final int[] peaks = new int[BINS];
  private int peakCount;

  // Spectral envelope.
  private final float[] logEnvelope = new float[BINS];
  private final float[] prefix = new float[BINS + 1];
  private final float[] envelopeGain = new float[BINS];

  // Overlap-add state.
  private final float[] olaRing = new float[OLA_RING];
  private final float[] weightRing = new float[OLA_RING];
  private long synthesizePos;
  private long emitPos;

  private double stretch = 1.0;
  private int synthesisHop = ANALYSIS_HOP;
  private float formantRatio = 1f;
  private boolean formantWarp;

  public PitchFormantShifter() {
    for (int i = 0; i < FRAME; i++) {
      window[i] = (float) (0.5 - 0.5 * Math.cos(TWO_PI * i / FRAME));
    }
    for (int k = 0; k < BINS; k++) {
      expectedAdvance[k] = HOP_SCALE * ANALYSIS_HOP * k;
    }
    reset();
  }

  public void reset() {
    java.util.Arrays.fill(inRing, 0f);
    java.util.Arrays.fill(re, 0f);
    java.util.Arrays.fill(im, 0f);
    java.util.Arrays.fill(previousPhase, 0f);
    java.util.Arrays.fill(accumulatedPhase, 0f);
    java.util.Arrays.fill(peakRegion, -1);
    java.util.Arrays.fill(olaRing, 0f);
    java.util.Arrays.fill(weightRing, 0f);
    inWrite = FRAME; // prime the analysis buffer with silence
    frameStart = 0;
    synthesizePos = 0;
    emitPos = 0;
    peakCount = 0;
  }

  /**
   * Sets the pitch ratio. Call between blocks: the synthesis hop (and therefore the resampler step
   * the caller must use) is recomputed here, and {@link #getEffectiveStretch()} returns the value
   * that was actually realised.
   */
  public void setStretch(double alpha) {
    if (alpha < 0.4) {
      alpha = 0.4;
    } else if (alpha > 2.5) {
      alpha = 2.5;
    }
    stretch = alpha;
    int hop = (int) Math.round(ANALYSIS_HOP * alpha);
    if (hop < ANALYSIS_HOP / 2) {
      hop = ANALYSIS_HOP / 2;
    } else if (hop > ANALYSIS_HOP * 2) {
      hop = ANALYSIS_HOP * 2;
    }
    synthesisHop = hop;
  }

  /** Stretch actually applied (integer synthesis hop divided by the analysis hop). */
  public double getEffectiveStretch() {
    return synthesisHop / (double) ANALYSIS_HOP;
  }

  public double getRequestedStretch() {
    return stretch;
  }

  /** Target formant scaling: 1.0 keeps the vocal tract, 1.2 is a ~20 % shorter tract. */
  public void setFormantRatio(float ratio) {
    if (ratio < 0.5f) {
      ratio = 0.5f;
    } else if (ratio > 2f) {
      ratio = 2f;
    }
    formantRatio = ratio;
    formantWarp = Math.abs(ratio - 1f) > 0.005f;
  }

  /** Feeds {@code count} samples, appending the stretched result to {@code out}. */
  public void process(float[] in, int offset, int count, FloatFifo out) {
    for (int i = 0; i < count; i++) {
      inRing[(int) (inWrite & IN_MASK)] = in[offset + i];
      inWrite++;
    }
    while (inWrite - (frameStart + FRAME) >= 0) {
      analyzeSynthesizeOne(out);
      frameStart += ANALYSIS_HOP;
    }
  }

  // ---------------------------------------------------------------------------------

  private void analyzeSynthesizeOne(FloatFifo out) {
    for (int i = 0; i < FRAME; i++) {
      re[i] = inRing[(int) ((frameStart + i) & IN_MASK)] * window[i];
      im[i] = 0f;
    }
    fft.forward(re, im);

    float maxMagnitude = 0f;
    for (int k = 0; k < BINS; k++) {
      float binRe = re[k];
      float binIm = im[k];
      float mag = (float) Math.sqrt(binRe * binRe + binIm * binIm);
      magnitude[k] = mag;
      phase[k] = (float) Math.atan2(binIm, binRe);
      if (mag > maxMagnitude) {
        maxMagnitude = mag;
      }
    }

    // Per-bin instantaneous frequency expressed as a deviation (in bins) from the bin centre.
    for (int k = 0; k < BINS; k++) {
      float delta = phase[k] - previousPhase[k] - expectedAdvance[k];
      delta -= TWO_PI * Math.round(delta / TWO_PI);
      deviation[k] = (float) ((delta / HOP_SCALE) / ANALYSIS_HOP);
    }

    findPeakRegions(maxMagnitude * PEAK_FLOOR);

    if (formantWarp) {
      applyFormantWarp();
    }

    // Identity phase locking: every bin of a peak's region inherits the peak's accumulated phase
    // and keeps its own phase offset relative to that peak.
    // Two passes on purpose. Every accumulated phase must be advanced for this frame *before*
    // any bin reads its region's peak phase: doing both in one pass makes the bins below the peak
    // read the previous frame's value while the bins above it read the new one, which is a
    // half-frame phase error and cancels most of the spectrum in the overlap-add.
    for (int k = 0; k < BINS; k++) {
      int region = peakRegion[k];
      if (region >= 0 && peaks[region] != k) {
        continue; // locked to a peak; it inherits the peak's phase below
      }
      accumulatedPhase[k] =
          wrapPhase(accumulatedPhase[k] + HOP_SCALE * (k + deviation[k]) * synthesisHop);
    }
    for (int k = 0; k < BINS; k++) {
      int region = peakRegion[k];
      float synthPhase;
      if (region >= 0) {
        int peak = peaks[region];
        synthPhase = wrapPhase(accumulatedPhase[peak] + (phase[k] - phase[peak]));
      } else {
        synthPhase = accumulatedPhase[k];
      }
      float mag = magnitude[k];
      re[k] = mag * (float) Math.cos(synthPhase);
      im[k] = mag * (float) Math.sin(synthPhase);
    }
    // Conjugate symmetry for the inverse transform.
    for (int k = 1; k < HALF; k++) {
      re[FRAME - k] = re[k];
      im[FRAME - k] = -im[k];
    }
    im[0] = 0f;
    im[HALF] = 0f;

    System.arraycopy(phase, 0, previousPhase, 0, BINS);

    fft.inverse(re, im);
    overlapAdd();
    emit(out);
  }

  private static float wrapPhase(float value) {
    return (float) (value - TWO_PI * Math.round(value / TWO_PI));
  }

  private void findPeakRegions(float floor) {
    peakCount = 0;
    for (int k = 1; k < BINS - 1; k++) {
      float mag = magnitude[k];
      if (mag > floor && mag > magnitude[k - 1] && mag >= magnitude[k + 1]) {
        peaks[peakCount++] = k;
      }
    }
    if (peakCount == 0) {
      java.util.Arrays.fill(peakRegion, -1);
      return;
    }
    java.util.Arrays.fill(peakRegion, -1);
    for (int p = 0; p < peakCount; p++) {
      int peak = peaks[p];
      int lower = (p == 0) ? 0 : (peaks[p - 1] + peak) / 2;
      int upper = (p == peakCount - 1) ? BINS - 1 : (peak + peaks[p + 1]) / 2;
      for (int k = lower; k <= upper; k++) {
        peakRegion[k] = p;
      }
      peakRegion[peak] = p;
    }
  }

  /**
   * Estimates the spectral envelope (vocal-tract response) of the current frame and applies the
   * formant scaling. The smoothing window is proportional to frequency (constant-Q), which is what
   * keeps it from eating the low formants of a low-pitched voice.
   */
  private void applyFormantWarp() {
    for (int k = 0; k < BINS; k++) {
      logEnvelope[k] = (float) Math.log(magnitude[k] + 1e-7f);
    }
    prefix[0] = 0f;
    for (int k = 0; k < BINS; k++) {
      prefix[k + 1] = prefix[k] + logEnvelope[k];
    }
    for (int k = 0; k < BINS; k++) {
      int halfWidth = Math.max(ENV_MIN_BINS, (int) (k * ENV_Q_FRACTION));
      int lower = Math.max(0, k - halfWidth);
      int upper = Math.min(BINS - 1, k + halfWidth);
      logEnvelope[k] = (prefix[upper + 1] - prefix[lower]) / (upper - lower + 1);
    }

    final float warp = formantRatio / (float) getEffectiveStretch();
    for (int k = 0; k < BINS; k++) {
      float source = k / warp;
      if (source > BINS - 1) {
        source = BINS - 1;
      }
      int index = (int) source;
      float frac = source - index;
      float warped =
          (index >= BINS - 1)
              ? logEnvelope[BINS - 1]
              : logEnvelope[index] * (1f - frac) + logEnvelope[index + 1] * frac;
      float gain = (float) Math.exp(warped - logEnvelope[k]);
      if (gain < 0.03f) {
        gain = 0.03f;
      } else if (gain > 24f) {
        gain = 24f;
      }
      envelopeGain[k] = gain;
    }
    // Three tap smoothing across frequency keeps the warp from sounding gritty.
    float previous = envelopeGain[0];
    for (int k = 1; k < BINS - 1; k++) {
      float current = envelopeGain[k];
      envelopeGain[k] = 0.25f * previous + 0.5f * current + 0.25f * envelopeGain[k + 1];
      previous = current;
    }

    float before = 0f;
    float after = 0f;
    for (int k = 0; k < BINS; k++) {
      before += magnitude[k] * magnitude[k];
      float shaped = magnitude[k] * envelopeGain[k];
      after += shaped * shaped;
    }
    float correction = 1f;
    if (before > 1e-12f && after > 1e-12f) {
      correction = (float) Math.sqrt(before / after);
      if (correction < 0.5f) {
        correction = 0.5f;
      } else if (correction > 2f) {
        correction = 2f;
      }
    }
    for (int k = 0; k < BINS; k++) {
      magnitude[k] *= envelopeGain[k] * correction;
    }
  }

  private void overlapAdd() {
    long base = synthesizePos;
    for (int i = 0; i < FRAME; i++) {
      int slot = (int) ((base + i) & OLA_MASK);
      float w = window[i];
      olaRing[slot] += re[i] * w;
      weightRing[slot] += w * w;
    }
    synthesizePos += synthesisHop;
  }

  private void emit(FloatFifo out) {
    int count = 0;
    while (emitPos < synthesizePos) {
      int slot = (int) (emitPos & OLA_MASK);
      float weight = weightRing[slot];
      float value = weight > 1e-4f ? olaRing[slot] / weight : 0f;
      if (emitPos >= EMIT_START) {
        emitBuffer[count++] = value;
      }
      olaRing[slot] = 0f;
      weightRing[slot] = 0f;
      emitPos++;
    }
    if (count > 0) {
      out.write(emitBuffer, 0, count);
    }
  }

  private final float[] emitBuffer = new float[ANALYSIS_HOP * 3 + 8];
}
