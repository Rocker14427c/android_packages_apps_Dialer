# VoiceFX v1.5 — realistic Girl + Agent (TD-PSOLA)

This folder is the offline lab + module builder for the v2 voice engine in
`java/com/android/incallui/voicefx/VoiceFxProcessor.java`. It does **not**
affect the Soong build (`Android.bp` never references it).

## Why v1 sounded fake, and what v2 does instead

| | v1.4 (old) | v1.5 (new) |
|---|---|---|
| Core | Modulated-delay granular shift — pitch **and** formants move together | Streaming **TD-PSOLA** — pitch ratio and formant ratio are independent |
| Girl | +4.5 st + shelves → "pitched-up male" | Adaptive median-F0 target **196 Hz** (ratio clamped ×1.35–×1.85), formants **×1.17**, widened intonation, chest cut, presence, breath, compressor |
| Agent | −6 st + ring-mod + 85 ms echo → Dalek/robot | **Layered copies −7 st / −2 st / +5 st** (how L's laptop voice is actually built), monotonized to 35 % F0 variation, 250–3800 Hz band, hard compression, light drive |
| Extras | — | NSDF F0 tracker @16 kHz, noise gate, RMS makeup, soft-clip safety net |

Measured on the synthetic adult-male test signal (`psola_lab.py selftest`):
Girl output F0 **195.5 Hz**, applied formant warp measured **×1.185**
(target ×1.17); silence stays silent; Agent confirms band-limited output.
Design references: MIT streaming TD-PSOLA voice changer (presets + benchmarks),
Praat gender-change anatomy (pitch ×~1.7, formants ×~1.15), Rubber Band/WORLD
formant literature, Death-Note "L" recreations (layered opposite pitch copies).

Honest ceiling: pure DSP *moves* your voice; it cannot regenerate a different
person's timbre like a neural model (RVC). Within a phone-call codec
(AMR-WB/EVS bandwidth) a well-tuned PSOLA conversion is the most convincing
thing that fits a budget phone's CPU in real time. Speak naturally — don't
raise your own pitch on top of Girl; the engine does the lifting, and your
normal resonance/relaxed delivery converts far more convincingly.

## 1. Hear it first (no flashing)

On any PC with Python 3 + `numpy scipy matplotlib`:

```bash
pip install numpy scipy matplotlib
python3 psola_lab.py selftest     # objective checks (F0/formant/silence)
python3 psola_lab.py demo         # synthetic male phrase -> out/*.wav + spectrograms
# YOUR voice: record a dry mono clip on the phone (quiet room, normal speech)
python3 psola_lab.py convert myvoice.wav girl.wav  --preset girl
python3 psola_lab.py convert myvoice.wav agent.wav --preset agent
```

The Python model mirrors the Java parameters; tune there, then copy the
numbers into `VoiceFxProcessor.java` (marked `// ---- tuning`):

- **Girl too low / still masculine** → `GIRL_TARGET_F0_HZ` 196 → 205–215.
- **Girl chipmunk / cartoonish** → lower target (185–190) and/or
  `GIRL_FORMANT` → 1.12. **Too "big"** → formant 1.20–1.22.
- **Agent unclear** → raise the −2 st layer gain (0.55 → 0.7), lower +5 st.
  **Agent not anonymous enough** → raise +5 st gain, intonation 0.35 → 0.2.

## 2. Build the flashable v1.5 module (one command)

Needs on your PC: JDK, Android SDK build-tools (`ANDROID_HOME`), your
`VoiceFX-Dialer-Module-v1.4.zip` and `axionvoice.jks`. The builder reuses the
v1.4 module byte-for-byte and swaps **only** `VoiceFxProcessor*.smali` in its
APK (signed with your key, verified against the v1.4 cert + package
`com.axionvoice.dialer` before zipping):

```bash
python3 build_v15.py \
  --v14-zip /path/to/VoiceFX-Dialer-Module-v1.4.zip \
  --keystore /path/to/axionvoice.jks \
  --out /path/to/out
# -> out/VoiceFX-Dialer-Module-v1.5.zip (+ md5.txt, BUILD_REPORT_V15.md)
```

Flash over v1.4 in ReSukiSU → reboot. **Uninstall + reboot still reverts
everything** (v1.4 `service.sh`/`uninstall.sh` are reused unchanged: Phone
role returns to stock, stock InCallService re-enabled, zero partition writes).

`module-template/` is a faithful reconstruction of the v1.4 module layout
(from its build report) for reference / disaster recovery only — the builder
above is the supported path.

## 3. On-device checks after flashing

1. Test call to a friendly number (or second phone): Girl should read as an
   adult woman, not a pitched man; Agent should be intelligible but
   unrecognizable, flat and "through a laptop".
2. If audio ever glitches, capture
   `adb logcat -s VoiceFxController VoiceFxUi` — the engine never touches
   call audio on failure (v1.4 guarantees kept).
3. Battery/heat: the PSOLA core is a few MFLOPS + a 16 kHz correlator;
   expect no meaningful change vs v1.4 on MT6769-class hardware.
   Added one-way latency ≈ 50 ms (35 ms look-ahead + one pitch period).
