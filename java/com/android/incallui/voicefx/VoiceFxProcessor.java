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
import com.android.incallui.voicefx.dsp.Dynamics;
import com.android.incallui.voicefx.dsp.FloatFifo;
import com.android.incallui.voicefx.dsp.PitchFormantShifter;
import com.android.incallui.voicefx.dsp.PitchTracker;
import com.android.incallui.voicefx.dsp.StreamingResampler;

/**
 * Realtime in-call voice effect engine (mono PCM16 in/out).
 *
 * <p>One instance per engine thread; all methods must be called from that thread. Nothing is
 * allocated while streaming.
 *
 * <pre>
 *   PCM16 in
 *     -&gt; DC block + rumble high pass + gentle gate          (clean, stable input)
 *     -&gt; anti-alias low pass (tracks the pitch ratio)        (no aliasing when resampling up)
 *     -&gt; pitch tracker -&gt; target register -&gt; pitch ratio    (adaptive, per speaker)
 *     -&gt; STFT pitch/formant shifter                          (independent pitch and formants)
 *     -&gt; cubic resampler                                     (turns the stretch into a pitch shift)
 *     -&gt; breath / air noise                                  (female voices are not perfectly clean)
 *     -&gt; preset EQ                                           (tilt, presence, band limit)
 *     -&gt; early reflections                                   (small room, "through a laptop")
 *     -&gt; saturation                                          (analog-ish edge)
 *     -&gt; gate + compressor + level match + limiter           (sits in the call like a real voice)
 *     -&gt; PCM16 out
 * </pre>
 *
 * <p>The old chain (granular shifter + ring modulator + slap echo) is gone on purpose: the granular
 * shifter moved the formants together with the pitch, which is precisely what makes a converted
 * voice sound like a cartoon instead of a person.
 */
public final class VoiceFxProcessor {

  public static final int PRESET_NONE = 0;
  public static final int PRESET_GIRL = 1;
  public static final int PRESET_BOY = 2;
  public static final int PRESET_ROBOT = 3;
  public static final int PRESET_CHILD = 4;
  public static final int PRESET_AGENT = 5; // Death Note "L"-style anonymous voice

  private static final double TWO_PI = 2.0 * Math.PI;

  /** Pitch glide speed while the target register is being reached, in semitones per block. */
  private static final float PITCH_RAMP_SEMITONES_PER_BLOCK = 0.5f;

  private static final int MAX_EQ_BANDS = 8;
  private static final int MAX_REFLECTIONS = 2;

  private final float sampleRate;

  // Input conditioning.
  private final Biquad dcBlock = new Biquad();
  private final Biquad rumbleHighPass = new Biquad();
  private final Biquad antiAlias1 = new Biquad();
  private final Biquad antiAlias2 = new Biquad();
  private final Dynamics.Gate inputGate = new Dynamics.Gate();

  // Pitch / formant engine.
  private final PitchTracker tracker = new PitchTracker();
  private final PitchFormantShifter shifter = new PitchFormantShifter();
  private final FloatFifo stretched = new FloatFifo(16384);
  private final StreamingResampler resampler = new StreamingResampler();

  // Coloration.
  private final Biquad[] eqBands = new Biquad[MAX_EQ_BANDS];
  private int eqBandCount;
  private final Biquad breathHighPass = new Biquad();
  private final Biquad breathLowPass = new Biquad();
  private float breathAmount;
  private float breathEnvelope;
  private float breathCoefficient;

  private final float[][] reflectionBuffer = new float[MAX_REFLECTIONS][];
  private final float[] reflectionGain = new float[MAX_REFLECTIONS];
  private final int[] reflectionLength = new int[MAX_REFLECTIONS];
  private final int[] reflectionPosition = new int[MAX_REFLECTIONS];
  private int reflectionCount;

  private float driveGain;
  private float driveNormalisation = 1f;
  private float driveMix;

  private double ringPhase;
  private double ringIncrement;
  private float ringDepth;

  // Dynamics.
  private final Dynamics.Compressor compressor = new Dynamics.Compressor();
  private final Dynamics.LevelMatcher levelMatcher = new Dynamics.LevelMatcher();
  private final Dynamics.Limiter limiter = new Dynamics.Limiter();
  private float outputTrim = 1f;

  // Pitch targeting.
  private float targetF0Hz;
  private float minPitchSemitones;
  private float maxPitchSemitones;
  private float fallbackPitchSemitones;
  private float currentPitchSemitones;
  private float appliedPitchSemitones;
  private float smoothedDesiredShift;
  private float lockedShift;
  private boolean pitchLocked;
  private long samplesStreamed;
  private int unstableBlocks;
  /** Starting offset remembered from an earlier call, NaN when there is none. */
  private float pitchHintSemitones = Float.NaN;
  /**
   * Offset the caller remembered from an earlier call, or NaN. While it is set, the live pitch
   * estimate is ignored for the first few seconds: it is already known where the speaker sits, and
   * a fresh estimate is at its noisiest exactly then.
   */
  private float seedPitchSemitones = Float.NaN;

  // Preset state.
  private int preset = PRESET_NONE;
  private int pendingPreset = PRESET_NONE;
  private boolean configured;
  private VoiceFxPreset activePreset;

  // Click-free switching.
  private static final int FADE_SAMPLES = 256; // ~5 ms at 48 kHz
  private float fadeGain;
  private int fadeCounter;
  private boolean fadingOut;

  private float[] input = new float[0];
  private float[] output = new float[0];
  private final float[] primeBuffer = new float[1024];
  private float rngState = 0x9E3779B9;

  public VoiceFxProcessor(float sampleRate) {
    this.sampleRate = sampleRate;
    dcBlock.set(Biquad.HIGHPASS, sampleRate, 24f, 0.70f, 0f);
    rumbleHighPass.set(Biquad.HIGHPASS, sampleRate, 70f, 0.70f, 0f);
    breathHighPass.set(Biquad.HIGHPASS, sampleRate, 2400f, 0.70f, 0f);
    breathLowPass.set(Biquad.LOWPASS, sampleRate, 7500f, 0.70f, 0f);
    breathCoefficient = (float) (1.0 - Math.exp(-1.0 / (0.030 * sampleRate)));
    for (int i = 0; i < MAX_EQ_BANDS; i++) {
      eqBands[i] = new Biquad();
    }
    limiter.configure(sampleRate, -1.0f, 60f);
    levelMatcher.configure(sampleRate, 1800f, 7f, -8f);
    setAntiAliasCutoff(18000f);
  }

  // ---------------------------------------------------------------------------------
  // Public API.

  /** Selects a voice. Switching is crossfaded so it never clicks. */
  public synchronized void setPreset(int newPreset) {
    if (configured && newPreset == pendingPreset && !fadingOut && fadeCounter == 0) {
      return;
    }
    pendingPreset = newPreset;
    if (!configured) {
      applyPreset(newPreset);
      configured = true;
      fadeGain = 0f;
      fadeCounter = FADE_SAMPLES;
      fadingOut = false;
    } else {
      // Fade out first, swap every parameter while the output is silent, fade back in.
      fadeCounter = FADE_SAMPLES;
      fadingOut = true;
    }
  }

  /**
   * Seeds the pitch offset with the value learned during an earlier call for the same voice.
   * Without it every call would spend its first seconds gliding to the right register.
   */
  public synchronized void setPitchHint(float semitones) {
    pitchHintSemitones = semitones;
  }

  public int getPreset() {
    return preset;
  }

  /** Calibrated pitch offset in semitones, or NaN when the speaker has not been measured yet. */
  public float getLearnedPitchSemitones() {
    if (pitchLocked) {
      return lockedShift;
    }
    return tracker.hasEstimate() ? currentPitchSemitones : Float.NaN;
  }

  public float getDetectedF0Hz() {
    return tracker.getMedianF0();
  }

  public float getAppliedPitchSemitones() {
    return appliedPitchSemitones;
  }

  /** Diagnostic: output samples the resampler had to hold because the FIFO ran dry. */
  public long getStarvedSamples() {
    return resampler.starvedSamples;
  }

  public float getFormantRatio() {
    return activePreset == null ? 1f : activePreset.formantRatio;
  }

  /** In-place processing of {@code frames} mono PCM16 samples. */
  public synchronized void process(short[] pcm, int frames) {
    if (frames <= 0) {
      return;
    }
    if (!configured) {
      return; // never started: the caller should not be running the engine at all
    }
    if (input.length < frames) {
      input = new float[frames];
      output = new float[frames];
    }

    // ---- 1. Input conditioning ---------------------------------------------------
    for (int i = 0; i < frames; i++) {
      float x = pcm[i] * (1f / 32768f);
      x = dcBlock.tick(x);
      x = rumbleHighPass.tick(x);
      x = antiAlias2.tick(antiAlias1.tick(x));
      x = inputGate.tick(x);
      input[i] = x;
    }

    // ---- 2. Pitch target ---------------------------------------------------------
    if (preset != PRESET_NONE) {
      tracker.push(input, 0, frames);
      updatePitchTarget(frames);
    }

    // ---- 3. Pitch + formants -----------------------------------------------------
    final double effectiveStretch = shifter.getEffectiveStretch();
    shifter.process(input, 0, frames, stretched);
    resampler.pull(stretched, effectiveStretch, output, 0, frames);

    // ---- 4. Coloration and dynamics ---------------------------------------------
    final float blockStretch = (float) effectiveStretch;
    for (int i = 0; i < frames; i++) {
      float x = output[i];

      // Breath: airy noise gated by how loud the voice currently is.
      if (breathAmount > 0.0001f) {
        float magnitude = Math.abs(x);
        breathEnvelope += (magnitude - breathEnvelope) * breathCoefficient;
        float noise = nextNoise();
        noise = breathLowPass.tick(breathHighPass.tick(noise));
        x += noise * breathEnvelope * breathAmount;
      }

      for (int b = 0; b < eqBandCount; b++) {
        x = eqBands[b].tick(x);
      }

      for (int r = 0; r < reflectionCount; r++) {
        int length = reflectionLength[r];
        if (length <= 0) {
          continue;
        }
        int position = reflectionPosition[r];
        float delayed = reflectionBuffer[r][position];
        reflectionBuffer[r][position] = x;
        reflectionPosition[r] = (position + 1) % length;
        x += delayed * reflectionGain[r];
      }

      if (driveGain > 0f) {
        float driven = (float) Math.tanh(driveGain * x) * driveNormalisation;
        x = x * (1f - driveMix) + driven * driveMix;
      }

      if (ringDepth > 0.0001f) {
        ringPhase += ringIncrement;
        if (ringPhase >= TWO_PI) {
          ringPhase -= TWO_PI;
        }
        float modulated = x * (float) Math.sin(ringPhase);
        x = x * (1f - ringDepth) + modulated * ringDepth;
      }

      x = compressor.tick(x);
      x = levelMatcher.tick(input[i], x);
      x = x * outputTrim;
      x = limiter.tick(x);

      if (preset != PRESET_NONE) {
        x *= nextFadeGain();
      } else {
        x = 0f;
      }

      output[i] = x;
      int value = (int) Math.round(x * 32767f);
      if (value > 32767) {
        value = 32767;
      } else if (value < -32768) {
        value = -32768;
      }
      pcm[i] = (short) value;
    }
    appliedPitchSemitones = currentPitchSemitones;
    // Re-tune the anti-alias filter (and therefore the shifter) for the next block.
    updateAntiAlias(blockStretch);
  }

  // ---------------------------------------------------------------------------------
  // Pitch targeting.

  private void updatePitchTarget(int frames) {
    samplesStreamed += frames;
    float desired = fallbackPitchSemitones;
    final boolean trustEstimate = Float.isNaN(seedPitchSemitones)
        || samplesStreamed > 5L * (long) sampleRate;
    if (targetF0Hz > 0f && tracker.hasEstimate() && trustEstimate) {
      float median = tracker.getMedianF0();
      if (median > 0f) {
        float semitones = (float) (12.0 * Math.log(targetF0Hz / median) / Math.log(2.0));
        if (semitones < minPitchSemitones) {
          semitones = minPitchSemitones;
        } else if (semitones > maxPitchSemitones) {
          semitones = maxPitchSemitones;
        }
        desired = semitones;
      }
    }
    // The estimate still wobbles by a few percent while it settles; smoothing it is what stops
    // the converted voice from bending back and forth by half a semitone.
    smoothedDesiredShift += (desired - smoothedDesiredShift) * 0.06f;

    float limit = PITCH_RAMP_SEMITONES_PER_BLOCK * (frames / (sampleRate * 0.01f));
    if (limit < PITCH_RAMP_SEMITONES_PER_BLOCK) {
      limit = PITCH_RAMP_SEMITONES_PER_BLOCK;
    }
    if (!pitchLocked) {
      float delta = smoothedDesiredShift - currentPitchSemitones;
      if (delta > limit) {
        delta = limit;
      } else if (delta < -limit) {
        delta = -limit;
      }
      currentPitchSemitones += delta;
      // Once the estimate has settled (4 s of speech) the offset is frozen: a fixed register for
      // the rest of the call is exactly what a real speaker does, and it removes any residual drift.
      if (samplesStreamed > 4L * (long) sampleRate
          && Math.abs(smoothedDesiredShift - currentPitchSemitones) < 0.15f
          && tracker.hasEstimate()) {
        pitchLocked = true;
        lockedShift = currentPitchSemitones;
      }
    } else if (Math.abs(smoothedDesiredShift - lockedShift) > 1.2f) {
      // Either a different speaker or a bad calibration: adapt again.
      unstableBlocks++;
      if (unstableBlocks > 300) {
        pitchLocked = false;
        unstableBlocks = 0;
      }
    } else {
      unstableBlocks = 0;
      lockedShift += (smoothedDesiredShift - lockedShift) * 0.01f;
      currentPitchSemitones = lockedShift;
    }

    shifter.setStretch(Math.pow(2.0, currentPitchSemitones / 12.0));
    shifter.setFormantRatio(activePreset == null ? 1f : activePreset.formantRatio);
  }

  private void updateAntiAlias(float stretch) {
    float cutoff = 0.42f * sampleRate / Math.max(0.4f, stretch);
    if (cutoff > 19000f) {
      cutoff = 19000f;
    }
    if (Math.abs(cutoff - lastAntiAliasCutoff) > 250f) {
      setAntiAliasCutoff(cutoff);
    }
  }

  private float lastAntiAliasCutoff = -1f;

  private void setAntiAliasCutoff(float cutoff) {
    antiAlias1.set(Biquad.LOWPASS, sampleRate, cutoff, 0.541f, 0f);
    antiAlias2.set(Biquad.LOWPASS, sampleRate, cutoff, 1.306f, 0f);
    lastAntiAliasCutoff = cutoff;
  }

  // ---------------------------------------------------------------------------------
  // Preset plumbing.

  private void applyPreset(int id) {
    pendingPreset = id;
    preset = id;
    VoiceFxPreset next = VoiceFxPreset.forId(id);
    activePreset = next;
    if (next == null) {
      resetDspState();
      return;
    }
    fallbackPitchSemitones = next.fallbackPitchSemitones;
    seedPitchSemitones = Float.NaN;
    if (!Float.isNaN(pitchHintSemitones)
        && pitchHintSemitones >= next.minPitchSemitones
        && pitchHintSemitones <= next.maxPitchSemitones) {
      fallbackPitchSemitones = pitchHintSemitones;
      seedPitchSemitones = pitchHintSemitones;
    }
    targetF0Hz = next.targetF0Hz;
    minPitchSemitones = next.minPitchSemitones;
    maxPitchSemitones = next.maxPitchSemitones;
    // Start from the offset the caller remembered (or the preset's own guess) so the first block is
    // already in the right register instead of gliding there over the first second.
    currentPitchSemitones = fallbackPitchSemitones;
    smoothedDesiredShift = fallbackPitchSemitones;
    pitchHintSemitones = Float.NaN;
    pitchLocked = false;
    unstableBlocks = 0;
    samplesStreamed = 0;
    shifter.setStretch(Math.pow(2.0, currentPitchSemitones / 12.0));
    shifter.setFormantRatio(next.formantRatio);

    inputGate.configure(sampleRate, next.gateThresholdDb, 4f, 90f);
    inputGate.setDepth(next.gateDepth);

    eqBandCount = 0;
    for (int i = 0; i < next.eq.length && eqBandCount < MAX_EQ_BANDS; i++) {
      VoiceFxPreset.Band band = next.eq[i];
      eqBands[eqBandCount++].set(band.type, sampleRate, band.freqHz, band.q, band.gainDb);
    }

    breathAmount = next.breathAmount;
    breathEnvelope = 0f;

    reflectionCount = 0;
    float[] delays = next.reflectionDelays();
    float[] gains = next.reflectionLevels();
    for (int i = 0; i < delays.length && reflectionCount < MAX_REFLECTIONS; i++) {
      int length = Math.max(1, (int) (sampleRate * delays[i] / 1000f));
      if (reflectionBuffer[reflectionCount] == null
          || reflectionBuffer[reflectionCount].length != length) {
        reflectionBuffer[reflectionCount] = new float[length];
      } else {
        java.util.Arrays.fill(reflectionBuffer[reflectionCount], 0f);
      }
      reflectionLength[reflectionCount] = length;
      reflectionPosition[reflectionCount] = 0;
      reflectionGain[reflectionCount] = gains[i];
      reflectionCount++;
    }

    driveGain = next.driveDb > 0f ? (float) Math.pow(10.0, next.driveDb / 20.0) : 0f;
    driveNormalisation = driveGain > 0f ? 1f / (float) Math.tanh(driveGain) : 1f;
    driveMix = next.driveMix;

    ringDepth = next.ringModDepth;
    ringIncrement = TWO_PI * next.ringModHz / sampleRate;
    ringPhase = 0.0;

    primePipeline();
    compressor.configure(
        sampleRate,
        next.compressorThresholdDb,
        next.compressorRatio,
        next.compressorAttackMs,
        next.compressorReleaseMs,
        next.compressorMakeupDb,
        8f);
    outputTrim = (float) Math.pow(10.0, next.outputTrimDb / 20.0);
  }

  /**
   * Pushes silence through the shifter before any real audio arrives.
   *
   * <p>The shifter emits in bursts (one hop per analysis frame) while the resampler consumes a
   * steady stream, so the FIFO between them needs a little lead. Without this the resampler
   * starves for a few samples every block and repeats the last sample, which is audible as a
   * faint buzz on sustained vowels.
   */
  /**
   * Silence pushed through the shifter before the first real block. It has to be at least one
   * analysis frame plus the overlap that is thrown away, otherwise the shifter produces nothing and
   * the resampler starts starved (a held sample is audible as a buzz on the first vowel).
   */
  private static final int PRIME_SAMPLES = 3072;

  private void primePipeline() {
    java.util.Arrays.fill(primeBuffer, 0f);
    final int primeSamples = PRIME_SAMPLES;
    int done = 0;
    while (done < primeSamples) {
      int count = Math.min(primeBuffer.length, primeSamples - done);
      shifter.process(primeBuffer, 0, count, stretched);
      done += count;
    }
    resampler.reset();
  }

  private void resetDspState() {
    resampler.reset();
    stretched.clear();
    compressor.reset();
    limiter.reset();
    levelMatcher.reset();
    inputGate.reset();
  }

  private float nextFadeGain() {
    if (fadeCounter > 0) {
      if (fadingOut) {
        fadeGain -= 1f / FADE_SAMPLES;
        if (fadeGain <= 0f) {
          fadeGain = 0f;
          fadingOut = false;
          applyPreset(pendingPreset);
          fadeCounter = FADE_SAMPLES;
        }
      } else {
        fadeGain += 1f / FADE_SAMPLES;
        if (fadeGain >= 1f) {
          fadeGain = 1f;
          fadeCounter = 0;
        }
      }
      fadeCounter--;
    }
    return fadeGain;
  }

  private float nextNoise() {
    // xorshift32: cheap, deterministic, good enough for breath noise.
    int x = (int) rngState;
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    rngState = x;
    return x * 4.656613e-10f;
  }
}
