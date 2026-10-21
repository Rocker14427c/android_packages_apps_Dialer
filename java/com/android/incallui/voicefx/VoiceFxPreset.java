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

package com.android.incallui.voicefx;

import com.android.incallui.voicefx.dsp.Biquad;

/**
 * One voice, described as data.
 *
 * <p>Keeping every number here (instead of scattering constants through the DSP) means the audible
 * result of a preset can be read, compared and tuned without touching the engine, and it is why the
 * UI, the tests and the audio path cannot disagree about what "Girl" means.
 *
 * <h3>The four things that actually make a voice read as male/female/child</h3>
 *
 * <ol>
 *   <li><b>Fundamental frequency</b> - the register. An adult male speaks around 100-130 Hz, an
 *       adult female around 180-230 Hz, a child around 250-330 Hz. This is the pitch shift.
 *   <li><b>Formants</b> - the resonances of the vocal tract, which is ~15-20 % shorter in a female
 *       speaker and ~35 % shorter in a child. Shifting these together with the pitch is exactly
 *       what produces the "chipmunk" sound of cheap changers, so they are set independently here.
 *   <li><b>Spectral tilt / EQ</b> - female voices carry comparatively less energy below 300 Hz and
 *       more in the 2.5-5 kHz presence band; a "laptop anonymous" voice is band limited and hollow
 *       around 800-1000 Hz with the top rolled off.
 *   <li><b>Dynamics and air</b> - real voices are compressed by the room and the phone, and female
 *       speech carries audible breath noise. Both are what make a converted voice stop sounding
 *       synthetic.
 * </ol>
 */
public final class VoiceFxPreset {

  /** One RBJ biquad, exactly as the mixing console would show it. */
  public static final class Band {
    public final int type;
    public final float freqHz;
    public final float q;
    public final float gainDb;

    public Band(int type, float freqHz, float q, float gainDb) {
      this.type = type;
      this.freqHz = freqHz;
      this.q = q;
      this.gainDb = gainDb;
    }
  }

  public final int id;

  /** Pitch in semitones used until the speaker's own register has been measured. */
  public final float fallbackPitchSemitones;

  /** Absolute fundamental to aim for, or 0 to always use the fixed offset. */
  public final float targetF0Hz;

  public final float minPitchSemitones;
  public final float maxPitchSemitones;

  /** Vocal-tract scaling: 1.19 = a tract 19 % shorter than the speaker's. */
  public final float formantRatio;

  public final Band[] eq;
  public final float breathAmount;

  public final float gateThresholdDb;
  public final float gateDepth;
  public final float compressorThresholdDb;
  public final float compressorRatio;
  public final float compressorAttackMs;
  public final float compressorReleaseMs;
  public final float compressorMakeupDb;

  /** Early reflections in milliseconds and their linear gain (0 gain = unused). */
  public final float[] reflectionDelaysMs;
  public final float[] reflectionGains;

  public final float driveDb;
  public final float driveMix;
  public final float ringModHz;
  public final float ringModDepth;
  public final float outputTrimDb;

  private VoiceFxPreset(Builder builder) {
    id = builder.id;
    fallbackPitchSemitones = builder.fallbackPitchSemitones;
    targetF0Hz = builder.targetF0Hz;
    minPitchSemitones = builder.minPitchSemitones;
    maxPitchSemitones = builder.maxPitchSemitones;
    formantRatio = builder.formantRatio;
    eq = builder.eq;
    breathAmount = builder.breathAmount;
    gateThresholdDb = builder.gateThresholdDb;
    gateDepth = builder.gateDepth;
    compressorThresholdDb = builder.compressorThresholdDb;
    compressorRatio = builder.compressorRatio;
    compressorAttackMs = builder.compressorAttackMs;
    compressorReleaseMs = builder.compressorReleaseMs;
    compressorMakeupDb = builder.compressorMakeupDb;
    reflectionDelaysMs = builder.reflectionDelaysMs;
    reflectionGains = builder.reflectionGains;
    driveDb = builder.driveDb;
    driveMix = builder.driveMix;
    ringModHz = builder.ringModHz;
    ringModDepth = builder.ringModDepth;
    outputTrimDb = builder.outputTrimDb;
  }

  public static final class Builder {
    private final int id;
    private float fallbackPitchSemitones;
    private float targetF0Hz;
    private float minPitchSemitones = -12f;
    private float maxPitchSemitones = 12f;
    private float formantRatio = 1f;
    private Band[] eq = new Band[0];
    private float breathAmount;
    private float gateThresholdDb = -58f;
    private float gateDepth = 0.5f;
    private float compressorThresholdDb = -20f;
    private float compressorRatio = 3f;
    private float compressorAttackMs = 5f;
    private float compressorReleaseMs = 140f;
    private float compressorMakeupDb = 3f;
    private float[] reflectionDelaysMs = new float[0];
    private float[] reflectionGains = new float[0];
    private float driveDb;
    private float driveMix;
    private float ringModHz;
    private float ringModDepth;
    private float outputTrimDb;

    public Builder(int id) {
      this.id = id;
    }

    public Builder pitch(float fallbackSemitones, float targetF0, float min, float max) {
      fallbackPitchSemitones = fallbackSemitones;
      targetF0Hz = targetF0;
      minPitchSemitones = min;
      maxPitchSemitones = max;
      return this;
    }

    public Builder formant(float ratio) {
      formantRatio = ratio;
      return this;
    }

    public Builder eq(Band... bands) {
      eq = bands;
      return this;
    }

    public Builder breath(float amount) {
      breathAmount = amount;
      return this;
    }

    public Builder gate(float thresholdDb, float depth) {
      gateThresholdDb = thresholdDb;
      gateDepth = depth;
      return this;
    }

    public Builder compressor(float thresholdDb, float ratio, float attackMs, float releaseMs,
        float makeupDb) {
      compressorThresholdDb = thresholdDb;
      compressorRatio = ratio;
      compressorAttackMs = attackMs;
      compressorReleaseMs = releaseMs;
      compressorMakeupDb = makeupDb;
      return this;
    }

    public Builder reflections(float[] delaysMs, float[] gains) {
      reflectionDelaysMs = delaysMs;
      reflectionGains = gains;
      return this;
    }

    public Builder saturation(float driveDb, float mix) {
      driveDb = driveDb;
      driveMix = mix;
      return this;
    }

    public Builder ringMod(float freqHz, float depth) {
      ringModHz = freqHz;
      ringModDepth = depth;
      return this;
    }

    public Builder trim(float db) {
      outputTrimDb = db;
      return this;
    }

    public VoiceFxPreset build() {
      return new VoiceFxPreset(this);
    }
  }

  // ---------------------------------------------------------------------------------
  // The voices.

  private static final float[] NO_DELAYS = new float[0];
  private static final float[] NO_GAINS = new float[0];

  /**
   * Girl - a natural adult female voice.
   *
   * <p>Pitch is aimed at 185 Hz rather than shifted by a fixed offset, and the vocal tract is only
   * shortened by 19 %: that combination is what a real female speaker sounds like. The 300 Hz dip
   * removes the male chest weight, +3 dB at 3.2 kHz restores the consonant presence that survives a
   * phone codec, and a little breath noise plus gentle compression removes the "synthetic" edge.
   */
  public static VoiceFxPreset girl() {
    return new Builder(VoiceFxProcessor.PRESET_GIRL)
        .pitch(7.5f, 185f, 4f, 11.5f)
        .formant(1.19f)
        .eq(
            new Band(Biquad.HIGHPASS, 120f, 0.70f, 0f),
            new Band(Biquad.PEAKING, 300f, 1.00f, -3.0f),
            new Band(Biquad.PEAKING, 1300f, 1.10f, 1.0f),
            new Band(Biquad.PEAKING, 3200f, 1.00f, 2.2f),
            new Band(Biquad.HIGH_SHELF, 5500f, 0.70f, 1.5f),
            new Band(Biquad.LOWPASS, 15000f, 0.70f, 0f))
        .breath(0.13f)
        .gate(-58f, 0.55f)
        .compressor(-22f, 3.5f, 6f, 140f, 4f)
        .saturation(2f, 0.10f)
        .trim(0f)
        .build();
  }

  /**
   * Agent - the anonymous "L" voice: a low, flat, deliberately unreadable male voice coming out of
   * a laptop.
   *
   * <p>The character is not a big pitch drop (that just sounds like a demon); it is the combination
   * of a slightly lower register, a slightly longer vocal tract, a strong 900 Hz box resonance, a
   * 2.6 kHz dip that removes the "friendly" part of the spectrum, a hard band limit at both ends
   * (no chest, no air) and heavy compression, which flattens the delivery. Short reflections at 9
   * and 17 ms put it in a small room instead of a studio.
   */
  public static VoiceFxPreset agent() {
    return new Builder(VoiceFxProcessor.PRESET_AGENT)
        .pitch(-3f, 96f, -5.5f, -1f)
        .formant(0.94f)
        .eq(
            new Band(Biquad.HIGHPASS, 160f, 0.70f, 0f),
            new Band(Biquad.PEAKING, 1300f, 1.10f, 2.5f),
            new Band(Biquad.PEAKING, 2200f, 1.20f, 1.5f),
            new Band(Biquad.PEAKING, 3200f, 1.40f, -2.0f),
            new Band(Biquad.LOWPASS, 4800f, 0.70f, 0f),
            new Band(Biquad.HIGH_SHELF, 5000f, 0.70f, -5.0f))
        .breath(0.04f)
        .gate(-56f, 0.6f)
        .compressor(-26f, 4.5f, 3f, 180f, 6f)
        .reflections(new float[] {8f, 15f}, new float[] {0.16f, 0.09f})
        .saturation(3f, 0.14f)
        .trim(0f)
        .build();
  }

  /** Boy - a young adult male: slightly lower and heavier than the speaker's natural voice. */
  public static VoiceFxPreset boy() {
    return new Builder(VoiceFxProcessor.PRESET_BOY)
        .pitch(-2.5f, 100f, -5f, -0.5f)
        .formant(0.96f)
        .eq(
            new Band(Biquad.HIGHPASS, 85f, 0.70f, 0f),
            new Band(Biquad.PEAKING, 190f, 0.90f, 2.0f),
            new Band(Biquad.PEAKING, 3100f, 1.00f, -1.5f),
            new Band(Biquad.HIGH_SHELF, 6000f, 0.70f, -1.0f))
        .breath(0.04f)
        .gate(-58f, 0.5f)
        .compressor(-22f, 3f, 8f, 160f, 3f)
        .saturation(2f, 0.08f)
        .build();
  }

  /** Child - a shorter vocal tract and a much higher register, kept bright but not shrieky. */
  public static VoiceFxPreset child() {
    return new Builder(VoiceFxProcessor.PRESET_CHILD)
        .pitch(9f, 275f, 7f, 14f)
        .formant(1.32f)
        .eq(
            new Band(Biquad.HIGHPASS, 170f, 0.70f, 0f),
            new Band(Biquad.PEAKING, 500f, 1.00f, -2.0f),
            new Band(Biquad.PEAKING, 3600f, 1.00f, 2.5f),
            new Band(Biquad.HIGH_SHELF, 5000f, 0.70f, 2.5f))
        .breath(0.18f)
        .gate(-58f, 0.55f)
        .compressor(-24f, 4f, 5f, 120f, 4f)
        .saturation(2f, 0.10f)
        .build();
  }

  /** Robot - the one preset that is supposed to sound synthetic: ring modulation and grit. */
  public static VoiceFxPreset robot() {
    return new Builder(VoiceFxProcessor.PRESET_ROBOT)
        .pitch(0f, 0f, -2f, 2f)
        .formant(1f)
        .eq(
            new Band(Biquad.HIGHPASS, 180f, 0.70f, 0f),
            new Band(Biquad.PEAKING, 1800f, 1.10f, 2f),
            new Band(Biquad.LOWPASS, 6500f, 0.70f, 0f))
        .breath(0f)
        .gate(-52f, 0.7f)
        .compressor(-24f, 6f, 2f, 80f, 5f)
        .ringMod(58f, 0.55f)
        .saturation(6f, 0.35f)
        .build();
  }

  public static VoiceFxPreset forId(int id) {
    switch (id) {
      case VoiceFxProcessor.PRESET_GIRL:
        return girl();
      case VoiceFxProcessor.PRESET_BOY:
        return boy();
      case VoiceFxProcessor.PRESET_CHILD:
        return child();
      case VoiceFxProcessor.PRESET_AGENT:
        return agent();
      case VoiceFxProcessor.PRESET_ROBOT:
        return robot();
      default:
        return null;
    }
  }

  public float[] reflectionDelays() {
    return reflectionDelaysMs == null ? NO_DELAYS : reflectionDelaysMs;
  }

  public float[] reflectionLevels() {
    return reflectionGains == null ? NO_GAINS : reflectionGains;
  }
}
