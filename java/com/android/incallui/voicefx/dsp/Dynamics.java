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
 * Level-control building blocks: noise gate, compressor, peak limiter and a very slow level matcher.
 *
 * <p>All four exist for one reason: a converted voice has to sit in the call at the same loudness,
 * with the same evenness, as the natural one. Without them a pitch/formant shift either sounds
 * quiet and thin or pumps audibly between syllables.
 */
public final class Dynamics {

  private Dynamics() {}

  private static final double DB_TO_LIN = 0.11512925464970229; // ln(10)/20

  private static float dbToLinear(float db) {
    return (float) Math.exp(db * DB_TO_LIN);
  }

  /** Downward expander: everything below the threshold is pushed further down. */
  public static final class Gate {

    private float threshold = 0.0018f; // ~ -55 dBFS
    private float depth = 0.55f; // 0 = off, 1 = maximum attenuation
    private float attackCoefficient = 0.02f;
    private float releaseCoefficient = 0.0004f;
    private float envelope;
    private float gain = 1f;

    public void configure(float sampleRate, float thresholdDb, float attackMs, float releaseMs) {
      threshold = dbToLinear(thresholdDb);
      attackCoefficient = (float) (1.0 - Math.exp(-1.0 / (0.001 * attackMs * sampleRate)));
      releaseCoefficient = (float) (1.0 - Math.exp(-1.0 / (0.001 * releaseMs * sampleRate)));
      envelope = 0f;
      gain = 1f;
    }

    public void setDepth(float value) {
      depth = value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    public void reset() {
      envelope = 0f;
      gain = 1f;
    }

    public float tick(float x) {
      float rectified = Math.abs(x);
      envelope += (rectified - envelope) * (rectified > envelope ? attackCoefficient : releaseCoefficient);
      float target = 1f;
      if (envelope < threshold) {
        float ratio = threshold > 0f ? envelope / threshold : 0f;
        target = 1f - depth * (1f - ratio);
        if (target < 0.02f) {
          target = 0.02f;
        }
      }
      gain += (target - gain) * (target > gain ? attackCoefficient : releaseCoefficient);
      return x * gain;
    }
  }

  /** Soft-knee feed-forward compressor with makeup gain. */
  public static final class Compressor {

    private float thresholdDb = -20f;
    private float ratio = 3f;
    private float kneeDb = 8f;
    private float makeup = 1f;
    private float attackCoefficient;
    private float releaseCoefficient;
    private float envelopeDb = -90f;
    private float gainDb;

    public void configure(
        float sampleRate, float thresholdDb, float ratio, float attackMs, float releaseMs,
        float makeupDb, float kneeDb) {
      this.thresholdDb = thresholdDb;
      this.ratio = ratio < 1f ? 1f : ratio;
      this.kneeDb = kneeDb;
      this.makeup = dbToLinear(makeupDb);
      attackCoefficient = (float) (1.0 - Math.exp(-1.0 / (0.001 * attackMs * sampleRate)));
      releaseCoefficient = (float) (1.0 - Math.exp(-1.0 / (0.001 * releaseMs * sampleRate)));
      envelopeDb = -90f;
      gainDb = 0f;
    }

    public void reset() {
      envelopeDb = -90f;
      gainDb = 0f;
    }

    public float tick(float x) {
      float level = Math.abs(x);
      float levelDb = level > 1e-6f ? (float) (Math.log(level) * 8.685889638f) : -120f;
      envelopeDb +=
          (levelDb - envelopeDb) * (levelDb > envelopeDb ? attackCoefficient : releaseCoefficient);
      float over = envelopeDb - thresholdDb;
      float reductionDb;
      if (over <= -kneeDb * 0.5f) {
        reductionDb = 0f;
      } else if (over >= kneeDb * 0.5f) {
        reductionDb = over * (1f - 1f / ratio);
      } else {
        float knee = over + kneeDb * 0.5f;
        reductionDb = (1f - 1f / ratio) * knee * knee / (2f * kneeDb);
      }
      gainDb += (reductionDb - gainDb) * (reductionDb > gainDb ? attackCoefficient : releaseCoefficient);
      return x * (float) Math.exp(-gainDb * DB_TO_LIN) * makeup;
    }
  }

  /** Peak limiter with instant attack and a soft clip safety net. */
  public static final class Limiter {

    private float ceiling = 0.89f;
    private float releaseCoefficient;
    private float gain = 1f;

    public void configure(float sampleRate, float ceilingDb, float releaseMs) {
      ceiling = dbToLinear(ceilingDb);
      releaseCoefficient = (float) (1.0 - Math.exp(-1.0 / (0.001 * releaseMs * sampleRate)));
      gain = 1f;
    }

    public void reset() {
      gain = 1f;
    }

    public float tick(float x) {
      float peak = Math.abs(x);
      float target = peak > ceiling ? ceiling / peak : 1f;
      if (target < gain) {
        gain = target;
      } else {
        gain += (1f - gain) * releaseCoefficient;
      }
      float value = x * gain;
      // Soft clip: linear below 0.8, then a tanh shaped shoulder up to full scale.
      float magnitude = Math.abs(value);
      if (magnitude > 0.8f) {
        float compressed = 0.8f + (ceiling - 0.8f) * (float) Math.tanh((magnitude - 0.8f) / (ceiling - 0.8f));
        value = value < 0f ? -compressed : compressed;
      }
      return value;
    }
  }

  /**
   * Slow loudness matching: nudges the output level towards the input level, a few dB at most.
   * Selecting a preset should never change how loud the user sounds to the other party.
   */
  public static final class LevelMatcher {

    private float gain = 1f;
    private float inputLevel = 0.05f;
    private float outputLevel = 0.05f;
    private float coefficient;
    private float maxGain = 2.8f;
    private float minGain = 0.35f;

    public void configure(float sampleRate, float timeConstantMs, float maxGainDb, float minGainDb) {
      coefficient = (float) (1.0 - Math.exp(-1.0 / (0.001 * timeConstantMs * sampleRate)));
      maxGain = dbToLinear(maxGainDb);
      minGain = dbToLinear(minGainDb);
      reset();
    }

    public void reset() {
      gain = 1f;
      inputLevel = 0.05f;
      outputLevel = 0.05f;
    }

    public float tick(float input, float output) {
      inputLevel += (Math.abs(input) - inputLevel) * coefficient;
      float level = Math.abs(output) * gain;
      outputLevel += (level - outputLevel) * coefficient;
      if (inputLevel > 0.004f && outputLevel > 0.0008f) {
        float target = inputLevel / outputLevel;
        if (target > maxGain) {
          target = maxGain;
        } else if (target < minGain) {
          target = minGain;
        }
        gain += (target - gain) * coefficient;
      }
      return output * gain;
    }

    public float getGain() {
      return gain;
    }
  }
}
