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
 * Normalised-autocorrelation pitch tracker used to discover the speaker's own register.
 *
 * <p>Every voice changer that applies a fixed semitone offset is wrong for about half of its users:
 * +7 semitones on a 90 Hz voice still lands in the male range, and the same offset on a 190 Hz
 * voice leaves the female range entirely. This tracker measures the fundamental of the person
 * speaking (who is, after all, the only speaker this call will ever hear) so a preset can aim at an
 * absolute target register instead of a blind offset.
 *
 * <p>Robustness matters far more than accuracy here. A wrong octave would be much worse than no
 * estimate at all, so the chain is: decimate to 12 kHz, 30 ms frames, octave-error corrected
 * autocorrelation peak, per-frame jump rejection, a five frame median, and finally a rolling median
 * over the last two seconds of accepted frames. Unvoiced frames are dropped instead of guessed.
 */
public final class PitchTracker {

  private static final int DECIMATION = 4;
  private static final float DECIMATED_RATE = 48000f / DECIMATION;
  private static final int WINDOW = 360; // 30 ms at 12 kHz
  private static final int HOP = 180; // 15 ms
  private static final float MIN_F0 = 65f;
  private static final float MAX_F0 = 420f;
  private static final int MIN_LAG = (int) (DECIMATED_RATE / MAX_F0);
  private static final int MAX_LAG = (int) (DECIMATED_RATE / MIN_F0);
  private static final float VOICING_THRESHOLD = 0.18f;
  private static final float RMS_FLOOR = 0.0035f; // about -49 dBFS
  private static final int RECENT_LENGTH = 5;
  /** Rolling median window: 128 frames of 15 ms, so about two seconds of speech. */
  private static final int ESTIMATE_WINDOW = 128;
  private static final int MIN_ESTIMATES = 8;

  private final Biquad antiAlias1 = new Biquad();
  private final Biquad antiAlias2 = new Biquad();
  private final float[] decimated = new float[WINDOW * 2];
  private final float[] difference = new float[MAX_LAG + 2];
  private final float[] normalised = new float[MAX_LAG + 2];
  private final float[] recent = new float[RECENT_LENGTH];
  private final float[] recentScratch = new float[RECENT_LENGTH];
  private final float[] estimates = new float[ESTIMATE_WINDOW];
  private final float[] sortScratch = new float[ESTIMATE_WINDOW];

  private int decimateCounter;
  private int decimatedFill;
  private int recentCount;
  private int recentIndex;
  private int estimateCount;
  private int estimateIndex;
  private float windowMedian;
  private long voicedFrames;
  private long analysedFrames;
  private float lastF0;

  public PitchTracker() {
    antiAlias1.set(Biquad.LOWPASS, DECIMATED_RATE, 4600f, 0.541f, 0f);
    antiAlias2.set(Biquad.LOWPASS, DECIMATED_RATE, 4600f, 1.306f, 0f);
    reset();
  }

  public void reset() {
    antiAlias1.reset();
    antiAlias2.reset();
    java.util.Arrays.fill(decimated, 0f);
    java.util.Arrays.fill(recent, 0f);
    java.util.Arrays.fill(estimates, 0f);
    decimateCounter = 0;
    decimatedFill = 0;
    recentCount = 0;
    recentIndex = 0;
    estimateCount = 0;
    estimateIndex = 0;
    windowMedian = 0f;
    voicedFrames = 0;
    analysedFrames = 0;
    lastF0 = 0f;
  }

  public void push(float[] samples, int offset, int count) {
    for (int i = 0; i < count; i++) {
      float filtered = antiAlias2.tick(antiAlias1.tick(samples[offset + i]));
      if (++decimateCounter >= DECIMATION) {
        decimateCounter = 0;
        if (decimatedFill < decimated.length) {
          decimated[decimatedFill++] = filtered;
        }
      }
    }
    while (decimatedFill >= WINDOW) {
      analyseFrame();
      System.arraycopy(decimated, HOP, decimated, 0, decimatedFill - HOP);
      decimatedFill -= HOP;
    }
  }

  private void analyseFrame() {
    float energy = 0f;
    for (int i = 0; i < WINDOW; i++) {
      energy += decimated[i] * decimated[i];
    }
    float rms = (float) Math.sqrt(energy / WINDOW);
    analysedFrames++;
    if (rms < RMS_FLOOR) {
      return;
    }

    // YIN: cumulative mean normalised squared difference. It is markedly better than a plain
    // autocorrelation at ignoring the formant structure of the vowel being spoken.
    difference[0] = 0f;
    for (int lag = 1; lag <= MAX_LAG; lag++) {
      float sum = 0f;
      int limit = WINDOW - lag;
      for (int i = 0; i < limit; i++) {
        float delta = decimated[i] - decimated[i + lag];
        sum += delta * delta;
      }
      difference[lag] = sum;
    }
    normalised[0] = 1f;
    float running = 0f;
    for (int lag = 1; lag <= MAX_LAG; lag++) {
      running += difference[lag];
      normalised[lag] = running > 0f ? difference[lag] * lag / running : 1f;
    }

    int chosen = -1;
    for (int lag = MIN_LAG; lag < MAX_LAG; lag++) {
      if (normalised[lag] < VOICING_THRESHOLD) {
        // Walk down to the bottom of this dip for the most precise period.
        while (lag + 1 < MAX_LAG && normalised[lag + 1] < normalised[lag]) {
          lag++;
        }
        chosen = lag;
        break;
      }
    }
    if (chosen < 0) {
      return;
    }
    // Parabolic interpolation around the dip for sub-sample accuracy.
    float left = normalised[chosen - 1];
    float centre = normalised[chosen];
    float right = normalised[chosen + 1];
    float denominator = left - 2f * centre + right;
    float offset = denominator != 0f ? 0.5f * (left - right) / denominator : 0f;
    if (offset > 0.5f) {
      offset = 0.5f;
    } else if (offset < -0.5f) {
      offset = -0.5f;
    }
    float f0 = DECIMATED_RATE / (chosen + offset);
    if (f0 < MIN_F0 || f0 > MAX_F0) {
      return;
    }
    if (!acceptEstimate(f0)) {
      return;
    }
    recent[recentIndex] = f0;
    recentIndex = (recentIndex + 1) % RECENT_LENGTH;
    if (recentCount < RECENT_LENGTH) {
      recentCount++;
    }
    if (recentCount < 3) {
      return;
    }
    float median = medianOfRecent();
    if (median <= 0f) {
      return;
    }
    lastF0 = median;
    voicedFrames++;
    addEstimate(median);
  }

  /** Rejects an estimate that jumps by more than a third relative to the recent median. */
  private boolean acceptEstimate(float f0) {
    if (recentCount < 2) {
      return true;
    }
    float reference = medianOfRecent();
    if (reference <= 0f) {
      return true;
    }
    float ratio = f0 / reference;
    return ratio > 0.72f && ratio < 1.38f;
  }

  /** Median of the last few accepted per-frame estimates (insertion sort on a fixed buffer). */
  private float medianOfRecent() {
    int count = recentCount;
    if (count == 0) {
      return 0f;
    }
    for (int i = 0; i < count; i++) {
      float value = recent[i];
      int j = i - 1;
      while (j >= 0 && recentScratch[j] > value) {
        recentScratch[j + 1] = recentScratch[j];
        j--;
      }
      recentScratch[j + 1] = value;
    }
    return recentScratch[count / 2];
  }

  private void addEstimate(float f0) {
    estimates[estimateIndex] = f0;
    estimateIndex = (estimateIndex + 1) % ESTIMATE_WINDOW;
    if (estimateCount < ESTIMATE_WINDOW) {
      estimateCount++;
    }
    if (estimateCount >= MIN_ESTIMATES) {
      System.arraycopy(estimates, 0, sortScratch, 0, estimateCount);
      java.util.Arrays.sort(sortScratch, 0, estimateCount);
      windowMedian = sortScratch[estimateCount / 2];
    }
  }

  public boolean hasEstimate() {
    return windowMedian > 0f;
  }

  public long getVoicedFrames() {
    return voicedFrames;
  }

  public long getAnalysedFrames() {
    return analysedFrames;
  }

  public float getLastF0() {
    return lastF0;
  }

  /** Rolling median fundamental over roughly the last two seconds, or 0 when unknown. */
  public float getMedianF0() {
    return windowMedian;
  }
}
