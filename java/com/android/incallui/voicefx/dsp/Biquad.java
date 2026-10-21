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
 * Single RBJ audio-EQ biquad in direct form II transposed.
 *
 * <p>All coefficient math is the standard "Audio EQ Cookbook" set, so the numbers used by the
 * presets read exactly like the dB/Hz/Q values a mixing engineer would dial in.
 */
public final class Biquad {

  public static final int LOWPASS = 0;
  public static final int HIGHPASS = 1;
  public static final int PEAKING = 2;
  public static final int LOW_SHELF = 3;
  public static final int HIGH_SHELF = 4;
  public static final int BANDPASS = 5;
  public static final int NOTCH = 6;

  private static final double TWO_PI = 2.0 * Math.PI;

  private float b0 = 1f;
  private float b1;
  private float b2;
  private float a1;
  private float a2;
  private float z1;
  private float z2;

  public void set(int type, float sampleRate, float freqHz, float q, float gainDb) {
    double w0 = TWO_PI * freqHz / sampleRate;
    if (w0 > Math.PI * 0.98) {
      w0 = Math.PI * 0.98;
    }
    double cs = Math.cos(w0);
    double sn = Math.sin(w0);
    double alpha = sn / (2.0 * Math.max(0.05f, q));
    double a = Math.pow(10.0, gainDb / 40.0);
    double b0d = 1;
    double b1d = 0;
    double b2d = 0;
    double a0d = 1;
    double a1d = 0;
    double a2d = 0;
    switch (type) {
      case LOWPASS:
        b0d = (1 - cs) / 2;
        b1d = 1 - cs;
        b2d = b0d;
        a0d = 1 + alpha;
        a1d = -2 * cs;
        a2d = 1 - alpha;
        break;
      case HIGHPASS:
        b0d = (1 + cs) / 2;
        b1d = -(1 + cs);
        b2d = b0d;
        a0d = 1 + alpha;
        a1d = -2 * cs;
        a2d = 1 - alpha;
        break;
      case PEAKING:
        b0d = 1 + alpha * a;
        b1d = -2 * cs;
        b2d = 1 - alpha * a;
        a0d = 1 + alpha / a;
        a1d = -2 * cs;
        a2d = 1 - alpha / a;
        break;
      case LOW_SHELF:
        b0d = a * ((a + 1) - (a - 1) * cs + 2 * Math.sqrt(a) * alpha);
        b1d = 2 * a * ((a - 1) - (a + 1) * cs);
        b2d = a * ((a + 1) - (a - 1) * cs - 2 * Math.sqrt(a) * alpha);
        a0d = (a + 1) + (a - 1) * cs + 2 * Math.sqrt(a) * alpha;
        a1d = -2 * ((a - 1) + (a + 1) * cs);
        a2d = (a + 1) + (a - 1) * cs - 2 * Math.sqrt(a) * alpha;
        break;
      case HIGH_SHELF:
        b0d = a * ((a + 1) + (a - 1) * cs + 2 * Math.sqrt(a) * alpha);
        b1d = -2 * a * ((a - 1) + (a + 1) * cs);
        b2d = a * ((a + 1) + (a - 1) * cs - 2 * Math.sqrt(a) * alpha);
        a0d = (a + 1) - (a - 1) * cs + 2 * Math.sqrt(a) * alpha;
        a1d = 2 * ((a - 1) - (a + 1) * cs);
        a2d = (a + 1) - (a - 1) * cs - 2 * Math.sqrt(a) * alpha;
        break;
      case BANDPASS:
        b0d = alpha;
        b1d = 0;
        b2d = -alpha;
        a0d = 1 + alpha;
        a1d = -2 * cs;
        a2d = 1 - alpha;
        break;
      case NOTCH:
        b0d = 1;
        b1d = -2 * cs;
        b2d = 1;
        a0d = 1 + alpha;
        a1d = -2 * cs;
        a2d = 1 - alpha;
        break;
      default:
        break;
    }
    b0 = (float) (b0d / a0d);
    b1 = (float) (b1d / a0d);
    b2 = (float) (b2d / a0d);
    a1 = (float) (a1d / a0d);
    a2 = (float) (a2d / a0d);
  }

  public void reset() {
    z1 = 0f;
    z2 = 0f;
  }

  public float tick(float x) {
    float y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
  }
}
