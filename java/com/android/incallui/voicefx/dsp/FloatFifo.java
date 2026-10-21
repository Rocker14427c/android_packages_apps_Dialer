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
 * Fixed-capacity single-producer/single-consumer float ring buffer.
 *
 * <p>Used to connect the (frame based, bursty) time-stretch stage to the sample-by-sample
 * resampler. Absolute 64 bit positions are kept so readers can look a few samples ahead or
 * behind without copying.
 */
public final class FloatFifo {

  private final float[] buffer;
  private final int mask;
  private long writePos;
  private long readPos;

  public FloatFifo(int capacity) {
    if (capacity < 8 || (capacity & (capacity - 1)) != 0) {
      throw new IllegalArgumentException("capacity must be a power of two: " + capacity);
    }
    buffer = new float[capacity];
    mask = capacity - 1;
  }

  public void clear() {
    java.util.Arrays.fill(buffer, 0f);
    writePos = 0;
    readPos = 0;
  }

  public int available() {
    long diff = writePos - readPos;
    if (diff < 0) {
      return 0;
    }
    return diff > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) diff;
  }

  public int capacity() {
    return buffer.length;
  }

  public void write(float[] src, int offset, int count) {
    long pos = writePos;
    for (int i = 0; i < count; i++) {
      buffer[(int) (pos & mask)] = src[offset + i];
      pos++;
    }
    writePos = pos;
  }

  public void writeSilence(int count) {
    long pos = writePos;
    for (int i = 0; i < count; i++) {
      buffer[(int) (pos & mask)] = 0f;
      pos++;
    }
    writePos = pos;
  }

  public void consume(int count) {
    if (count > 0) {
      readPos += count;
      if (readPos > writePos) {
        readPos = writePos;
      }
    }
  }

  /** Sample {@code relative} positions after the read head; out-of-range reads return 0. */
  public float peek(int relative) {
    if (relative < 0) {
      return 0f;
    }
    long pos = readPos + relative;
    if (pos >= writePos) {
      return 0f;
    }
    return buffer[(int) (pos & mask)];
  }

  public long readPosition() {
    return readPos;
  }
}
