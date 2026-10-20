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

/**
 * Realtime in-call voice effect engine (mono, PCM16 in/out).
 *
 * <p>Ported from the host-verified native design. One instance per engine thread;
 * all methods must be called from that thread. Allocates only on reconfigure.
 *
 * <p>Chain: DC-block HPF 80 Hz -> granular pitch shifter -> 2x RBJ biquad EQ ->
 * ring modulator -> tanh overdrive -> slap echo -> soft clip.
 */
public final class VoiceFxProcessor {

  public static final int PRESET_NONE = 0;
  public static final int PRESET_GIRL = 1;
  public static final int PRESET_BOY = 2;
  public static final int PRESET_ROBOT = 3;
  public static final int PRESET_CHILD = 4;
  public static final int PRESET_AGENT = 5; // Death Note "L"-style anonymous voice

  private static final double TWO_PI = 2.0 * Math.PI;

  // RBJ biquad
  private static final class Biquad {
    static final int LP = 0, HP = 1, PEAK = 2, LOWSHELF = 3, HIGHSHELF = 4;
    private float b0 = 1, b1, b2, a1, a2, z1, z2;

    void configure(int type, float fs, float f0, float q, float gainDb) {
      double w0 = TWO_PI * f0 / fs;
      double cs = Math.cos(w0), sn = Math.sin(w0);
      double alpha = sn / (2.0 * q);
      double A = Math.pow(10.0, gainDb / 40.0);
      double b0d = 1, b1d = 0, b2d = 0, a0d = 1, a1d = 0, a2d = 0;
      switch (type) {
        case LP:
          b0d = (1 - cs) / 2; b1d = 1 - cs; b2d = b0d;
          a0d = 1 + alpha; a1d = -2 * cs; a2d = 1 - alpha;
          break;
        case HP:
          b0d = (1 + cs) / 2; b1d = -(1 + cs); b2d = b0d;
          a0d = 1 + alpha; a1d = -2 * cs; a2d = 1 - alpha;
          break;
        case LOWSHELF:
          b0d = A * ((A + 1) - (A - 1) * cs + 2 * Math.sqrt(A) * alpha);
          b1d = 2 * A * ((A - 1) - (A + 1) * cs);
          b2d = A * ((A + 1) - (A - 1) * cs - 2 * Math.sqrt(A) * alpha);
          a0d = (A + 1) + (A - 1) * cs + 2 * Math.sqrt(A) * alpha;
          a1d = -2 * ((A - 1) + (A + 1) * cs);
          a2d = (A + 1) + (A - 1) * cs - 2 * Math.sqrt(A) * alpha;
          break;
        case HIGHSHELF:
          b0d = A * ((A + 1) + (A - 1) * cs + 2 * Math.sqrt(A) * alpha);
          b1d = -2 * A * ((A - 1) + (A + 1) * cs);
          b2d = A * ((A + 1) + (A - 1) * cs - 2 * Math.sqrt(A) * alpha);
          a0d = (A + 1) - (A - 1) * cs + 2 * Math.sqrt(A) * alpha;
          a1d = 2 * ((A - 1) - (A + 1) * cs);
          a2d = (A + 1) - (A - 1) * cs - 2 * Math.sqrt(A) * alpha;
          break;
        default:
          break;
      }
      b0 = (float) (b0d / a0d); b1 = (float) (b1d / a0d); b2 = (float) (b2d / a0d);
      a1 = (float) (a1d / a0d); a2 = (float) (a2d / a0d);
      z1 = z2 = 0f;
    }

    // Direct form II transposed
    float tick(float x) {
      float y = b0 * x + z1;
      z1 = b1 * x - a1 * y + z2;
      z2 = b2 * x - a2 * y;
      return y;
    }
  }

  // Granular pitch shifter (two Hann grains sweeping a modulated delay line)
  private static final class PitchShifter {
    private float[] buf = new float[0];
    private int bufSize, window;
    private long writePos;
    private double phase;
    private float rate = 1f;
    private boolean active;

    void configure(float fs, float semitones) {
      rate = (float) Math.pow(2.f, semitones / 12.f);
      active = Math.abs(rate - 1f) > 1e-4f;
      int win = 512;
      while (win < Math.min((int) (fs / 20f), 2048)) win <<= 1;
      window = win;
      bufSize = win * 4;
      buf = new float[bufSize];
      writePos = 0;
      phase = 0.0;
    }

    private float readInterp(double delay) {
      double pos = (double) writePos - delay;
      long ipos = (long) Math.floor(pos);
      float frac = (float) (pos - (double) ipos);
      float a = buf[(int) (ipos & (bufSize - 1))];
      float b = buf[(int) ((ipos + 1) & (bufSize - 1))];
      return a + frac * (b - a);
    }

    float tick(float x) {
      buf[(int) (writePos & (bufSize - 1))] = x;
      writePos++;
      if (!active) return x;

      double phi = phase, win = window;
      double delayA = win + phi * win;
      double phiB = phi + 0.5; if (phiB >= 1.0) phiB -= 1.0;
      double delayB = win + phiB * win;
      double gainA = 0.5 - 0.5 * Math.cos(TWO_PI * phi);
      double gainB = 0.5 - 0.5 * Math.cos(TWO_PI * phiB);

      float out = (float) (gainA * readInterp(delayA) + gainB * readInterp(delayB));
      phase += (1.0 - rate) / win;
      if (phase < 0.0) phase += 1.0;
      else if (phase >= 1.0) phase -= 1.0;
      return out;
    }
  }

  private static final class RingMod {
    private double phase, inc;
    private float wet = 1f;
    private boolean active;

    void configure(float fs, float freqHz, float wetMix) {
      inc = (double) freqHz / fs;
      wet = wetMix;
      active = wetMix > 1e-3f && freqHz > 0f;
      phase = 0.0;
    }

    float tick(float x) {
      if (!active) return x;
      phase += inc; if (phase >= 1.0) phase -= 1.0;
      float mod = (float) Math.sin(TWO_PI * phase);
      return x * (1f - wet) + x * mod * wet;
    }
  }

  private static final class Echo {
    private float[] buf = new float[1];
    private int pos, len;
    private float wet, fb;
    private boolean active;

    void configure(float fs, float delayMs, float wetMix, float feedback) {
      len = Math.max(1, (int) (fs * delayMs / 1000f));
      buf = new float[len];
      pos = 0; wet = wetMix; fb = feedback;
      active = wetMix > 1e-3f;
    }

    float tick(float x) {
      if (!active) return x;
      float d = buf[pos];
      buf[pos] = x + d * fb;
      pos = (pos + 1) % len;
      return x * (1f - wet) + d * wet;
    }
  }

  private final float fs;
  private final Biquad dcBlock = new Biquad();
  private final PitchShifter pitch = new PitchShifter();
  private final Biquad eq1 = new Biquad();
  private final Biquad eq2 = new Biquad();
  private final RingMod ring = new RingMod();
  private final Echo echo = new Echo();
  private boolean eq1On, eq2On;
  private float driveGain;
  private int preset = PRESET_NONE;

  public VoiceFxProcessor(float sampleRate) {
    fs = sampleRate;
    dcBlock.configure(Biquad.HP, fs, 80f, 0.707f, 0f);
    setPreset(PRESET_NONE);
  }

  public synchronized void setPreset(int newPreset) {
    preset = newPreset;
    float pitchSemi = 0f;
    eq1On = eq2On = false;
    float ringFreq = 0f, ringWet = 0f;
    driveGain = 0f;
    echo.configure(fs, 0f, 0f, 0f);

    switch (newPreset) {
      case PRESET_GIRL:
        pitchSemi = 4.5f;
        eq1.configure(Biquad.LOWSHELF, fs, 250f, 0.707f, -2f); eq1On = true;
        eq2.configure(Biquad.HIGHSHELF, fs, 3000f, 0.707f, 3f); eq2On = true;
        break;
      case PRESET_BOY:
        pitchSemi = -3.5f;
        eq1.configure(Biquad.LOWSHELF, fs, 180f, 0.707f, 2f); eq1On = true;
        break;
      case PRESET_ROBOT:
        eq1.configure(Biquad.HP, fs, 180f, 0.707f, 0f); eq1On = true;
        ringFreq = 40f; ringWet = 1.0f;
        driveGain = 6f;
        break;
      case PRESET_CHILD:
        pitchSemi = 7.5f;
        eq1.configure(Biquad.HP, fs, 160f, 0.707f, 0f); eq1On = true;
        eq2.configure(Biquad.HIGHSHELF, fs, 2400f, 0.707f, 4f); eq2On = true;
        break;
      case PRESET_AGENT:
        // "L from Death Note": low anonymous monotone through a laptop.
        pitchSemi = -6f;
        eq1.configure(Biquad.HP, fs, 300f, 0.707f, 0f); eq1On = true;
        eq2.configure(Biquad.LP, fs, 3400f, 0.707f, 0f); eq2On = true;
        ringFreq = 25f; ringWet = 0.30f;
        driveGain = 5f;
        echo.configure(fs, 85f, 0.18f, 0.35f);
        break;
      default:
        break;
    }
    pitch.configure(fs, pitchSemi);
    ring.configure(fs, ringFreq, ringWet);
  }

  public int getPreset() {
    return preset;
  }

  /** In-place processing of {@code frames} mono PCM16 samples. */
  public void process(short[] pcm, int frames) {
    if (preset == PRESET_NONE) return; // hard bypass
    float g = driveGain > 0f ? (float) Math.pow(10.0, driveGain / 20.0) : 0f;
    float norm = g > 0f ? (float) Math.tanh(g) : 0f;

    for (int i = 0; i < frames; i++) {
      float x = pcm[i] * (1f / 32768f);
      x = dcBlock.tick(x);
      x = pitch.tick(x);
      if (eq1On) x = eq1.tick(x);
      if (eq2On) x = eq2.tick(x);
      x = ring.tick(x);
      if (g > 0f) x = (float) Math.tanh(g * x) / norm;
      x = echo.tick(x);
      // soft clip
      if (x > 1.5f) x = 1f;
      else if (x < -1.5f) x = -1f;
      else x = 1.5f * x - 0.5f * x * x * x;
      pcm[i] = (short) Math.round(x * 32767f);
    }
  }
}
