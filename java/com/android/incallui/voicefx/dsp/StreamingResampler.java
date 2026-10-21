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
 * Fractional-delay reader (Catmull-Rom cubic interpolation) used to turn the time-stretched
 * stream coming out of {@link PitchFormantShifter} back into a fixed-rate stream.
 *
 * <p>Playing a signal stretched by {@code alpha} back {@code alpha} times faster restores the
 * original duration and multiplies every frequency by {@code alpha}: that is the pitch shift.
 * Because the stretch factor is recomputed per frame (it tracks the pitch target), the read
 * step is allowed to change between blocks; the FIFO stays level because the shifter consumes
 * exactly as many input samples as the resampler produces output samples.
 */
public final class StreamingResampler {

  private double position = 1.0;
  private float lastOutput;
  /** Diagnostics: output samples produced while the FIFO was starving (should stay 0). */
  public long starvedSamples;

  public void reset() {
    position = 1.0;
    lastOutput = 0f;
  }

  /**
   * Produces {@code count} samples into {@code out}.
   *
   * <p>If the FIFO ever runs dry (which should not happen once primed, but is possible while the
   * pipeline settles after a preset change) the last output sample is held instead of writing
   * zeros, so the remote party hears a tiny freeze rather than a click.
   */
  public void pull(FloatFifo fifo, double step, float[] out, int offset, int count) {
    if (step <= 0.0) {
      step = 1.0;
    }
    for (int i = 0; i < count; i++) {
      if (fifo.available() < 4) {
        starvedSamples++;
        out[offset + i] = lastOutput;
        continue;
      }
      int index = (int) Math.floor(position);
      float frac = (float) (position - index);
      float xm1 = fifo.peek(index - 1);
      float x0 = fifo.peek(index);
      float x1 = fifo.peek(index + 1);
      float x2 = fifo.peek(index + 2);
      float c1 = 0.5f * (x1 - xm1);
      float c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2;
      float c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1);
      float value = ((c3 * frac + c2) * frac + c1) * frac + x0;
      out[offset + i] = value;
      lastOutput = value;
      position += step;
    }
    int consume = (int) Math.floor(position) - 1;
    if (consume > 0) {
      fifo.consume(consume);
      position -= consume;
    }
    if (position < 1.0) {
      position = 1.0;
    }
  }
}
