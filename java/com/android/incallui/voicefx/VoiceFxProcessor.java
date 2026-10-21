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
 * Realtime in-call voice effect engine (mono, PCM16 in/out) — v2 (TD-PSOLA).
 *
 * <p>v1 used a modulated-delay granular shifter, which moves pitch and
 * formants together: at +4.5 st the "Girl" preset was a pitched-up male
 * voice (too little pitch for a woman, too much formant shift). v2 replaces
 * the core with a streaming TD-PSOLA engine (Time-Domain Pitch-Synchronous
 * OverLap-Add) that controls the two independently — the minimum requirement
 * for a realistic gender conversion:
 *
 * <pre>
 *   DC-block HPF 80 Hz -> noise gate -> F0 tracker (NSDF autocorrelation on a
 *   x3-decimated stream, 10 ms cadence) -> TD-PSOLA (pitch marks -> 2-period
 *   Hann grains re-spaced by the pitch ratio, each grain resampled by the
 *   formant ratio; optional multi-layer mix and F0 monotonizing) -> RMS
 *   makeup -> biquad EQ -> breath -> ring mod -> tanh drive -> slap echo ->
 *   compressor -> soft-clip limiter.
 * </pre>
 *
 * <p>GIRL (natural adult woman from an adult-male source) adaptively targets
 * a ~196 Hz median F0 with the ratio clamped to x1.35..x1.85, formants at
 * x1.17 (shorter vocal tract), slightly widened intonation, chest cut,
 * presence lift and a touch of breath. AGENT (Death Note "L" laptop voice)
 * is three PSOLA layers of the same voice (-7 st / -2 st / +5 st) over a
 * monotonized, compressed, 250-3800 Hz band-limited body — layered detuned
 * copies are how that effect is actually built, not a single deep pitch.
 *
 * <p>Budget-device budget: no allocation in {@link #process}, circular
 * buffers only, F0 correlation at 16 kHz (~10 MMAC/s), grain synthesis a few
 * MFLOPS. Added latency ≈ 35 ms look-ahead + one pitch period.
 *
 * <p>The parameter-mirrored Python reference in {@code tools/voicefx/}
 * ({@code psola_lab.py}) renders these exact presets offline for audition
 * and tuning. One instance per engine thread; all methods must be called
 * from that thread.
 */
public final class VoiceFxProcessor {

  public static final int PRESET_NONE = 0;
  public static final int PRESET_GIRL = 1;
  public static final int PRESET_BOY = 2;
  public static final int PRESET_ROBOT = 3;
  public static final int PRESET_CHILD = 4;
  public static final int PRESET_AGENT = 5; // Death Note "L"-style anonymous voice

  // ---------------------------------------------------------------- tuning --
  // GIRL — natural adult woman. Raise target Hz for a younger read (205-215),
  // lower it (180-188) if it sounds strained on a deep source voice. Formant
  // 1.17 ~= adult female vocal tract; 1.10 = subtle, 1.25 = young/cute.
  private static final float GIRL_TARGET_F0_HZ = 196f;
  private static final float GIRL_RATIO_MIN = 1.35f;
  private static final float GIRL_RATIO_MAX = 1.85f;
  private static final float GIRL_FORMANT = 1.17f;
  private static final float GIRL_INTONATION = 1.10f; // slightly wider melody

  // AGENT — layered copies (pitch ratio, formant, gain). No unshifted dry
  // voice on purpose: identity must not survive. Intonation 0.35 keeps only
  // 35% of natural F0 movement (deadpan). Raise the +5 st layer gain for a
  // hollower/more electronic read, lower it for clearer speech.
  private static final float[] AGENT_RATIO = {0.67f, 0.89f, 1.33f};
  private static final float[] AGENT_FORMANT = {0.90f, 0.97f, 1.05f};
  private static final float[] AGENT_GAIN = {0.85f, 0.55f, 0.45f};
  private static final float AGENT_INTONATION = 0.35f;

  private static final double TWO_PI = 2.0 * Math.PI;
  private static final float F0_MIN_HZ = 65f;
  private static final float F0_MAX_HZ = 340f;
  private static final int MAX_LAYERS = 3;
  private static final int MAX_EQ = 4;

  // Pitch-control modes.
  private static final int MODE_FIXED = 0;     // constant ratio (Boy)
  private static final int MODE_ADAPTIVE = 1;  // aim median F0 at a target (Girl/Child/Robot)
  private static final int MODE_LAYERS = 2;    // monotonize + per-layer ratios (Agent)

  // ------------------------------------------------------------- RBJ biquad --
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
        case PEAK: // v1 left this unimplemented (silent bypass); v2 needs it
          b0d = 1 + alpha * A; b1d = -2 * cs; b2d = 1 - alpha * A;
          a0d = 1 + alpha / A; a1d = -2 * cs; a2d = 1 - alpha / A;
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

    float tick(float x) {
      float y = b0 * x + z1;
      z1 = b1 * x - a1 * y + z2;
      z2 = b2 * x - a2 * y;
      return y;
    }
  }

  // -------------------------------------------------------------- F0 tracker --
  /**
   * NSDF autocorrelation pitch tracker on a x3-decimated stream (16 kHz at
   * 48 kHz in), one estimate per 10 ms block: 30 ms window, 65-340 Hz lag
   * range, center clipping, first-strong-peak picking (octave-down
   * preference), parabolic refinement. Also tracks a slow log-domain median
   * of voiced F0 for the adaptive presets.
   */
  private final class F0Tracker {
    private static final int DECIM = 3;
    private final int fsd;         // decimated rate
    private final int win;         // 30 ms @ decimated rate
    private final int lagLo;
    private final int lagHi;
    private final float[] dec = new float[1024];
    private final float[] frame;   // clipped frame scratch (win + lagHi)
    private final float[] sqPrefix;
    private final float[] nsdf;
    private long decAbs;
    private float lp;
    private int phase;
    float f0 = 120f;
    float periodFs;
    boolean voiced;
    double logMedian = Math.log(120.0);

    F0Tracker(float sampleRate) {
      fsd = (int) (sampleRate / DECIM);
      win = (int) (0.030f * fsd);
      lagLo = Math.max(2, (int) (fsd / F0_MAX_HZ));
      lagHi = (int) (fsd / F0_MIN_HZ);
      frame = new float[win + lagHi + 2];
      sqPrefix = new float[win + lagHi + 3];
      nsdf = new float[lagHi + 2];
      periodFs = sampleRate / 120f;
    }

    void reset() {
      decAbs = 0;
      lp = 0f;
      phase = 0;
      f0 = 120f;
      voiced = false;
      logMedian = Math.log(120.0);
      for (int i = 0; i < dec.length; i++) dec[i] = 0f;
    }

    float medianF0() {
      return (float) Math.exp(logMedian);
    }

    /** Feed one gated block; updates f0/voiced/periodFs/median. */
    void push(float[] x, int n) {
      final float a = 0.117f; // ~900 Hz one-pole @48k (decimation filter)
      for (int i = 0; i < n; i++) {
        lp += a * (x[i] - lp);
        if (++phase == DECIM) {
          phase = 0;
          dec[(int) (decAbs & 1023)] = lp;
          decAbs++;
        }
      }
      estimate();
    }

    private float decAt(long abs) {
      return dec[(int) (abs & 1023)];
    }

    private void estimate() {
      int need = win + lagHi;
      if (decAbs < need) {
        voiced = false;
        return;
      }
      long start = decAbs - need;
      // Whole-frame mean removal + center clipping into scratch. (Per-lag
      // means are unnecessary: the input is already DC-blocked upstream.)
      double mean = 0;
      for (int i = 0; i < need; i++) mean += decAt(start + i);
      mean /= need;
      double sq = 0;
      float peakAbs = 0f;
      for (int i = 0; i < win; i++) {
        float v = decAt(start + i) - (float) mean;
        sq += (double) v * v;
        peakAbs = Math.max(peakAbs, Math.abs(v));
        frame[i] = v;
      }
      if (Math.sqrt(sq / win) < 0.004) {
        voiced = false;
        return;
      }
      float clip = 0.3f * peakAbs;
      for (int i = 0; i < need; i++) {
        float v = (i < win) ? frame[i] : decAt(start + i) - (float) mean;
        float av = Math.abs(v) - clip;
        frame[i] = av <= 0f ? 0f : Math.copySign(av, v);
      }
      sqPrefix[0] = 0f;
      for (int i = 0; i < need; i++) sqPrefix[i + 1] = sqPrefix[i] + frame[i] * frame[i];
      float refE = sqPrefix[win];

      int bestLag = lagLo;
      float best = -2f;
      for (int lag = lagLo; lag <= lagHi; lag++) {
        double dot = 0;
        for (int i = 0; i < win; i++) dot += (double) frame[i] * frame[i + lag];
        float segE = sqPrefix[lag + win] - sqPrefix[lag];
        float v = (float) (2.0 * dot / (refE + segE + 1e-12));
        nsdf[lag] = v;
        if (v > best) {
          best = v;
          bestLag = lag;
        }
      }
      if (best < 0.42f) {
        voiced = false;
        return;
      }
      int chosen = bestLag;
      float thresh = 0.9f * best;
      for (int lag = lagLo + 1; lag < bestLag; lag++) {
        if (nsdf[lag] >= nsdf[lag - 1] && nsdf[lag] >= nsdf[lag + 1] && nsdf[lag] >= thresh) {
          chosen = lag;
          break;
        }
      }
      float lagF = chosen;
      if (chosen > 0 && chosen < nsdf.length - 1) {
        float y0 = nsdf[chosen - 1], y1 = nsdf[chosen], y2 = nsdf[chosen + 1];
        float den = y0 - 2f * y1 + y2;
        if (Math.abs(den) > 1e-12f) lagF += 0.5f * (y0 - y2) / den;
      }
      float est = fsd / Math.max(lagF, 1f);
      f0 = Math.min(Math.max(est, F0_MIN_HZ), F0_MAX_HZ);
      periodFs = fs / f0;
      voiced = true;
      logMedian += 0.03 * (Math.log(f0) - logMedian);
    }
  }

  // ----------------------------------------------------------------- PSOLA --
  /**
   * Streaming multi-layer TD-PSOLA. Analysis epochs are peak-refined pitch
   * marks. Each layer re-lays 2-period Hann grains (formant-resampled) at
   * spacing P/ratio into a shared weight-normalized overlap-add; the output
   * timeline equals the input timeline (duration preserved). All buffers are
   * circular with absolute sample indexing — no copies, no allocation.
   */
  private final class Psola {
    private static final int BUF = 65536;          // 1.36 s @48k
    private static final int MASK = BUF - 1;
    private static final int EPOCHS = 512;
    private static final int PARAMS = 128;

    private final float[] inBuf = new float[BUF];
    private final float[] acc = new float[BUF];
    private final float[] wgt = new float[BUF];
    private final long[] ePos = new long[EPOCHS];
    private final int[] ePeriod = new int[EPOCHS];
    private final boolean[] eVoiced = new boolean[EPOCHS];
    private final long[] pEnd = new long[PARAMS];
    private final float[] pF0 = new float[PARAMS];
    private final boolean[] pVoiced = new boolean[PARAMS];
    private final float[] pRatio = new float[PARAMS];
    private final float[] hann = new float[2048];
    private final float[] layerRatio = new float[MAX_LAYERS];
    private final float[] layerFormant = new float[MAX_LAYERS];
    private final float[] layerGain = new float[MAX_LAYERS];
    private final double[] nextS = new double[MAX_LAYERS];

    private int numLayers = 1;
    private int lookahead;
    private int unvoicedPeriod;
    private int tail;
    private long writeAbs;
    private long nextA;
    private long emitAbs;
    private float periodSmooth;
    private int eHead;   // oldest live epoch slot
    private int eCount;
    private int pHead;   // next param slot to write
    private int pCount;

    Psola() {
      for (int i = 0; i < hann.length; i++) {
        hann[i] = (float) (0.5 - 0.5 * Math.cos(TWO_PI * i / (hann.length - 1)));
      }
    }

    void configure(float[] ratios, float[] formants, float[] gains, int layers) {
      numLayers = Math.min(layers, MAX_LAYERS);
      for (int i = 0; i < numLayers; i++) {
        layerRatio[i] = ratios[i];
        layerFormant[i] = formants[i];
        layerGain[i] = gains[i];
      }
      lookahead = (int) (0.035f * fs);
      unvoicedPeriod = Math.max(24, (int) (fs / 200f));
      tail = (int) (fs / F0_MIN_HZ) + 1;
      reset();
    }

    void reset() {
      writeAbs = 0;
      nextA = 0;
      emitAbs = 0;
      periodSmooth = fs / 120f;
      eHead = 0;
      eCount = 0;
      pHead = 0;
      pCount = 0;
      for (int i = 0; i < MAX_LAYERS; i++) nextS[i] = 0.0;
      for (int i = 0; i < BUF; i++) {
        inBuf[i] = 0f;
        acc[i] = 0f;
        wgt[i] = 0f;
      }
    }

    void noteBlock(long absEnd, float f0, boolean voiced, float ratio) {
      pEnd[pHead] = absEnd;
      pF0[pHead] = f0;
      pVoiced[pHead] = voiced;
      pRatio[pHead] = ratio;
      pHead = (pHead + 1) & (PARAMS - 1);
      if (pCount < PARAMS) pCount++;
    }

    // Scratch outputs of paramsAt() (avoids allocation in the audio path).
    private float atF0;
    private boolean atVoiced;
    private float atRatio;

    private void paramsAt(double pos) {
      atF0 = 120f;
      atVoiced = false;
      atRatio = 1f;
      if (pCount == 0) return;
      // Newest first: synthesis trails the tracker by only a few blocks.
      for (int k = 0; k < pCount; k++) {
        int slot = (pHead - 1 - k) & (PARAMS - 1);
        if (pos < pEnd[slot]) {
          atF0 = pF0[slot];
          atVoiced = pVoiced[slot];
          atRatio = pRatio[slot];
        } else {
          break; // older entries end even earlier; keep last match
        }
      }
      // If pos is past every entry, fall back to the newest values.
      if (pos >= pEnd[(pHead - 1) & (PARAMS - 1)]) {
        int slot = (pHead - 1) & (PARAMS - 1);
        atF0 = pF0[slot];
        atVoiced = pVoiced[slot];
        atRatio = pRatio[slot];
      }
    }

    private float readCubic(double pos) {
      long i = (long) Math.floor(pos);
      float t = (float) (pos - i);
      float x0 = inBuf[(int) ((i - 1) & MASK)];
      float x1 = inBuf[(int) (i & MASK)];
      float x2 = inBuf[(int) ((i + 1) & MASK)];
      float x3 = inBuf[(int) ((i + 2) & MASK)];
      // Catmull-Rom
      return x1 + 0.5f * t * (x2 - x0 + t * (2f * x0 - 5f * x1 + 4f * x2 - x3
          + t * (3f * (x1 - x2) + x3 - x0)));
    }

    private float hannAt(int i, int len) {
      if (len <= 1) return 1f;
      return hann[(int) ((long) i * (hann.length - 1) / (len - 1))];
    }

    private long refinePeak(long mark, int period) {
      int r = Math.max(4, period / 4);
      long best = mark;
      float bestV = Float.NEGATIVE_INFINITY;
      for (long p = Math.max(0L, mark - r); p <= mark + r; p++) {
        float v = inBuf[(int) (p & MASK)];
        if (v > bestV) {
          bestV = v;
          best = p;
        }
      }
      return best;
    }

    private void pushEpoch(long pos, int period, boolean voiced) {
      if (eCount == EPOCHS) { // drop oldest (should not happen in practice)
        eHead = (eHead + 1) & (EPOCHS - 1);
        eCount--;
      }
      int slot = (eHead + eCount) & (EPOCHS - 1);
      ePos[slot] = pos;
      ePeriod[slot] = period;
      eVoiced[slot] = voiced;
      eCount++;
    }

    private int nearestEpoch(double s) {
      int bestSlot = -1;
      double bestD = Double.MAX_VALUE;
      for (int k = 0; k < eCount; k++) {
        int slot = (eHead + k) & (EPOCHS - 1);
        double d = Math.abs(ePos[slot] - s);
        if (d < bestD) {
          bestD = d;
          bestSlot = slot;
        }
      }
      return bestSlot;
    }

    private void dropOldEpochs() {
      double minS = nextS[0];
      for (int i = 1; i < numLayers; i++) minS = Math.min(minS, nextS[i]);
      long keepFrom = (long) minS - 3L * tail;
      while (eCount > 0 && ePos[eHead] < keepFrom) {
        eHead = (eHead + 1) & (EPOCHS - 1);
        eCount--;
      }
    }

    /** Push n gated samples; emit finalized samples into the outer FIFO. */
    void process(float[] x, int n) {
      for (int i = 0; i < n; i++) {
        inBuf[(int) (writeAbs & MASK)] = x[i];
        writeAbs++;
      }
      long frontier = writeAbs - lookahead;

      // Analysis epochs (pitch marks).
      while (nextA + periodSmooth < frontier) {
        paramsAt(nextA);
        float period;
        boolean voiced = atVoiced;
        if (voiced) {
          period = fs / Math.min(Math.max(atF0, F0_MIN_HZ), F0_MAX_HZ);
          periodSmooth = 0.7f * periodSmooth + 0.3f * period;
          period = periodSmooth;
        } else {
          period = unvoicedPeriod;
        }
        int pInt = Math.max(24, Math.round(period));
        long mark = voiced ? refinePeak(nextA, pInt) : nextA;
        pushEpoch(mark, pInt, voiced);
        nextA = mark + pInt;
      }

      // Synthesis: one grain train per layer over the shared epochs.
      for (int li = 0; li < numLayers; li++) {
        float formant = layerFormant[li];
        float gain = layerGain[li];
        while (nextS[li] < frontier && eCount > 0) {
          int slot = nearestEpoch(nextS[li]);
          if (slot < 0) break;
          long ep = ePos[slot];
          int period = ePeriod[slot];
          boolean voiced = eVoiced[slot];
          if (period < 4) {
            nextS[li] += 24;
            continue;
          }
          int len = 2 * period;
          long center = Math.round(nextS[li]);
          long start = center - period;
          for (int i = 0; i < len; i++) {
            double src = ep + (i - period) * (double) formant;
            float w = hannAt(i, len) * gain;
            int idx = (int) ((start + i) & MASK);
            acc[idx] += readCubic(src) * w;
            wgt[idx] += w;
          }
          paramsAt(nextS[li]);
          double ratio = atRatio * layerRatio[li];
          double step = voiced ? period / Math.max(ratio, 0.05) : period;
          nextS[li] += Math.max(24.0, step);
        }
      }

      // Emit the region no future grain can touch, weight-normalized.
      double minS = nextS[0];
      for (int i = 1; i < numLayers; i++) minS = Math.min(minS, nextS[i]);
      long emitTo = (long) minS - tail;
      while (emitAbs < emitTo) {
        int idx = (int) (emitAbs & MASK);
        float w = wgt[idx];
        float v = w > 1e-4f ? acc[idx] / Math.max(w, 0.25f) : 0f;
        acc[idx] = 0f;
        wgt[idx] = 0f;
        emitAbs++;
        fifoPush(v);
      }
      dropOldEpochs();
    }
  }

  // ----------------------------------------------------------- ring / echo --
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
      phase += inc;
      if (phase >= 1.0) phase -= 1.0;
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
      pos = 0;
      wet = wetMix;
      fb = feedback;
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

  // -------------------------------------------------------------- compressor --
  private static final class Compressor {
    private float ratio = 1f;
    private float thrDb;
    private float ka, kr, env;

    void configure(float fs, float ratioIn, float thrDbIn) {
      ratio = ratioIn;
      thrDb = thrDbIn;
      ka = (float) Math.exp(-1.0 / (fs * 0.004));
      kr = (float) Math.exp(-1.0 / (fs * 0.140));
      env = 0f;
    }

    float tick(float x) {
      if (ratio <= 1f) return x;
      float a = Math.abs(x);
      env = a + (a > env ? ka : kr) * (env - a);
      double levelDb = 20.0 * Math.log10(env + 1e-9);
      double over = levelDb - thrDb;
      if (over <= 0) return x;
      double gainDb = -over * (1.0 - 1.0 / ratio);
      return (float) (x * Math.pow(10.0, gainDb / 20.0));
    }
  }

  // ------------------------------------------------------------------ state --
  private final float fs;
  private final int blockFrames;
  private final Biquad dcBlock = new Biquad();
  private final Biquad[] eq = new Biquad[MAX_EQ];
  private final boolean[] eqOn = new boolean[MAX_EQ];
  private final RingMod ring = new RingMod();
  private final Echo echo = new Echo();
  private final Compressor comp = new Compressor();
  private final F0Tracker tracker;
  private final Psola psola;

  private float[] blockBuf;
  private final float[] fifo = new float[16384];
  private int fifoHead, fifoCount;

  private int preset = PRESET_NONE;
  private int mode = MODE_FIXED;
  private float targetF0, ratioMin = 1f, ratioMax = 1f, baseRatio = 1f;
  private float intonation = 1f;
  private float ratioSmooth = 1f;
  private long absIn;
  private float makeup = 1f;
  private float noiseFloor = 1e-4f;
  private float gateGain = 1f;
  private float breathGain;
  private int breathState = 0x1234567;
  private float breathPrev;
  private float driveGain;
  private int fadeInLeft;

  public VoiceFxProcessor(float sampleRate) {
    fs = sampleRate;
    blockFrames = Math.max(32, Math.round(fs / 100f)); // 10 ms
    blockBuf = new float[blockFrames];
    for (int i = 0; i < MAX_EQ; i++) eq[i] = new Biquad();
    tracker = new F0Tracker(sampleRate);
    psola = new Psola();
    dcBlock.configure(Biquad.HP, fs, 80f, 0.707f, 0f);
    setPreset(PRESET_NONE);
  }

  private void fifoPush(float v) {
    if (fifoCount == fifo.length) { // overflow guard: drop oldest
      fifoHead = (fifoHead + 1) & (fifo.length - 1);
      fifoCount--;
    }
    fifo[(fifoHead + fifoCount) & (fifo.length - 1)] = v;
    fifoCount++;
  }

  private float fifoPop() {
    float v = fifo[fifoHead];
    fifoHead = (fifoHead + 1) & (fifo.length - 1);
    fifoCount--;
    return v;
  }

  private void setEq(int idx, int type, float f0, float q, float db) {
    eq[idx].configure(type, fs, f0, q, db);
    eqOn[idx] = true;
  }

  public synchronized void setPreset(int newPreset) {
    preset = newPreset;
    for (int i = 0; i < MAX_EQ; i++) eqOn[i] = false;
    ring.configure(fs, 0f, 0f);
    echo.configure(fs, 0f, 0f, 0f);
    comp.configure(fs, 1f, 0f);
    driveGain = 0f;
    breathGain = 0f;
    mode = MODE_FIXED;
    baseRatio = 1f;
    targetF0 = 0f;
    intonation = 1f;

    float[] ratios = {1f, 1f, 1f};
    float[] formants = {1f, 1f, 1f};
    float[] gains = {1f, 0f, 0f};
    int layers = 1;

    switch (newPreset) {
      case PRESET_GIRL:
        // Natural adult woman: adaptive median-F0 target + independent formant.
        mode = MODE_ADAPTIVE;
        targetF0 = GIRL_TARGET_F0_HZ;
        ratioMin = GIRL_RATIO_MIN;
        ratioMax = GIRL_RATIO_MAX;
        baseRatio = (float) Math.pow(2.0, 7.5 / 12.0);
        intonation = GIRL_INTONATION;
        formants[0] = GIRL_FORMANT;
        setEq(0, Biquad.HP, 100f, 0.707f, 0f);
        setEq(1, Biquad.LOWSHELF, 300f, 0.707f, -2.5f);   // chest cut
        setEq(2, Biquad.PEAK, 3200f, 0.8f, 1.5f);          // presence
        setEq(3, Biquad.HIGHSHELF, 6500f, 0.707f, 2f);     // brightness
        breathGain = 0.012f;
        comp.configure(fs, 3f, -20f);
        break;
      case PRESET_BOY:
        mode = MODE_FIXED;
        baseRatio = 0.80f; // -3.9 st, formants only -7%: deeper, still human
        formants[0] = 0.93f;
        setEq(0, Biquad.LOWSHELF, 200f, 0.707f, 2f);
        comp.configure(fs, 2f, -20f);
        break;
      case PRESET_ROBOT:
        // Flatten every voiced frame to a fixed 110 Hz, then ring-modulate.
        mode = MODE_ADAPTIVE;
        targetF0 = 110f;
        ratioMin = 0.5f;
        ratioMax = 2f;
        baseRatio = 1f;
        intonation = 0f;
        setEq(0, Biquad.HP, 180f, 0.707f, 0f);
        ring.configure(fs, 45f, 0.8f);
        driveGain = 5f;
        comp.configure(fs, 4f, -22f);
        break;
      case PRESET_CHILD:
        mode = MODE_ADAPTIVE;
        targetF0 = 290f;
        ratioMin = 1.60f;
        ratioMax = 2.40f;
        baseRatio = (float) Math.pow(2.0, 10.0 / 12.0);
        intonation = 1.05f;
        formants[0] = 1.32f;
        setEq(0, Biquad.HP, 140f, 0.707f, 0f);
        setEq(1, Biquad.HIGHSHELF, 5000f, 0.707f, 3f);
        comp.configure(fs, 3f, -20f);
        break;
      case PRESET_AGENT:
        // "L from Death Note": layered detuned copies, deadpan, band-limited.
        mode = MODE_LAYERS;
        intonation = AGENT_INTONATION;
        baseRatio = 1f;
        layers = 3;
        for (int i = 0; i < 3; i++) {
          ratios[i] = AGENT_RATIO[i];
          formants[i] = AGENT_FORMANT[i];
          gains[i] = AGENT_GAIN[i];
        }
        setEq(0, Biquad.HP, 250f, 0.707f, 0f);        // laptop/telephone band
        setEq(1, Biquad.PEAK, 1800f, 0.9f, 2f);       // intelligibility
        setEq(2, Biquad.LP, 3800f, 0.707f, 0f);
        driveGain = 3f;                                // electronic grit (no ring mod)
        echo.configure(fs, 30f, 0.06f, 0.15f);         // faint room slap only
        comp.configure(fs, 6f, -24f);                  // flat, relentless level
        break;
      default:
        break;
    }

    psola.configure(ratios, formants, gains, layers);
    tracker.reset();
    ratioSmooth = baseRatio;
    absIn = 0;
    makeup = 1f;
    noiseFloor = 1e-4f;
    gateGain = 1f;
    breathPrev = 0f;
    fifoHead = 0;
    fifoCount = 0;
    fadeInLeft = (int) (0.015f * fs); // 15 ms de-click ramp after (re)start
  }

  public int getPreset() {
    return preset;
  }

  private float ratioFor(float f0, boolean voiced) {
    float med = tracker.medianF0();
    float r;
    switch (mode) {
      case MODE_ADAPTIVE: {
        if (voiced) {
          double ft = targetF0 * Math.pow(Math.max(f0, 1f) / med, intonation);
          r = (float) (ft / Math.max(f0, 1f));
          r = Math.min(Math.max(r, ratioMin), ratioMax);
        } else {
          r = ratioSmooth;
        }
        break;
      }
      case MODE_LAYERS: {
        // Monotonize around the running median; layer ratios apply on top.
        if (voiced) {
          double ft = med * Math.pow(Math.max(f0, 1f) / med, intonation);
          r = (float) (ft / Math.max(f0, 1f));
        } else {
          r = ratioSmooth;
        }
        break;
      }
      default:
        r = baseRatio;
        break;
    }
    ratioSmooth += 0.25f * (r - ratioSmooth);
    return ratioSmooth;
  }

  private float nextNoise() { // xorshift32 -> [-1, 1)
    int s = breathState;
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    breathState = s;
    return ((s >>> 8) & 0xFFFF) * (1f / 32768f) - 1f;
  }

  /** In-place processing of {@code frames} mono PCM16 samples. */
  public void process(short[] pcm, int frames) {
    if (preset == PRESET_NONE) return; // hard bypass
    int done = 0;
    while (done < frames) {
      int n = Math.min(blockFrames, frames - done);
      processBlock(pcm, done, n);
      done += n;
    }
  }

  private void processBlock(short[] pcm, int off, int n) {
    if (blockBuf.length < n) blockBuf = new float[n];

    double sqIn = 0;
    for (int i = 0; i < n; i++) {
      float v = pcm[off + i] * (1f / 32768f);
      blockBuf[i] = v;
      sqIn += (double) v * v;
    }
    float inRms = (float) Math.sqrt(sqIn / Math.max(n, 1) + 1e-18);

    // DC block, then the noise gate (linear gain ramp de-clicks the step).
    for (int i = 0; i < n; i++) blockBuf[i] = dcBlock.tick(blockBuf[i]);
    noiseFloor = inRms > noiseFloor ? Math.min(noiseFloor * 1.002f, 0.02f) : inRms;
    float thr = Math.max(0.006f, noiseFloor * 2.5f);
    float target = inRms > thr ? 1f : Math.max(0.08f, (inRms / thr) * (inRms / thr));
    float prevGate = gateGain;
    gateGain += (target > gateGain ? 0.5f : 0.08f) * (target - gateGain);
    for (int i = 0; i < n; i++) {
      float g = prevGate + (gateGain - prevGate) * (i + 1) / (float) n;
      blockBuf[i] *= g;
    }

    tracker.push(blockBuf, n);
    float ratio = ratioFor(tracker.f0, tracker.voiced);
    absIn += n;
    psola.noteBlock(absIn, tracker.f0, tracker.voiced, ratio);
    psola.process(blockBuf, n);

    float gDrive = driveGain > 0f ? (float) Math.pow(10.0, driveGain / 20.0) : 0f;
    float norm = gDrive > 0f ? (float) Math.tanh(gDrive) : 0f;

    // Pull exactly n samples (silence while the PSOLA look-ahead primes).
    double sqOut = 0;
    for (int i = 0; i < n; i++) {
      blockBuf[i] = fifoCount > 0 ? fifoPop() : 0f;
      sqOut += (double) blockBuf[i] * blockBuf[i];
    }
    float outRms = (float) Math.sqrt(sqOut / Math.max(n, 1) + 1e-18);
    if (tracker.voiced && outRms > 1e-6f) {
      float want = Math.min(Math.max(inRms / outRms, 0.5f), 2.2f);
      makeup += 0.15f * (want - makeup);
    }

    float peak = 0f;
    for (int i = 0; i < n; i++) {
      float x = blockBuf[i] * makeup;
      for (int e = 0; e < MAX_EQ; e++) {
        if (eqOn[e]) x = eq[e].tick(x);
      }
      if (breathGain > 0f) {
        float noise = nextNoise();
        float hp = noise - breathPrev;
        breathPrev = noise;
        x += hp * Math.abs(x) * breathGain * 1.5f;
      }
      x = ring.tick(x);
      if (gDrive > 0f) x = (float) Math.tanh(gDrive * x) / norm;
      x = echo.tick(x);
      x = comp.tick(x);
      if (fadeInLeft > 0) {
        float total = 0.015f * fs;
        x *= 1f - fadeInLeft / total;
        fadeInLeft--;
      }
      // Cubic soft clip (kept from v1: cheap and glitch-free).
      float y;
      if (x > 1.5f) y = 1f;
      else if (x < -1.5f) y = -1f;
      else y = 1.5f * x - 0.5f * x * x * x;
      peak = Math.max(peak, Math.abs(y));
      pcm[off + i] = (short) Math.round(y * 32767f);
    }

    // Safety net: a DSP fault must never blast garbage into the call. If the
    // block ever goes non-finite, reset the dynamic state and mute it; the
    // next block recovers from a clean slate.
    if (!(peak <= 2f)) {
      for (int i = 0; i < n; i++) pcm[off + i] = 0;
      psola.reset();
      tracker.reset();
      ratioSmooth = baseRatio;
      fifoHead = 0;
      fifoCount = 0;
      fadeInLeft = (int) (0.015f * fs);
    }
  }
}
