"""VoiceFX v1.5 DSP lab — Python reference model of the Java TD-PSOLA engine.

This mirrors, parameter-for-parameter, the algorithm ported to
``java/com/android/incallui/voicefx/VoiceFxProcessor.java`` (v2):

    DC-block HPF -> noise gate -> F0 track (NSDF, x3 decimated, 10 ms cadence)
    -> TD-PSOLA (epoch grains, independent pitch ratio + formant resampling,
       optional multi-layer mix + F0 monotonizing) -> EQ -> compressor
    -> saturation -> soft-clip limiter

It exists so presets can be *heard and measured* on a PC before rebuilding the
flashable module:

    python3 psola_lab.py selftest
    python3 psola_lab.py convert in.wav out.wav --preset girl
    python3 psola_lab.py convert in.wav out.wav --preset agent
    python3 psola_lab.py demo            # synthetic male phrase -> out/*.wav + spectrogram

Input WAV: mono 16-bit, any sample rate (resampled to 48 kHz like the phone).
Record a dry phone-mic clip of yourself (headphones, quiet room, normal
speaking voice), convert it, and judge Girl/Agent with your own ears.
"""
from __future__ import annotations

import argparse
import math
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly

FS = 48000
BLOCK = 480            # 10 ms @ 48 kHz — same cadence as VoiceFxController
F0_DECIM = 3           # F0 tracker runs at 16 kHz
F0_WIN_S = 0.040       # 40 ms tracker window (max-quality mode)
F0_MIN, F0_MAX = 65.0, 340.0
LOOKAHEAD_S = 0.045    # PSOLA look-ahead behind the input frontier

# ---------------------------------------------------------------- presets ---
# Keep in sync with VoiceFxProcessor.java (v1.5 / VoiceFxProcessor v2).

@dataclass
class Layer:
    ratio: float       # pitch ratio (multiplies F0)
    formant: float     # vocal-tract scale (>1 shorter tract)
    gain: float

@dataclass
class Preset:
    name: str
    layers: list = field(default_factory=list)
    target_f0: float = 0.0        # >0 => adaptive: aim median F0 here
    ratio_min: float = 1.0
    ratio_max: float = 1.0
    base_ratio: float = 1.0       # used when target_f0 == 0
    formant: float = 1.0          # single-layer formant when layers empty
    intonation: float = 1.0       # exponent on F0 variation (1=natural)
    eq: list = field(default_factory=list)  # (kind, f0, q, gain_db)
    breath: float = 0.0
    ring_hz: float = 0.0
    ring_wet: float = 0.0
    drive_db: float = 0.0
    echo_ms: float = 0.0
    echo_wet: float = 0.0
    echo_fb: float = 0.0
    comp_ratio: float = 1.0
    comp_thr_db: float = 0.0

GIRL = Preset(
    "girl",
    target_f0=196.0, ratio_min=1.35, ratio_max=1.90, base_ratio=2 ** (7.5 / 12),
    formant=1.17, intonation=1.10,
    eq=[("hp", 100, 0.707, 0), ("lowshelf", 300, 0.707, -2.5),
        ("peak", 3200, 0.8, 1.5), ("highshelf", 6500, 0.707, 2.0)],
    breath=0.015, comp_ratio=3.0, comp_thr_db=-20.0,
)
AGENT = Preset(
    "agent",
    layers=[Layer(0.67, 0.90, 0.85), Layer(0.89, 0.97, 0.55), Layer(1.33, 1.05, 0.45)],
    intonation=0.35,  # keep only 35% of natural F0 variation (deadpan)
    eq=[("hp", 250, 0.707, 0), ("peak", 1800, 0.9, 2.0), ("lp", 3800, 0.707, 0)],
    drive_db=3.0, echo_ms=30.0, echo_wet=0.06, echo_fb=0.15,
    comp_ratio=6.0, comp_thr_db=-24.0,
)
BOY = Preset(
    "boy", base_ratio=0.80, formant=0.93,
    eq=[("lowshelf", 200, 0.707, 2.0)], comp_ratio=2.0, comp_thr_db=-20.0,
)
CHILD = Preset(
    "child",
    target_f0=290.0, ratio_min=1.60, ratio_max=2.40, base_ratio=2 ** (10 / 12),
    formant=1.32, intonation=1.05,
    eq=[("hp", 140, 0.707, 0), ("highshelf", 5000, 0.707, 3.0)],
    comp_ratio=3.0, comp_thr_db=-20.0,
)
ROBOT = Preset(
    "robot", target_f0=110.0, ratio_min=0.5, ratio_max=2.0, base_ratio=1.0,
    formant=1.0, intonation=0.0,
    eq=[("hp", 180, 0.707, 0)], ring_hz=45.0, ring_wet=0.8, drive_db=5.0,
    comp_ratio=4.0, comp_thr_db=-22.0,
)
PRESETS = {p.name: p for p in (GIRL, AGENT, BOY, CHILD, ROBOT)}

# ---------------------------------------------------------------- biquad ----

class Biquad:
    """RBJ biquad, direct form II transposed (same formulas as the Java)."""

    def __init__(self, kind: str, fs: float, f0: float, q: float, gain_db: float):
        w0 = 2 * math.pi * f0 / fs
        cs, sn = math.cos(w0), math.sin(w0)
        alpha = sn / (2 * q)
        A = 10 ** (gain_db / 40)
        if kind == "lp":
            b0, b1, b2 = (1 - cs) / 2, 1 - cs, (1 - cs) / 2
            a0, a1, a2 = 1 + alpha, -2 * cs, 1 - alpha
        elif kind == "hp":
            b0, b1, b2 = (1 + cs) / 2, -(1 + cs), (1 + cs) / 2
            a0, a1, a2 = 1 + alpha, -2 * cs, 1 - alpha
        elif kind == "peak":
            b0, b1, b2 = 1 + alpha * A, -2 * cs, 1 - alpha * A
            a0, a1, a2 = 1 + alpha / A, -2 * cs, 1 - alpha / A
        elif kind == "lowshelf":
            b0 = A * ((A + 1) - (A - 1) * cs + 2 * math.sqrt(A) * alpha)
            b1 = 2 * A * ((A - 1) - (A + 1) * cs)
            b2 = A * ((A + 1) - (A - 1) * cs - 2 * math.sqrt(A) * alpha)
            a0 = (A + 1) + (A - 1) * cs + 2 * math.sqrt(A) * alpha
            a1 = -2 * ((A - 1) + (A + 1) * cs)
            a2 = (A + 1) + (A - 1) * cs - 2 * math.sqrt(A) * alpha
        elif kind == "highshelf":
            b0 = A * ((A + 1) + (A - 1) * cs + 2 * math.sqrt(A) * alpha)
            b1 = -2 * A * ((A - 1) + (A + 1) * cs)
            b2 = A * ((A + 1) + (A - 1) * cs - 2 * math.sqrt(A) * alpha)
            a0 = (A + 1) - (A - 1) * cs + 2 * math.sqrt(A) * alpha
            a1 = 2 * ((A - 1) - (A + 1) * cs)
            a2 = (A + 1) - (A - 1) * cs - 2 * math.sqrt(A) * alpha
        else:
            raise ValueError(kind)
        self.b0, self.b1, self.b2 = b0 / a0, b1 / a0, b2 / a0
        self.a1, self.a2 = a1 / a0, a2 / a0
        self.z1 = self.z2 = 0.0

    def process(self, x: np.ndarray) -> np.ndarray:
        y = np.empty_like(x)
        b0, b1, b2, a1, a2, z1, z2 = self.b0, self.b1, self.b2, self.a1, self.a2, self.z1, self.z2
        for i, xi in enumerate(x):
            yi = b0 * xi + z1
            z1 = b1 * xi - a1 * yi + z2
            z2 = b2 * xi - a2 * yi
            y[i] = yi
        self.z1, self.z2 = z1, z2
        return y

# ------------------------------------------------------------- F0 tracker ---

class F0Tracker:
    """NSDF autocorrelation on a x3-decimated stream, one estimate per block.

    Mirrors the Java tracker: 30 ms window @16 kHz, lag range 65-340 Hz,
    first-strong-peak picking (octave-down preference), parabolic refine.
    """

    def __init__(self, fs: int = FS):
        self.fs = fs
        self.fsd = fs // F0_DECIM
        self.win = int(F0_WIN_S * self.fsd)
        self.lag_lo = int(self.fsd / F0_MAX)
        self.lag_hi = int(self.fsd / F0_MIN)
        self.dec = np.zeros(0, dtype=np.float64)
        self._lp = 0.0
        self._phase = 0
        self.period = fs / 120.0       # running period @ fs
        self.voiced = False
        self.f0 = 120.0
        self.log_median = math.log(120.0)  # running source median (voiced only)
        self._hist: list = []          # last voiced estimates (median-of-3)

    def push_block(self, x: np.ndarray) -> tuple[float, bool]:
        # one-pole low-pass (~900 Hz @48k) then keep every 3rd sample
        a = 0.117  # 1-exp(-2*pi*900/48000)
        out = []
        lp, ph = self._lp, self._phase
        for xi in x:
            lp += a * (xi - lp)
            ph += 1
            if ph == F0_DECIM:
                ph = 0
                out.append(lp)
        self._lp, self._phase = lp, ph
        if out:
            self.dec = np.concatenate([self.dec, np.asarray(out)])
            keep = self.win + self.lag_hi + 64
            if len(self.dec) > keep:
                self.dec = self.dec[-keep:]
        return self._estimate()

    def _estimate(self) -> tuple[float, bool]:
        w = self.win
        if len(self.dec) < w + self.lag_hi:
            return self.f0, False
        frame = self.dec[-(w + self.lag_hi):].copy()
        ref = frame[:w] - np.mean(frame[:w])
        rms = math.sqrt(float(np.mean(ref ** 2)) + 1e-18)
        if rms < 0.004:
            self.voiced = False
            self._hist = []
            return self.f0, False
        # center clipping reduces formant influence on the correlation
        clip = 0.3 * float(np.max(np.abs(ref)))
        refc = np.sign(ref) * np.maximum(np.abs(ref) - clip, 0.0)
        nsdf = np.full(self.lag_hi + 1, -1.0)
        for lag in range(self.lag_lo, self.lag_hi + 1):
            seg = frame[lag:lag + w] - np.mean(frame[lag:lag + w])
            segc = np.sign(seg) * np.maximum(np.abs(seg) - clip, 0.0)
            num = 2.0 * float(np.dot(refc, segc))
            den = float(np.dot(refc, refc) + np.dot(segc, segc)) + 1e-18
            nsdf[lag] = num / den
        peak = int(np.argmax(nsdf[self.lag_lo:])) + self.lag_lo
        best = nsdf[peak]
        # first local peak reaching 90% of the best (prefers true period over 2P)
        chosen = peak
        for lag in range(self.lag_lo + 1, peak):
            if nsdf[lag] >= nsdf[lag - 1] and nsdf[lag] >= nsdf[lag + 1] and nsdf[lag] >= 0.9 * best:
                chosen = lag
                break
        if best < 0.42:
            self.voiced = False
            self._hist = []
            return self.f0, False
        # parabolic interpolation around the chosen lag
        lag_f = float(chosen)
        if 0 < chosen < len(nsdf) - 1:
            y0, y1, y2 = nsdf[chosen - 1], nsdf[chosen], nsdf[chosen + 1]
            den = (y0 - 2 * y1 + y2)
            if abs(den) > 1e-12:
                lag_f += 0.5 * (y0 - y2) / den
        f0 = self.fsd / max(lag_f, 1.0)
        f0 = min(max(f0, F0_MIN), F0_MAX)
        # Octave-error correction against the slow median (exact 2x/0.5x
        # jumps are tracker mistakes, not real voice movement), then a
        # median-of-3 over voiced estimates kills single-block warble.
        med = math.exp(self.log_median)
        r = f0 / med
        if 1.8 <= r <= 2.25 and f0 / 2 >= F0_MIN:
            f0 /= 2.0
        elif 0.44 <= r <= 0.56 and f0 * 2 <= F0_MAX:
            f0 *= 2.0
        self._hist = (self._hist + [f0])[-3:]
        if len(self._hist) == 3:
            f0 = sorted(self._hist)[1]
        self.f0 = f0
        self.period = self.fs / f0
        self.voiced = True
        self.log_median += 0.03 * (math.log(f0) - self.log_median)
        return f0, True

    @property
    def median_f0(self) -> float:
        return math.exp(self.log_median)

# ----------------------------------------------------------------- PSOLA ----

def _cubic(x0, x1, x2, x3, t):
    """Catmull-Rom interpolation (same kernel as the Java)."""
    return x1 + 0.5 * t * (x2 - x0 + t * (2 * x0 - 5 * x1 + 4 * x2 - x3 +
                                           t * (3 * (x1 - x2) + x3 - x0)))

class Psola:
    """Streaming multi-layer TD-PSOLA with independent pitch/formant.

    Analysis epochs are pitch marks (peak-refined). A synthesis epoch picks
    the nearest analysis grain (2P samples, formant-resampled, Hann windowed)
    and overlap-adds it at spacing P/ratio. Output timeline == input timeline
    (duration preserved); emitted samples are weight-normalized.
    """

    def __init__(self, fs: int, layers: list, intonation: float):
        self.fs = fs
        self.layers = layers
        self.intonation = intonation
        self.look = int(LOOKAHEAD_S * fs)
        self.unvoiced_period = fs // 200
        self.reset()

    def reset(self):
        self.buf = np.zeros(0, dtype=np.float64)
        self.base = 0
        self.next_a = 0
        self.epochs = []          # (pos, period, voiced)
        self.period = self.fs / 120.0
        self.next_s = [0.0] * len(self.layers)
        self.acc = np.zeros(0, dtype=np.float64)
        self.wgt = np.zeros(0, dtype=np.float64)
        self.acc_base = 0
        # per-block tracker params: (abs_end, f0, voiced, ratio_scale, median)
        self.blocks = []

    def note_block(self, abs_end: int, f0: float, voiced: bool,
                   ratio: float, median: float):
        self.blocks.append((abs_end, f0, voiced, ratio, median))
        if len(self.blocks) > 64:
            self.blocks = self.blocks[-64:]

    def _params_at(self, pos: float):
        for end, f0, voiced, ratio, median in self.blocks:
            if pos < end:
                return f0, voiced, ratio, median
        if self.blocks:
            _, f0, voiced, ratio, median = self.blocks[-1]
            return f0, voiced, ratio, median
        return 120.0, False, 1.0, 120.0

    def _read(self, pos: float) -> float:
        i = int(math.floor(pos))
        t = pos - i
        j = i - self.base
        n = len(self.buf)
        def s(k):
            return self.buf[min(max(j + k, 0), n - 1)] if n else 0.0
        return _cubic(s(-1), s(0), s(1), s(2), t)

    def process(self, x: np.ndarray) -> np.ndarray:
        self.buf = np.concatenate([self.buf, x.astype(np.float64)])
        end_abs = self.base + len(self.buf)
        frontier = end_abs - self.look

        # --- analysis epochs (pitch marks) ---
        while self.next_a + self.period < frontier:
            f0, voiced, _, _ = self._params_at(self.next_a)
            period = self.fs / f0 if voiced else float(self.unvoiced_period)
            period = min(max(period, self.fs / F0_MAX), self.fs / F0_MIN) \
                if voiced else period
            self.period = 0.7 * self.period + 0.3 * period if voiced else self.period
            mark = self.next_a
            if voiced:
                mark = self._refine_peak(mark, int(period))
            self.epochs.append((mark, int(round(period)), voiced))
            self.next_a = mark + max(24, int(round(period)))

        # ensure accumulator covers the frontier
        need = int(frontier) - self.acc_base + int(self.fs / F0_MIN) * 2
        if need > len(self.acc):
            pad = need - len(self.acc)
            self.acc = np.concatenate([self.acc, np.zeros(pad)])
            self.wgt = np.concatenate([self.wgt, np.zeros(pad)])

        # --- synthesis, one train per layer over the shared epochs ---
        for li, layer in enumerate(self.layers):
            while self.next_s[li] < frontier and self.epochs:
                s = self.next_s[li]
                ep, period, voiced = min(self.epochs, key=lambda e: abs(e[0] - s))
                _, _, ratio, _ = self._params_at(s)
                r = ratio * layer.ratio
                L = 2 * period
                if L < 8:
                    self.next_s[li] += 24
                    continue
                c = int(round(s)) - self.acc_base
                start = c - period
                idx = np.arange(L)
                src_pos = ep + (idx - period) * layer.formant
                grain = np.array([self._read(float(p)) for p in src_pos])
                win = np.hanning(L)
                grain *= win * layer.gain
                a, b = max(start, 0), min(start + L, len(self.acc))
                if b > a:
                    ga, gb = a - start, a - start + (b - a)
                    self.acc[a:b] += grain[ga:gb]
                    self.wgt[a:b] += win[ga:gb] * layer.gain
                step = period / r if voiced else float(period)
                self.next_s[li] += max(24.0, step)

        # --- emit finalized region (behind every synthesis train's tail) ---
        tail = int(self.fs / F0_MIN)
        emit_to = int(min(self.next_s)) - tail - self.acc_base if self.next_s else 0
        emit_to = min(max(emit_to, 0), len(self.acc))
        if emit_to <= 0:
            ready = np.zeros(0)
        else:
            w = self.wgt[:emit_to]
            ready = np.where(w > 1e-4, self.acc[:emit_to] / np.maximum(w, 0.25), 0.0)
            self.acc = self.acc[emit_to:]
            self.wgt = self.wgt[emit_to:]
            self.acc_base += emit_to
        self._trim(frontier)
        return ready

    def _refine_peak(self, mark: int, period: int) -> int:
        r = max(4, period // 4)
        a, b = mark - r - self.base, mark + r - self.base
        if a < 0 or b >= len(self.buf) or b <= a:
            return mark
        seg = self.buf[a:b]
        return mark - r + int(np.argmax(seg))

    def _trim(self, frontier: int):
        keep = int(min([self.next_a] + [int(v) for v in self.next_s])) \
            - int(self.fs / F0_MIN) * 3
        if keep > self.base:
            cut = keep - self.base
            if 0 < cut < len(self.buf):
                self.buf = self.buf[cut:]
                self.base += cut
                self.epochs = [e for e in self.epochs if e[0] >= self.base - 1024]

# -------------------------------------------------------------- processor ---

class Compressor:
    def __init__(self, fs, ratio, thr_db, attack_ms=4.0, release_ms=140.0):
        self.ratio, self.thr = ratio, thr_db
        self.ka = math.exp(-1.0 / (fs * attack_ms / 1000))
        self.kr = math.exp(-1.0 / (fs * release_ms / 1000))
        self.env = 0.0

    def process(self, x):
        if self.ratio <= 1.0:
            return x
        y = np.empty_like(x)
        env = self.env
        for i, xi in enumerate(x):
            a = abs(xi)
            env = a + (self.ka if a > env else self.kr) * (env - a)
            level_db = 20 * math.log10(env + 1e-9)
            over = level_db - self.thr
            gain_db = -over * (1 - 1 / self.ratio) if over > 0 else 0.0
            y[i] = xi * (10 ** (gain_db / 20))
        self.env = env
        return y

class Processor:
    """Full v2 chain, block-streaming like the phone (480-sample blocks)."""

    def __init__(self, preset: Preset, fs: int = FS):
        self.p, self.fs = preset, fs
        self.dc = Biquad("hp", fs, 80, 0.707, 0)
        self.eq = [Biquad(k, fs, f, q, g) for (k, f, q, g) in preset.eq]
        layers = preset.layers or [Layer(1.0, preset.formant, 1.0)]
        self.psola = Psola(fs, layers, preset.intonation)
        self.tracker = F0Tracker(fs)
        self.comp = Compressor(fs, preset.comp_ratio, preset.comp_thr_db)
        self.ratio_smooth = preset.base_ratio
        self.abs_in = 0
        self.fifo = np.zeros(0)
        self.makeup = 1.0
        self.ring_phase = 0.0
        self.noise_floor = 1e-4
        self.gate_gain = 1.0
        elen = max(1, int(fs * preset.echo_ms / 1000)) if preset.echo_wet > 0 else 1
        self.echo_buf = np.zeros(elen)
        self.echo_pos = 0
        self._rng = np.random.default_rng(7)

    def _ratio_for(self, f0: float, voiced: bool) -> float:
        p = self.p
        if p.target_f0 > 0:
            med = self.tracker.median_f0
            ft = p.target_f0 * (max(f0, 1.0) / med) ** p.intonation
            r = ft / max(f0, 1.0) if voiced else self.ratio_smooth
            r = min(max(r, p.ratio_min), p.ratio_max)
        elif p.layers:
            med = self.tracker.median_f0
            # monotonize: compress F0 variation toward the running median
            ft = med * (max(f0, 1.0) / med) ** p.intonation
            r = ft / max(f0, 1.0) if voiced else self.ratio_smooth
        else:
            r = p.base_ratio
        self.ratio_smooth += 0.25 * (r - self.ratio_smooth)
        return self.ratio_smooth

    def process_block(self, x16: np.ndarray) -> np.ndarray:
        p = self.p
        x = x16.astype(np.float64) / 32768.0
        in_rms = math.sqrt(float(np.mean(x ** 2)) + 1e-18)
        x = self.dc.process(x)
        # noise gate (smoothed, soft floor) — keeps PSOLA off room noise
        self.noise_floor = min(self.noise_floor * 1.002, 0.02) \
            if in_rms > self.noise_floor else in_rms
        thr = max(0.006, self.noise_floor * 2.5)
        target = 1.0 if in_rms > thr else max(0.08, (in_rms / thr) ** 2)
        self.gate_gain += (0.5 if target > self.gate_gain else 0.08) * (target - self.gate_gain)
        xg = x * self.gate_gain

        f0, voiced = self.tracker.push_block(xg)
        ratio = self._ratio_for(f0, voiced)
        self.abs_in += len(x)
        self.psola.note_block(self.abs_in, f0, voiced, ratio, self.tracker.median_f0)
        produced = self.psola.process(xg)
        if len(produced):
            self.fifo = np.concatenate([self.fifo, produced])
        if len(self.fifo) >= len(x):
            y = self.fifo[:len(x)].copy()
            self.fifo = self.fifo[len(x):]
        else:
            return np.zeros(len(x), dtype=np.int16)  # still priming

        # smoothed RMS makeup so presets sit at comparable loudness
        out_rms = math.sqrt(float(np.mean(y ** 2)) + 1e-18)
        if voiced and out_rms > 1e-6:
            want = min(max(in_rms / out_rms, 0.5), 2.2)
            self.makeup += 0.15 * (want - self.makeup)
        y = y * self.makeup
        for bq in self.eq:
            y = bq.process(y)
        if p.breath > 0:
            env = np.abs(y)
            noise = self._rng.standard_normal(len(y))
            hp = np.concatenate([[0.0], np.diff(noise)])  # crude 1st-diff HPF
            y = y + hp * env * p.breath * 4.0
        if p.ring_wet > 0 and p.ring_hz > 0:
            ph = self.ring_phase + (p.ring_hz / self.fs) * np.arange(len(y))
            self.ring_phase = float((ph[-1] + p.ring_hz / self.fs) % 1.0)
            mod = np.sin(2 * math.pi * ph)
            y = y * (1 - p.ring_wet) + y * mod * p.ring_wet
        if p.drive_db > 0:
            g = 10 ** (p.drive_db / 20)
            y = np.tanh(g * y) / math.tanh(g)
        if p.echo_wet > 0:
            out = np.empty_like(y)
            buf, pos = self.echo_buf, self.echo_pos
            for i, xi in enumerate(y):
                d = buf[pos]
                buf[pos] = xi + d * p.echo_fb
                pos = (pos + 1) % len(buf)
                out[i] = xi * (1 - p.echo_wet) + d * p.echo_wet
            self.echo_pos, y = pos, out
        y = self.comp.process(y)
        # cubic soft clip (same curve as v1, kept: cheap and glitch-free)
        y = np.where(np.abs(y) > 1.5, np.sign(y), 1.5 * y - 0.5 * y ** 3)
        return np.round(np.clip(y, -1, 1) * 32767).astype(np.int16)

    def process_all(self, x16: np.ndarray) -> np.ndarray:
        out = []
        n = len(x16)
        padded = np.concatenate([x16, np.zeros(FS // 2, dtype=np.int16)])
        for i in range(0, len(padded) - BLOCK + 1, BLOCK):
            out.append(self.process_block(padded[i:i + BLOCK].astype(np.float64)))
        y = np.concatenate(out) if out else np.zeros(0, dtype=np.int16)
        # drop priming latency so in/out roughly align for audition files
        lat = int(LOOKAHEAD_S * FS) + BLOCK
        y = y[lat:lat + n] if len(y) > lat else y[:n]
        fade = min(int(0.02 * FS), len(y))
        if fade:
            y = y.astype(np.float64)
            y[:fade] *= np.linspace(0, 1, fade)
            y = y.astype(np.int16)
        return y

# ------------------------------------------------------------ synth/demo ----

VOWELS = {  # male formants F1/F2/F3 (Peterson & Barney, approx.)
    "a": (730, 1090, 2440), "e": (530, 1840, 2480), "i": (270, 2290, 3010),
    "o": (570, 840, 2410), "u": (300, 870, 2240),
}

def synth_male_phrase(fs: int = FS, f0_base: float = 118.0) -> np.ndarray:
    """Speech-like synthetic adult-male phrase (vowel sequence + fricatives)."""
    rng = np.random.default_rng(11)
    seq = [("a", 0.28), ("e", 0.22), ("i", 0.24), ("o", 0.26), ("u", 0.24),
           ("a", 0.30), ("i", 0.22), ("a", 0.34)]
    parts, f0_trace = [], []
    total = sum(d for _, d in seq) + 0.15
    t_cursor = 0.0
    for vi, (v, dur) in enumerate(seq):
        n = int(dur * fs)
        t = np.arange(n) / fs
        # sentence contour: declination + accent bumps
        prog = (t_cursor + t) / total
        f0 = f0_base * (1.08 - 0.18 * prog) * (1 + 0.10 * math.sin(vi * 1.3))
        f0 *= 1 + 0.03 * np.sin(2 * math.pi * 4.5 * t)  # jitter/vibrato
        phase = np.cumsum(2 * math.pi * f0 / fs)
        # glottal-ish source: harmonic stack with 1/k rolloff
        src = np.zeros(n)
        for k in range(1, 28):
            src += (1.0 / k) * np.sin(k * phase + 0.2 * k)
        src /= 2.5
        # formant resonators (peaking biquads) with 30 ms transitions
        out = src.copy()
        for fi, ff in enumerate(VOWELS[v]):
            bq = Biquad("peak", fs, ff, 6.0, 10.0 - fi * 2.0)
            out = bq.process(out)
        env = np.minimum(1.0, np.minimum(t / 0.03, (dur - t) / 0.04))
        parts.append(out * np.maximum(env, 0.0) * 0.5)
        f0_trace.append(f0)
        t_cursor += dur
        # short fricative burst between syllables
        fn = int(0.03 * fs)
        fric = rng.standard_normal(fn)
        fric = Biquad("hp", fs, 4000, 0.707, 0).process(fric) * 0.12
        parts.append(fric)
    y = np.concatenate(parts)
    y = np.concatenate([np.zeros(int(0.05 * fs)), y, np.zeros(int(0.1 * fs))])
    peak = np.max(np.abs(y)) + 1e-9
    return (y / peak * 0.8 * 32767).astype(np.int16)

def measure_f0(x: np.ndarray, fs: int = FS) -> float:
    """Median voiced F0 via plain autocorrelation (independent checker)."""
    xf = x.astype(np.float64) / 32768.0
    win, hop = int(0.04 * fs), int(0.01 * fs)
    lo, hi = int(fs / 400), int(fs / 60)
    vals = []
    for i in range(0, max(0, len(xf) - win - hi), hop):
        fr = xf[i:i + win] - np.mean(xf[i:i + win])
        if math.sqrt(float(np.mean(fr ** 2))) < 0.03:
            continue
        corr = np.correlate(xf[i:i + win + hi], fr, "valid")
        e0 = float(np.dot(fr, fr)) + 1e-12
        seg = corr[lo:hi] / e0
        k = int(np.argmax(seg)) + lo
        if seg[k - lo] > 0.35:
            vals.append(fs / k)
    return float(np.median(vals)) if vals else 0.0

def synth_steady_vowel(v: str = "a", f0: float = 120.0, dur: float = 1.2,
                       fs: int = FS) -> np.ndarray:
    """Sustained synthetic male vowel — for aligned formant measurements."""
    n = int(dur * fs)
    t = np.arange(n) / fs
    phase = 2 * math.pi * f0 * t
    src = np.zeros(n)
    for k in range(1, 28):
        src += (1.0 / k) * np.sin(k * phase + 0.2 * k)
    src /= 2.5
    out = src
    for fi, ff in enumerate(VOWELS[v]):
        out = Biquad("peak", fs, ff, 6.0, 10.0 - fi * 2.0).process(out)
    env = np.minimum(1.0, np.minimum(t / 0.05, (dur - t) / 0.05))
    out = out * np.maximum(env, 0.0)
    peak = np.max(np.abs(out)) + 1e-9
    return (out / peak * 0.8 * 32767).astype(np.int16)

def measure_formants(x: np.ndarray, fs: int = FS, center_s: float = None) -> tuple:
    """Crude F1/F2 from a cepstrally-smoothed spectrum.

    Uses the window at ``center_s`` when given (aligned comparisons), else
    the loudest window.
    """
    xf = x.astype(np.float64) / 32768.0
    n = 4096
    if center_s is not None:
        loud = min(max(int(center_s * fs) - n // 2, 0), max(len(xf) - n, 0))
    else:
        loud = int(np.argmax(np.convolve(np.abs(xf), np.ones(n), "valid")))
    seg = xf[loud:loud + n] * np.hanning(n)
    spec = np.log(np.abs(np.fft.rfft(seg)) + 1e-9)
    cep = np.fft.irfft(spec)
    cep[48:-48] = 0  # lifter: keep vocal-tract envelope only
    env = np.real(np.fft.rfft(cep))
    freqs = np.fft.rfftfreq(n, 1 / fs)
    def peak_in(a, b):
        m = (freqs >= a) & (freqs <= b)
        return float(freqs[m][int(np.argmax(env[m]))])
    return peak_in(200, 1100), peak_in(1100, 3200)

def _log_envelope(x: np.ndarray, fs: int = FS, center_s: float = 0.6) -> np.ndarray:
    """Heavily-smoothed log-magnitude envelope on a log-frequency axis.

    Smoothing (~0.35 octave) removes the harmonic ripple, leaving the
    vocal-tract envelope whose log-frequency *shift* is the formant ratio.
    """
    xf = x.astype(np.float64) / 32768.0
    n = 4096
    start = min(max(int(center_s * fs) - n // 2, 0), max(len(xf) - n, 0))
    acc = None
    for off in range(start, min(start + 8192, len(xf) - n), 1024):
        mag = np.abs(np.fft.rfft(xf[off:off + n] * np.hanning(n)))
        acc = mag if acc is None else acc + mag
    freqs = np.fft.rfftfreq(n, 1 / fs)
    logf = np.linspace(math.log2(200), math.log2(3500), 512)
    env = np.interp(logf, np.log2(np.maximum(freqs, 1.0)), np.log(acc + 1e-9))
    k = 43  # ~0.35 octave
    env = np.convolve(env, np.ones(k) / k, mode="same")
    return env - np.mean(env)

def measure_formant_ratio(x_in: np.ndarray, x_out: np.ndarray) -> float:
    """Estimate the formant warp actually applied (out/in envelope shift)."""
    a, b = _log_envelope(x_in), _log_envelope(x_out)
    step = (math.log2(3500) - math.log2(200)) / 511
    best_s, best_c = 0.0, -1e18
    s = -0.6
    while s <= 0.6:
        shift_bins = s / step
        # env_out(f) ~= env_in(f / ratio): on the log axis out lags in by log2(ratio)
        idx = np.arange(len(a)) - shift_bins
        valid = (idx >= 0) & (idx <= len(a) - 1)
        b_shift = np.interp(np.arange(len(a)), np.arange(len(a))[valid], a[valid])
        c = float(np.dot(b - np.mean(b), b_shift - np.mean(b_shift)))
        if c > best_c:
            best_c, best_s = c, s
        s += 0.005
    return 2 ** best_s

# ------------------------------------------------------------------- CLI ----

def load_wav48(path: Path) -> np.ndarray:
    rate, data = wavfile.read(str(path))
    if data.ndim > 1:
        data = data[:, 0]
    if data.dtype == np.int16:
        x = data.astype(np.float64)
    elif data.dtype == np.int32:
        x = data.astype(np.float64) / 65536.0
    elif data.dtype == np.uint8:
        x = (data.astype(np.float64) - 128) * 256.0
    else:
        x = np.clip(data.astype(np.float64), -1, 1) * 32767
    if rate != FS:
        g = math.gcd(rate, FS)
        x = resample_poly(x, FS // g, rate // g)
    return np.clip(x, -32768, 32767).astype(np.int16)

def cmd_convert(args) -> int:
    x = load_wav48(Path(args.input))
    t0 = time.perf_counter()
    y = Processor(PRESETS[args.preset]).process_all(x)
    dt = time.perf_counter() - t0
    wavfile.write(args.output, FS, y)
    dur = len(x) / FS
    print(f"{args.preset}: {args.input} -> {args.output}")
    print(f"  F0 median: in {measure_f0(x):.1f} Hz -> out {measure_f0(y):.1f} Hz")
    print(f"  compute: {dt:.2f}s for {dur:.2f}s audio (RTF {dt / max(dur, 1e-9):.2f}, host Python)")
    return 0

def cmd_demo(args) -> int:
    out = Path(args.outdir)
    out.mkdir(parents=True, exist_ok=True)
    x = synth_male_phrase()
    wavfile.write(str(out / "demo_in_male.wav"), FS, x)
    print(f"in : F0 {measure_f0(x):.1f} Hz, formants {tuple(round(v) for v in measure_formants(x))}")
    results = {}
    for name in ("girl", "agent", "boy", "child", "robot"):
        y = Processor(PRESETS[name]).process_all(x)
        wavfile.write(str(out / f"demo_{name}.wav"), FS, y)
        results[name] = y
        print(f"{name:6s}: F0 {measure_f0(y):7.1f} Hz, formants "
              f"{tuple(round(v) for v in measure_formants(y))}, peak {np.max(np.abs(y))}")
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, axes = plt.subplots(3, 1, figsize=(10, 8), sharex=True)
        for ax, (title, sig) in zip(axes, [("Input (synthetic adult male)", x),
                                           ("Girl (PSOLA pitch->~196 Hz, formant x1.17)", results["girl"]),
                                           ("Agent (layered -7/-2/+5 st, monotone, 250-3800 Hz)", results["agent"])]):
            ax.specgram(sig.astype(np.float64) / 32768, NFFT=1024, Fs=FS,
                        noverlap=768, cmap="magma", vmin=-110, vmax=-20)
            ax.set_ylim(0, 6000)
            ax.set_ylabel("Hz")
            ax.set_title(title, fontsize=9)
        axes[-1].set_xlabel("seconds")
        fig.tight_layout()
        fig.savefig(str(out / "demo_spectrograms.png"), dpi=130)
        print(f"spectrogram: {out / 'demo_spectrograms.png'}")
    except Exception as e:  # plotting is best-effort
        print(f"(spectrogram skipped: {e})")
    return 0

def cmd_selftest(args) -> int:
    x = synth_male_phrase()
    f0_in = measure_f0(x)
    ok = True
    def check(label, cond, detail=""):
        nonlocal ok
        print(f"[{'PASS' if cond else 'FAIL'}] {label} {detail}")
        ok = ok and cond
    check("synthetic male F0 in 100-130 Hz", 100 <= f0_in <= 130, f"({f0_in:.1f} Hz)")

    girl = Processor(PRESETS["girl"]).process_all(x)
    f0_g = measure_f0(girl)
    check("Girl F0 in female band 175-220 Hz", 175 <= f0_g <= 220, f"({f0_g:.1f} Hz)")
    # Formants need an aligned, steady vowel (phrase windows land on
    # different vowels in in/out and make the ratio meaningless).
    vowel = synth_steady_vowel("a", f0=120.0)
    vowel_g = Processor(PRESETS["girl"]).process_all(vowel)
    fr = measure_formant_ratio(vowel, vowel_g)
    check("Girl formant warp measured x1.07..x1.28 (target 1.17)",
          1.07 <= fr <= 1.28, f"(measured x{fr:.3f})")
    check("Girl steady-vowel F0 lands on target ~196 Hz (185-208)",
          185 <= measure_f0(vowel_g) <= 208, f"({measure_f0(vowel_g):.1f} Hz)")
    check("Girl output finite/non-silent",
          np.all(np.isfinite(girl.astype(np.float64))) and np.max(np.abs(girl)) > 4000)

    agent = Processor(PRESETS["agent"]).process_all(x)
    xa = agent.astype(np.float64) / 32768
    spec = np.abs(np.fft.rfft(xa * np.hanning(len(xa))))
    freqs = np.fft.rfftfreq(len(xa), 1 / FS)
    e_band = spec[(freqs > 250) & (freqs < 3800)].sum()
    e_hi = spec[freqs > 6000].sum()
    check("Agent band-limited (HF << in-band)", e_hi < 0.12 * e_band,
          f"(HF/band {e_hi / max(e_band, 1e-9):.3f})")
    check("Agent output finite/non-silent", np.max(np.abs(agent)) > 4000)

    silence = Processor(PRESETS["girl"]).process_all(np.zeros(FS, dtype=np.int16))
    check("silence stays (near) silent", np.max(np.abs(silence)) < 300,
          f"(peak {np.max(np.abs(silence))})")

    t0 = time.perf_counter()
    Processor(PRESETS["agent"]).process_all(x)
    rtf = (time.perf_counter() - t0) / (len(x) / FS)
    print(f"[info] Agent (3 layers) host RTF {rtf:.2f} in interpreted Python; "
          f"Java/ART target is >10x faster than this reference.")
    print("SELFTEST:", "PASS" if ok else "FAIL")
    return 0 if ok else 1

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("convert")
    c.add_argument("input")
    c.add_argument("output")
    c.add_argument("--preset", choices=sorted(PRESETS), default="girl")
    c.set_defaults(fn=cmd_convert)
    d = sub.add_parser("demo")
    d.add_argument("--outdir", default=str(Path(__file__).parent / "out"))
    d.set_defaults(fn=cmd_demo)
    s = sub.add_parser("selftest")
    s.set_defaults(fn=cmd_selftest)
    args = ap.parse_args(argv)
    return args.fn(args)

if __name__ == "__main__":
    sys.exit(main())
