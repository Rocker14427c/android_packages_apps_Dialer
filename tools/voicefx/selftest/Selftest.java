import com.android.incallui.voicefx.VoiceFxProcessor;
import com.android.incallui.voicefx.dsp.FloatFifo;
import com.android.incallui.voicefx.dsp.PitchFormantShifter;
import com.android.incallui.voicefx.dsp.StreamingResampler;

/**
 * Offline regression suite for the voice engine.
 *
 * <p>Pure Java, no Android, no audio files: it synthesises what it needs and
 * checks the numbers that actually decide whether a preset sounds like a voice:
 * how exactly the pitch is hit, how much of the signal survives as periodic
 * (HNR), whether a pure tone comes through untouched, and whether silence stays
 * silent. Run it before every release; the build refuses to ship if it fails.
 *
 * <pre>java Selftest</pre>
 * exits non-zero on the first broken promise.
 */
public final class Selftest {

  private static final int SR = 48000;

  private static int checks;
  private static int failures;
  private static String group = "";

  public static void main(String[] args) {
    transparency();
    pitchAccuracy();
    distortion();
    silence();
    engineOnVowel();
    hintBehaviour();
    loudInput();
    System.out.println();
    System.out.printf("%d checks, %d failures%n", checks, failures);
    if (failures > 0) {
      System.out.println("SELFTEST FAILED");
      System.exit(1);
    }
    System.out.println("SELFTEST PASSED");
  }

  // -------------------------------------------------------------------------
  // Checks.

  /** A sine must come through the shifter untouched at alpha = 1 (gain + purity). */
  private static void transparency() {
    group = "transparency";
    double[] freqs = {440, 984.375, 1000, 1200, 3000};
    for (double f : freqs) {
      float[] in = sine(f, 1.0, 0.3);
      Result r = shift(in, 1.0, 1.0f);
      double gainDb = 20 * Math.log10(rms(r.out, SR / 2, r.out.length) / rms(in, SR / 2, in.length));
      check(Math.abs(gainDb) < 0.1, String.format("f=%.0f Hz gain %+.2f dB (want 0.00)", f, gainDb));
      check(r.starved == 0, String.format("f=%.0f Hz starved %d samples (want 0)", f, r.starved));
    }
  }

  /** The realised pitch ratio must match the requested one to a few hundredths of a semitone. */
  private static void pitchAccuracy() {
    group = "pitch accuracy";
    double[] alphas = {0.794, 0.877, 1.19, 1.335, 1.5, 2.0};
    for (double alpha : alphas) {
      float[] in = sine(1000.0, 1.2, 0.3);
      Result r = shift(in, alpha, 1.0f);
      double measured = dominantHz(r.out, r.out.length / 3, 1 << 15);
      double expected = 1000.0 * alpha;
      double cents = 1200 * Math.log(measured / expected) / Math.log(2);
      check(Math.abs(cents) < 6.0,
          String.format("alpha %.3f -> %.1f Hz (want %.1f, %+.1f cents)", alpha, measured, expected, cents));
    }
  }

  /** Out-of-band energy: a clean vocoder keeps it below a fraction of a percent. */
  private static void distortion() {
    group = "distortion";
    float[] in = sine(1000.0, 1.2, 0.3);
    Result r = shift(in, 1.0, 1.0f);
    double frac = outOfBandFraction(r.out, r.out.length / 3, 1 << 15, 1000.0, 60.0);
    check(frac < 0.01, String.format("out-of-band energy %.4f (want < 0.0100)", frac));
  }

  /** Silence in, silence out: the engine must never self-oscillate or hiss. */
  private static void silence() {
    group = "silence";
    VoiceFxProcessor p = new VoiceFxProcessor(SR);
    p.setPreset(VoiceFxProcessor.PRESET_GIRL);
    short[] buf = new short[480];
    double peak = 0;
    for (int b = 0; b < 300; b++) {
      java.util.Arrays.fill(buf, (short) 0);
      p.process(buf, buf.length);
      for (short v : buf) {
        peak = Math.max(peak, Math.abs(v) / 32768.0);
      }
    }
    check(peak < 0.001, String.format("silence produced peak %.5f (want < 0.001)", peak));
  }

  /** End to end on a steady synthetic vowel: register, periodicity, level, latency-free pumping. */
  private static void engineOnVowel() {
    group = "engine";
    float[] vowel = vowel(110.0, 6.0);
    double inHnr = hnr(vowel, SR / 2, SR, 110.0);
    System.out.printf("  input vowel: HNR %+.1f dB%n", inHnr);

    // {preset, expected semitones for a 110 Hz input, minimum HNR}
    // Girl 185 Hz -> +9.00, Agent 96 Hz -> -2.36, Boy 100 Hz -> -1.65,
    // Child 275 Hz clamped to 14 st -> +14.00. The child conversion is the most
    // extreme stretch, so its periodicity floor is lower by design.
    Object[][] cases = {
        {VoiceFxProcessor.PRESET_GIRL, 9.00, 15.0},
        {VoiceFxProcessor.PRESET_AGENT, -2.36, 15.0},
        {VoiceFxProcessor.PRESET_CHILD, 14.00, 9.0},
        {VoiceFxProcessor.PRESET_BOY, -1.65, 15.0},
    };
    for (Object[] c : cases) {
      int preset = (Integer) c[0];
      double expectedSemitones = (Double) c[1];
      double minHnr = (Double) c[2];
      VoiceFxProcessor p = new VoiceFxProcessor(SR);
      p.setPreset(preset);
      short[] buf = new short[480];
      float[] out = new float[vowel.length];
      long start = System.nanoTime();
      int pos = 0;
      for (int off = 0; off + buf.length <= vowel.length; off += buf.length) {
        for (int i = 0; i < buf.length; i++) {
          float v = vowel[off + i];
          int s = Math.round(v * 32768f);
          buf[i] = (short) Math.max(-32768, Math.min(32767, s));
        }
        p.process(buf, buf.length);
        for (int i = 0; i < buf.length && pos < out.length; i++) {
          out[pos++] = buf[i] / 32768f;
        }
      }
      double wall = (System.nanoTime() - start) / 1e9;
      double learned = p.getLearnedPitchSemitones();
      double f0 = 110.0 * Math.pow(2, learned / 12.0);
      double hnr = hnr(out, SR / 2, SR, f0);
      double peak = 0;
      for (int i = SR / 2; i < SR / 2 + SR && i < out.length; i++) {
        peak = Math.max(peak, Math.abs(out[i]));
      }
      double level = rms(out, SR / 2, SR / 2 + SR);
      String name = presetName(preset);
      System.out.printf(
          "  %-6s learned %+6.2f st (want %+5.1f)  F0 %6.2f Hz  HNR %+5.1f dB  rms %.3f  "
              + "peak %.3f  starved %d  %.0fx realtime%n",
          name, learned, expectedSemitones, f0, hnr, level, peak, p.getStarvedSamples(),
          (6.0 / Math.max(wall, 1e-6)));
      check(Math.abs(learned - expectedSemitones) < 0.25, String.format(
          "%s register %+.2f st (want %+.2f)", name, learned, expectedSemitones));
      check(hnr > minHnr, String.format(
          "%s periodicity %+.1f dB (want > %.1f — this is the raspy-voice tripwire)",
          name, hnr, minHnr));
      check(peak < 0.99, String.format("%s peak %.3f (want < 0.99)", name, peak));
      check(level > 0.02, String.format("%s level %.3f (want > 0.02)", name, level));
      check(p.getStarvedSamples() == 0,
          String.format("%s starved %d samples", name, p.getStarvedSamples()));
      check(wall < 6.0, String.format("%s %.1fx realtime", name, 6.0 / Math.max(wall, 1e-6)));
    }
  }

  /** A remembered offset must be used from the first block, not glided into. */
  private static void hintBehaviour() {
    group = "pitch hint";
    float[] vowel = vowel(110.0, 3.0);
    VoiceFxProcessor p = new VoiceFxProcessor(SR);
    p.setPitchHint(9.0f);
    p.setPreset(VoiceFxProcessor.PRESET_GIRL);
    short[] buf = new short[480];
    double worst = 0;
    for (int off = 0; off + buf.length <= vowel.length; off += buf.length) {
      for (int i = 0; i < buf.length; i++) {
        int s = Math.round(vowel[off + i] * 32768f);
        buf[i] = (short) Math.max(-32768, Math.min(32767, s));
      }
      p.process(buf, buf.length);
      worst = Math.max(worst, Math.abs(p.getAppliedPitchSemitones() - 9.0));
    }
    check(worst < 0.15, String.format("applied offset drifts up to %.2f st from the hint", worst));
  }

  /** A hot input must be limited, not clipped into a square wave. */
  private static void loudInput() {
    group = "headroom";
    float[] loud = sine(220.0, 2.0, 0.95);
    VoiceFxProcessor p = new VoiceFxProcessor(SR);
    p.setPreset(VoiceFxProcessor.PRESET_GIRL);
    short[] buf = new short[480];
    int clipped = 0;
    for (int off = 0; off + buf.length <= loud.length; off += buf.length) {
      for (int i = 0; i < buf.length; i++) {
        int s = Math.round(loud[off + i] * 32768f);
        buf[i] = (short) Math.max(-32768, Math.min(32767, s));
      }
      p.process(buf, buf.length);
      for (short v : buf) {
        if (Math.abs(v) >= 32766) {
          clipped++;
        }
      }
    }
    check(clipped == 0, clipped + " output samples at full scale (want 0: the limiter holds)");
  }

  // -------------------------------------------------------------------------
  // Signal helpers.

  private static final class Result {
    final float[] out;
    final long starved;

    Result(float[] out, long starved) {
      this.out = out;
      this.starved = starved;
    }
  }

  /** Shifter + resampler only: isolates the pitch/formant path from EQ and dynamics. */
  private static Result shift(float[] in, double alpha, float formant) {
    PitchFormantShifter s = new PitchFormantShifter();
    s.setStretch(alpha);
    s.setFormantRatio(formant);
    FloatFifo fifo = new FloatFifo(16384);
    StreamingResampler rs = new StreamingResampler();
    // Prime exactly like VoiceFxProcessor does, otherwise the resampler starts
    // starved and the first milliseconds repeat a held sample.
    float[] silence = new float[3072];
    s.process(silence, 0, silence.length, fifo);
    rs.reset();
    float[] out = new float[in.length];
    float[] block = new float[480];
    int pos = 0;
    for (int off = 0; off + 480 <= in.length && pos < out.length; off += 480) {
      System.arraycopy(in, off, block, 0, 480);
      s.process(block, 0, 480, fifo);
      rs.pull(fifo, s.getEffectiveStretch(), out, pos, Math.min(480, out.length - pos));
      pos += 480;
    }
    return new Result(out, rs.starvedSamples);
  }

  private static float[] sine(double hz, double seconds, double amplitude) {
    int n = (int) (SR * seconds);
    float[] x = new float[n];
    for (int i = 0; i < n; i++) {
      x[i] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / SR));
    }
    return x;
  }

  /**
   * Klatt-style pulse train through four formant resonators: the standard
   * stand-in for a sustained vowel, and periodic enough that any vocoder
   * artefact shows up immediately as lost periodicity.
   */
  private static float[] vowel(double f0, double seconds) {
    int n = (int) (SR * seconds);
    double[] r = new double[5];
    double[] b1 = new double[5];
    double[] b2 = new double[5];
    double[] gain = new double[5];
    double[][] formants = {{730, 90}, {1090, 110}, {2440, 160}, {3400, 220}};
    for (int i = 0; i < formants.length; i++) {
      double bw = formants[i][1];
      double freq = formants[i][0];
      r[i + 1] = Math.exp(-Math.PI * bw / SR);
      b1[i + 1] = 2 * r[i + 1] * Math.cos(2 * Math.PI * freq / SR);
      b2[i + 1] = -(r[i + 1] * r[i + 1]);
      gain[i + 1] = 1 - r[i + 1];
    }
    float[] y = new float[n];
    double[] state1 = new double[5];
    double[] state2 = new double[5];
    double phase = 0;
    double peak = 0;
    for (int i = 0; i < n; i++) {
      phase += f0 / SR;
      double pulse = 0;
      if (phase >= 1.0) {
        phase -= 1.0;
        pulse = 1.0;
      }
      double x = pulse;
      for (int k = 1; k <= formants.length; k++) {
        double v = gain[k] * x + b1[k] * state1[k] + b2[k] * state2[k];
        state2[k] = state1[k];
        state1[k] = v;
        x = v;
      }
      y[i] = (float) x;
      peak = Math.max(peak, Math.abs(x));
    }
    double scale = 0.35 / Math.max(peak, 1e-9);
    int fade = SR / 100;
    for (int i = 0; i < n; i++) {
      double g = scale;
      if (i < fade) {
        g *= i / (double) fade;
      } else if (i > n - fade) {
        g *= (n - i) / (double) fade;
      }
      y[i] *= (float) g;
    }
    return y;
  }

  private static double rms(float[] x, int from, int to) {
    to = Math.min(to, x.length);
    double sum = 0;
    int n = 0;
    for (int i = from; i < to; i++) {
      sum += x[i] * x[i];
      n++;
    }
    return n == 0 ? 0 : Math.sqrt(sum / n);
  }

  /**
   * Harmonic-to-noise ratio from the normalised autocorrelation at one pitch
   * period, searched over a few neighbouring lags. This is the number that
   * separates "a voice" from "a raspy vocoder" (a clean conversion keeps the
   * periodicity of the source, a broken one scatters it).
   */
  private static double hnr(float[] x, int from, int n, double f0) {
    from = Math.max(0, from);
    n = Math.min(n, x.length - from);
    int period = (int) Math.round(SR / f0);
    double best = -1;
    for (int lag = Math.max(1, period - 4); lag <= period + 4; lag++) {
      double ab = 0;
      double aa = 0;
      double bb = 0;
      for (int i = 0; i + lag < n; i++) {
        double a = x[from + i];
        double b = x[from + i + lag];
        ab += a * b;
        aa += a * a;
        bb += b * b;
      }
      double corr = ab / Math.sqrt(aa * bb + 1e-20);
      if (corr > best) {
        best = corr;
      }
    }
    if (best <= 0.0) {
      return -60;
    }
    best = Math.min(best, 0.9999);
    return 10 * Math.log10(best / (1 - best));
  }

  /** Frequency of the strongest partial below Nyquist/2, parabolic interpolation. */
  private static double dominantHz(float[] x, int from, int size) {
    float[] mag = spectrum(x, from, size);
    int best = 1;
    for (int i = 1; i < mag.length / 2; i++) {
      if (mag[i] > mag[best]) {
        best = i;
      }
    }
    double bin = (double) SR / size;
    double a = mag[best - 1];
    double b = mag[best];
    double c = mag[best + 1];
    double delta = 0.5 * (a - c) / (a - 2 * b + c);
    return (best + delta) * bin;
  }

  /** Fraction of the energy that is not within +-bandHz of centreHz. */
  private static double outOfBandFraction(float[] x, int from, int size, double centreHz,
      double bandHz) {
    float[] mag = spectrum(x, from, size);
    double bin = (double) SR / size;
    double total = 0;
    double outside = 0;
    for (int i = 1; i < mag.length; i++) {
      double p = mag[i] * mag[i];
      total += p;
      if (Math.abs(i * bin - centreHz) > bandHz) {
        outside += p;
      }
    }
    return total <= 0 ? 1 : outside / total;
  }

  private static float[] spectrum(float[] x, int from, int size) {
    float[] re = new float[size];
    float[] im = new float[size];
    for (int i = 0; i < size && from + i < x.length; i++) {
      re[i] = (float) (x[from + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / size)));
    }
    com.android.incallui.voicefx.dsp.Fft fft = new com.android.incallui.voicefx.dsp.Fft(size);
    fft.forward(re, im);
    float[] mag = new float[size / 2 + 1];
    for (int i = 0; i < mag.length; i++) {
      mag[i] = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]);
    }
    return mag;
  }

  private static String presetName(int preset) {
    switch (preset) {
      case VoiceFxProcessor.PRESET_GIRL:
        return "Girl";
      case VoiceFxProcessor.PRESET_AGENT:
        return "Agent";
      case VoiceFxProcessor.PRESET_CHILD:
        return "Child";
      case VoiceFxProcessor.PRESET_BOY:
        return "Boy";
      default:
        return "Robot";
    }
  }

  private static void check(boolean ok, String message) {
    checks++;
    if (!ok) {
      failures++;
    }
    System.out.printf("  [%s] %-14s %s%n", ok ? "PASS" : "FAIL", group, message);
  }
}
