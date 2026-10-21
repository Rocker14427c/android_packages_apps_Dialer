# VoiceFX Dialer — release tooling

Everything needed to turn the sources in `java/com/android/incallui/voicefx/`
into an installable module, plus the tests that have to pass first. Nothing
here is part of the Soong build (`Android.bp` never references this folder).

```
selftest/Selftest.java   offline DSP regression suite (pitch, periodicity, silence, headroom)
selftest.sh              compiles and runs it against android.jar
test-lifecycle.sh        simulates install -> boot -> uninstall with mocked pm/cmd
build_v16.py             patches the released v1.4 APK, signs it, packs the module
module-template/         the scripts that ship inside the module
stubs/                   compile-only stand-ins for dialer classes (never shipped)
.github/workflows/build-voicefx-v16.yml   the whole thing, gated and published by CI
```

## How the module is produced

There is no source-built binary for this ROM, so a release patches the proven
**v1.4** module:

1. `javac` compiles `VoiceFxProcessor`, `VoiceFxPreset`, `VoiceFxController` and
   `dsp/*` against `android.jar`; the `stubs/` folder only exists so the
   controller's calls into `AudioModeProvider` / `TelecomAdapter` type-check.
2. `d8` turns those classes into dex, `baksmali` disassembles them.
3. **ABI gate**: every dialer member the new classes reference (exact method
   signature, exact field type) is looked up in the APK's own smali. A renamed
   or retyped method fails the build here instead of throwing
   `NoSuchMethodError` on the phone. This is the bug class that produced the
   "Voice changer unavailable right now" dialog in v1.4.
4. The old voice classes are deleted from the dex's smali tree, the new ones are
   written in, and the dex is reassembled — the rest of the APK is untouched.
5. The APK is re-signed, packaged with the module scripts, and the resulting zip
   is re-opened and validated (layout, permissions files, exec bits, CRC,
   `module.prop` bumped, dex set unchanged).

## Gates that must pass

| gate | what it proves |
|---|---|
| `selftest.sh` | pitch within 6 cents of the request, HNR > 15 dB for Girl/Agent/Boy, silence stays silent, no clipping, faster than realtime |
| `test-lifecycle.sh` | install grants permissions and takes the Phone role; uninstall returns the role, uninstalls the package, wipes its data and finishes the job after the reboot |
| ABI gate | no dialer method/field used by the new code is missing from the APK |
| dex integrity | the patched dex differs from the base and contains exactly the spliced classes |
| module layout | scripts are executable, permission XMLs present, `module.prop` bumped, no CRLF, CRC clean |

## Building locally

```bash
# 1. engine tests (needs a JDK and android.jar)
ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar tools/voicefx/selftest.sh

# 2. install/uninstall simulation (needs root)
sudo tools/voicefx/test-lifecycle.sh

# 3. the module itself
python3 tools/voicefx/build_v16.py \
  --v14-zip  VoiceFX-Dialer-Module-v1.4.zip \
  --keystore voicefx-release.jks --ks-pass '…' --ks-alias voicefx \
  --out out
```

`build_v16.py` needs JDK + Android SDK build-tools (d8, zipalign, apksigner,
aapt2) and downloads smali/baksmali automatically. It refuses to build if the
base zip is not the released v1.4 one (md5 `937b24304ff29204073bb7ae813ed516`),
unless `--force` is given.

## Signing, and why it decides the install order

Android rejects an update whose signing certificate changed. The v1.4 key is not
in this repository, so:

* if the repository secrets `VOICEFX_KS_B64` / `VOICEFX_KS_PASS` are set, CI
  reuses that key and future releases upgrade in place;
* otherwise CI generates a key for the run and publishes it as the
  `VOICEFX-SIGNING-KEY-DOWNLOAD-AND-KEEP` artifact — then installing over an
  older build requires uninstall + reboot first.

Either way, uninstalling the module is complete: the package is removed, its
data is wiped, and the Phone role goes back to the app that held it before.

## The presets

| preset | register (from a 110 Hz voice) | character |
|---|---|---|
| Girl | +9.00 st → 185 Hz, tract +19 % | adult woman: less chest, 3.2 kHz presence, light breath, gentle compression |
| Agent (L) | −2.36 st → 96 Hz, tract −6 % | flat, band-limited, compressed, small room — the laptop-in-the-dark voice |
| Boy | −1.65 st → 100 Hz, tract −4 % | young man, slightly heavier |
| Child | +14.00 st → 247 Hz, tract +32 % | short tract, bright |
| Robot | 0 st | ring modulation, deliberately synthetic |

Pitch is aimed at an absolute fundamental, measured from the speaker's own
median F0 — not a blind offset. That measurement is remembered between calls, so
a new call starts in the right register instead of gliding there.
