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
 * Small in-place radix-2 complex FFT (power-of-two sizes only).
 *
 * <p>Only two instances are alive at a time in the voice engine and every table is
 * pre-computed in the constructor, so the transform itself allocates nothing and is safe
 * to call from the realtime audio thread.
 */
public final class Fft {

  private final int size;
  private final int[] bitReverse;
  private final float[] cosTable;
  private final float[] sinTable;
  private final float[] workRe;
  private final float[] workIm;
  private final float scale;

  public Fft(int size) {
    if (size < 2 || (size & (size - 1)) != 0) {
      throw new IllegalArgumentException("FFT size must be a power of two: " + size);
    }
    this.size = size;
    this.scale = 1f / size;
    this.bitReverse = new int[size];
    int bits = Integer.numberOfTrailingZeros(size);
    for (int i = 0; i < size; i++) {
      bitReverse[i] = Integer.reverse(i) >>> (32 - bits);
    }
    this.cosTable = new float[size / 2];
    this.sinTable = new float[size / 2];
    for (int i = 0; i < size / 2; i++) {
      double angle = -2.0 * Math.PI * i / size;
      cosTable[i] = (float) Math.cos(angle);
      sinTable[i] = (float) Math.sin(angle);
    }
    this.workRe = new float[size];
    this.workIm = new float[size];
  }

  public int size() {
    return size;
  }

  /** Forward transform, in place. */
  public void forward(float[] re, float[] im) {
    transform(re, im, false);
  }

  /** Inverse transform including the {@code 1/N} normalisation, in place. */
  public void inverse(float[] re, float[] im) {
    transform(re, im, true);
    for (int i = 0; i < size; i++) {
      re[i] *= scale;
      im[i] *= scale;
    }
  }

  private void transform(float[] re, float[] im, boolean inverse) {
    for (int i = 0; i < size; i++) {
      int j = bitReverse[i];
      if (j > i) {
        float t = re[i];
        re[i] = re[j];
        re[j] = t;
        t = im[i];
        im[i] = im[j];
        im[j] = t;
      }
    }
    for (int span = 2; span <= size; span <<= 1) {
      int half = span >> 1;
      int step = size / span;
      for (int start = 0; start < size; start += span) {
        int twiddle = 0;
        for (int k = 0; k < half; k++, twiddle += step) {
          float wr = cosTable[twiddle];
          float wi = inverse ? -sinTable[twiddle] : sinTable[twiddle];
          int a = start + k;
          int b = a + half;
          float xr = re[b] * wr - im[b] * wi;
          float xi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - xr;
          im[b] = im[a] - xi;
          re[a] += xr;
          im[a] += xi;
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------
  // Helpers used by the real-input stages.

  /**
   * Real-input forward transform: {@code in[0..size)} is real, the result is written to
   * {@code outRe/outIm} as the full (conjugate symmetric) spectrum.
   */
  public void forwardReal(float[] in, float[] outRe, float[] outIm) {
    System.arraycopy(in, 0, workRe, 0, size);
    java.util.Arrays.fill(workIm, 0f);
    forward(workRe, workIm);
    System.arraycopy(workRe, 0, outRe, 0, size);
    System.arraycopy(workIm, 0, outIm, 0, size);
  }

  /** Inverse of {@link #forwardReal}: takes a full symmetric spectrum back to the time domain. */
  public void inverseReal(float[] inRe, float[] inIm, float[] out) {
    System.arraycopy(inRe, 0, workRe, 0, size);
    System.arraycopy(inIm, 0, workIm, 0, size);
    inverse(workRe, workIm);
    System.arraycopy(workRe, 0, out, 0, size);
  }
}
