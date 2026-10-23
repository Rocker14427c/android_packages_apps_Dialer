# Handoff: AxionVoice call recorder / voice changer (probe stage)

Read this whole file first. It is the state of the work as of probe version 4
(commit `efc7564`, tag `voicefx-probe-10`). Do not repeat the tests listed under
"Verified" unless you have a reason; they are already proven on the user's phone.

## 1. Goal (from the user)

- Make VoiceFX-style features work in a **normal, non-system APK** (no root, no
  system install) on the user's phone:
  1. **Call recording** (record both sides of a cellular call to WAV).
  2. **Voice changer** (the other person hears a modified voice of the user).
- The user chose a **probe first** approach: a diagnostic APK that proves what the
  phone allows, before building the real app on verified facts.
- The repo is `Rocker14427c/android_packages_apps_Dialer` (AOSP Dialer fork,
  LineageOS base). The existing VoiceFX v1.6 is a **root-only Magisk/KSU module**
  (`tools/voicefx/`, workflow `build-voicefx-v16.yml`). The user does not want root
  or a system install.

## 2. Who the user is and how to talk to them

- Not a developer. Wants **very simple, numbered instructions** for testing on the
  phone. Keep steps short.
- Tests on their own phone during real calls with another phone. They send
  **Copy log** output as text in chat (sometimes as `log.txt`).
- Earlier they asked for: no bugs in the test app ("otherwise the diagnosis is not
  fair"), the simplest reliable approach, and no root.
- They are frustrated by repeated setup problems. Be direct: say what is proven, what
  is not, and what the next step costs.

## 3. Device and environment facts

- Phone: realme RMX3430, build `BP4A.251205.006`, Android 16 (SDK 36), not rooted.
  User describes it as Axion OS.
- Shizuku (Wireless debugging) must be running; use Shizuku 13.x.
- **Sandbox limits:** no Android SDK, no Gradle, no Java build. Google Maven and
  `services.gradle.org` are blocked. **Only GitHub Actions can build the APK.**
  - Release assets cannot be downloaded from the sandbox (CDN blocked). Verify builds
    through the workflow's **check-run annotations** (`gh api .../check-runs/<id>/annotations`).
  - `gh run view --log` is also blocked. Use annotations (`::notice::`/`::error::`).
- Allowed hosts: github.com, api.github.com, codeload.github.com, registry.npmjs.org,
  pypi.org, files.pythonhosted.org.
- The branch name is fixed: `arena/07a57e7a-android-packages-apps-dialer`. Work and push
  only there. Never switch branches.

## 4. Git pitfalls (IMPORTANT, this happened 3 times)

The local checkout of this branch was **reset to the base commit `8423f23`** between
turns (the environment restored an old snapshot). When that happens:
- `git push` is rejected as non-fast-forward.
- Untracked probe files still exist locally but are not in history.
- A tag pushed to the wrong commit triggers a broken build.

**Procedure to check and repair (do this before every push):**
```bash
cd /home/user/android_packages_apps_Dialer
git fetch origin
git status -sb && git log --oneline -1 HEAD && git log --oneline -1 origin/arena/07a57e7a-android-packages-apps-dialer
```
If local HEAD is not the same as (or an ancestor of) `origin/arena/...`:
```bash
B=arena/07a57e7a-android-packages-apps-dialer
cp -a tools/voicefx/probe /home/user/scratch/backupN/probe        # keep working copy
cp -a .github/workflows/build-voicefx-probe.yml /home/user/scratch/backupN/
rm -rf tools/voicefx/probe
git reset --hard origin/$B
rm -rf tools/voicefx/probe                                        # avoid nested copy
cp -a /home/user/scratch/backupN/probe tools/voicefx/probe
cp /home/user/scratch/backupN/build-voicefx-probe.yml .github/workflows/
git diff --stat   # must show only the files you changed on purpose
```
- Never `cp -a` into an existing directory (it nests: `probe/probe`).
- Tags must point at commits that are on the remote branch. If you push a tag by mistake,
  `git push origin --delete <tag>` and `git tag -d <tag>`, then re-tag.
- Use a **new tag number** for each build: `voicefx-probe-N`. The workflow triggers on
  `voicefx-probe-*`.

Git identity is already set in the repo (`arena-agent`). Remote is `origin`.

## 5. Probe project layout

Path: `tools/voicefx/probe/` (standalone Gradle project, not part of AOSP Soong).

- `app/src/main/AndroidManifest.xml`: `MainActivity` (MAIN/LAUNCHER and two `DIAL`
  intent filters, required for the dialer role), `ProbeInCallService` (InCallService,
  `BIND_INCALL_SERVICE`, `IN_CALL_SERVICE_UI`), and `rikka.shizuku.ShizukuProvider`
  (required; the Shizuku library does not declare it).
- `app/src/main/aidl/com/axionvoice/probe/IProbeService.aidl`: binder API between app
  and shell service (`hello`, `setMonitorSource`, `playTone`, `recordCall`, `drainLog`,
  `setMicMuteViaAudioManager`, `destroy`, `exit`).
- `app/src/main/java/com/axionvoice/probe/ProbeService.java`: runs as **uid 2000
  (shell)** inside a Shizuku-started process (`com.axionvoice.probe:probe`).
  - `captureLoop()`: one `AudioRecord` at a time; monitor source switches; RMS every
    ~250 ms; WAV writer for `recordCall`.
  - `ShellAttributionContext` (a `ContextWrapper`): reports package `com.android.shell`
    so AudioFlinger accepts uid 2000. Used for both `AudioRecord.Builder.setContext`
    and `AudioTrack.Builder.setContext`.
  - `tone()`: `AudioTrack`, `USAGE_VOICE_COMMUNICATION`, `setPreferredDevice(TYPE_TELEPHONY)`, 880 Hz, 1.5 s.
  - `dumpAudioLogs()`: on first audio failure, reads `logcat -d` filtered for audio lines
    and writes them to the probe log with `LOGCAT` prefix. This is how the real native
    error was found.
- `app/src/main/java/com/axionvoice/probe/ProbeState.java`: shared static state and log queue.
- `app/src/main/java/com/axionvoice/probe/ProbeInCallService.java`: logs Telecom call
  state and mute/route; `setMuted` is used by the InCall mute button.
- `app/src/main/java/com/axionvoice/probe/MainActivity.java`: programmatic UI, 300 ms log
  drain, `PROBE_VERSION` constant (currently **4**). Bumping this makes Shizuku recreate
  the shell service (`.version(PROBE_VERSION)`).
- `README.md`: install steps and the A–H test plan for the user.
- `.github/workflows/build-voicefx-probe.yml`: triggers on tag `voicefx-probe-*`
  (and workflow_dispatch). Builds with JDK 17, Gradle 8.9, AGP 8.7.3, compileSdk 35.
  Verifies the APK (aapt2, apksigner, merged manifest must contain InCallService,
  Shizuku provider, `android.intent.action.DIAL`). Publishes each build as
  `AxionVoiceProbe-<short sha>.apk` to the pre-release `probe-v1-shizuku` (the "v1"
  is only the series name; the version is in the app's log line).
- Signing: a new key is generated **per build** (secrets `PROBE_KS_B64`/`PROBE_KS_PASS`
  are not set). The user must **uninstall the old probe** before installing a new one.

Dependencies: `dev.rikka.shizuku:api:13.1.5`, `dev.rikka.shizuku:provider:13.1.5`.
`minSdk 33`, `targetSdk 35`.

### How to ship a build (the normal loop)

1. Make the change, run the checklist in section 4.
2. `git add tools/voicefx/probe .github/workflows/build-voicefx-probe.yml`
3. `git commit -m "..."`, then `git push origin arena/07a57e7a-android-packages-apps-dialer`
4. `git tag voicefx-probe-N && git push origin voicefx-probe-N`
5. Wait: `gh run list --repo Rocker14427c/android_packages_apps_Dialer --workflow "Build AxionVoice Probe (Shizuku diagnostic APK)" --limit 1`
   then `gh run watch <id> --exit-status`.
6. Read annotations for the new commit: `gh api repos/.../commits/<sha>/check-runs` → `annotations`.
7. Read release assets: `gh release view probe-v1-shizuku --json assets,body`.
8. Give the user the **exact** download URL for the new file name and its SHA-256
  (from the release body line `SHA-256:`), plus "uninstall the old probe first" and
  "the first log line must say Probe version N".

## 6. Verified facts (from the user's phone logs)

All of these are proven on the user's phone with probe builds 3–4.

| # | Fact | Evidence |
|---|------|----------|
| V1 | Shizuku binds a shell-uid service from our APK. Needs `processNameSuffix(...)`, `ShizukuProvider` in manifest. | `PROBE identity: uid=2000 ... ctxPkg=com.android.shell` |
| V2 | The app can become the default phone app (dialer role) only if it declares the two `DIAL` intent filters. | `ROLE result=-1 ... default phone app=true` |
| V3 | InCallService answer/hangup/mute works; Telecom state and route are logged. | `[telecom] call state -> ACTIVE`, `telecom audio muted=...` |
| V4 | Mic capture (VOICE_COMMUNICATION, BUILTIN_MIC) works unmuted, during and outside calls. Speech is about -20 to -45 dBFS; room floor about -70 to -80 dBFS. | RMS lines |
| V5 | `AudioRecord` works only with the shell attribution (see section 7, A1). | `OPEN source=... state=1` |
| V6 | Call recording: `VOICE_CALL` source, 10 s, 48 kHz, mono, 16-bit WAV written to `/sdcard/Download/AxionVoiceProbe/` (960,000 bytes). Unmuted, signal present; user reports normal sound. | `REC done bytes=960000` |
| V7 | Tone: `AudioTrack` (USAGE_VOICE_COMMUNICATION), `setPreferredDevice(TYPE_TELEPHONY)`, routed to `TELEPHONY(type=18)`. 1.5 s 880 Hz beep is heard by the other person, **also while muted** (user confirmed). | `TONE done frames=72000 routed=TELEPHONY` |
| V8 | Mute (both `InCallService.setMuted` and `AudioManager.setMicrophoneMute`) silences the **probe's own capture** completely: `-96` to `-102` dBFS on VOICE_COMMUNICATION; `-120` dBFS on VOICE_CALL recording and VOICE_UPLINK. | logs 15:20, 19:22 |
| V9 | While muted, **VOICE_DOWNLINK** still captures the other person's voice (about -15 to -50 dBFS). Downlink is capturable while muted. | log 19:22 |
| V10 | Muted `UNPROCESSED` capture is also silent (`-120`). | log 19:22 |

Note on V8: one call (log 12:08, second call of that session) showed AudioManager mute
at about -63 to -73 dBFS (not digital zero). It is unexplained. Repeat it before relying on it.

## 7. Root causes already solved (do not re-investigate)

- **A1. AudioRecord/AudioTrack "Cannot create ...".** AudioFlinger validates the
  attribution source. For uid 2000 the package must be `com.android.shell`
  (`NativePermissionController.validateUidPackagePair` uses the fixed package for shell).
  `context.createPackageContext("com.android.shell")` **does not** change the op
  package (AOSP `ContextImpl` copies `container.mOpPackageName`), so the app's own
  package was used and rejected ("invalid attr", found in logcat). Fix: `ShellAttributionContext`
  overrides `Context.getAttributionSource()`.
- **A2. Dialer role never offered.** Fixed by the two `DIAL` filters (Permission
  module `roles.xml`, `DIALER` `required-components`).
- **A3. Shizuku bind NPE.** Fixed by `processNameSuffix("probe")` (Shizuku 13.x requires it).
- **A4. Provider missing.** Fixed by declaring `rikka.shizuku.ShizukuProvider` in the manifest.
- **A5. Old APK installed by mistake.** Fixed by per-commit file names in the release.

## 8. Ruled out (do not retry)

- **`adb`/`pm grant` of MODIFY_PHONE_STATE, CAPTURE_AUDIO_OUTPUT, etc.:** not allowed for
  non-runtime permissions (AOSP `PermissionService`). Also not needed: the Shizuku service
  already runs as shell (uid 2000), which holds these permissions.
- **Plain app capturing VOICE_CALL / VOICE_UPLINK / VOICE_DOWNLINK:** needs
  `CAPTURE_AUDIO_OUTPUT` or `CALL_AUDIO_INTERCEPTION` (system permissions). Only the shell
  identity (through Shizuku) has them.
- **Choosing a different capture source to get past mute:** MIC-type sources
  (VOICE_COMMUNICATION, UNPROCESSED, VOICE_UPLINK, VOICE_CALL) are all silent while
  muted (V8, V10). Note: plain `MIC` was not tested while muted.
- **Shizuku `newProcess`** (run a shell command/`app_process` directly): private in
  Shizuku 12.1.0 and master.
- **Command-line `app_process` as shell:** would not help. AudioFlinger still needs the
  package `com.android.shell` for uid 2000, and the "uid:<n>" path is rejected.

## 9. The core blocker for the voice changer

The voice-changer design needs three steps:
1. **Hear the user's raw voice while muted.** **FAILS** (V8, V10).
2. Process the voice (pitch/formant change) in the app.
3. **Send the processed voice** through the telephony route. **WORKS** (V7, also while muted).

Without step 1 there is no input to process. The alternative, leaving the mic unmuted,
sends the raw voice too, so it is not a voice changer.

Therefore **the voice changer cannot work with the current approach on this phone.** The
probe showed this clearly. Do not implement the full voice changer on the current approach
before a new experiment shows a capture path that stays live while muted.

## 10. Untested ideas (candidates for the next agent)

Ranked by likely value for the user. Each needs a probe build and a user test.

1. **AudioPolicy call capture.** `AudioManager.registerAudioPolicy(...)` with an
   `AudioMix` on call uplink/downlink (`MIX_ROUTE_FLAG_LOOP_BACK` or capture mix).
   The shell holds `MODIFY_AUDIO_ROUTING` and `CALL_AUDIO_INTERCEPTION`, which is how
   system call-audio tools work. **Unknown:** whether the mute is applied before or after
   this mix. It is most likely applied at the HAL input (AOSP `AudioFlinger::setMicMute` →
   HAL primary), so this probably fails too. It is still the only untested route to the
   uplink. Test: register a policy in the probe, mute, speak, read RMS from the policy's
   capture stream.
2. **Plain `MIC` source while muted.** Quick check; add a monitor button for
   `MediaRecorder.AudioSource.MIC` (= 1) and repeat test V8.
3. **Repeat the AudioManager mute** to explain the residual level in the 12:08 log (V8 note).
4. **Attribution on other sources** (VOICE_RECOGNITION, CAMCORDER) while muted. Low expectation.

If all of these fail, the honest outcome is: **call recording is possible as a normal app
with Shizuku; a voice changer that hides the user's real voice is not possible without root
or a system install.**

## 11. Options the user must decide

- **A. Stop the voice changer. Ship the call recorder as a normal app** (Shizuku needed).
  Feasible and verified (V1, V5, V6). Needs the probe code turned into a real app
  (Gradle project, UI, storage, consent notice). Recording only the user's side is unmuted
  capture; both sides come from `VOICE_CALL`.
- **B. Try idea 10.1 (AudioPolicy) first.** One more probe build and one user test.
- **C. Root or system install** (the existing VoiceFX v1.6 module, `tools/voicefx/`).
  Works, but the user has said they want to avoid it.

The user has not decided yet. Ask before large work.

## 12. Repo locations of other useful things

- `tools/voicefx/` (root-only VoiceFX module from earlier work; keep unchanged unless asked).
- `java/com/android/incallui/voicefx/` and `java/com/android/dialer/callrecord/` (Dialer
  code for the existing VoiceFX; the probe does not depend on them).
- The Dialer itself builds as a system app (Soong `Android.bp`). A normal APK needs a
  separate Gradle project. This is a large job; decide on it only after section 11.

## 13. Reference sources (fetched earlier; may be gone in a new sandbox)

These are on GitHub. Fetch with `gh api` (branch `lineage-24.0` in LineageOS mirrors):
- `LineageOS/android_frameworks_base`: `media/java/android/media/AudioRecord.java`,
  `media/java/android/media/AudioTrack.java`, `core/java/android/content/Context.java`,
  `core/java/android/app/ContextImpl.java`.
- `LineageOS/android_frameworks_av`: `media/libaudiopermission/ValidatedAttributionSourceState.cpp`,
  `media/libaudiopermission/NativePermissionController.cpp`.
- `LineageOS/android_packages_modules_Permission`: `PermissionController/res/xml/roles.xml`
  (DIALER role), `role-controller/.../DialerRoleBehavior.java`.
- `RikkaApps/Shizuku-API`: `api/src/main/java/rikka/shizuku/Shizuku.java` (UserServiceArgs,
  `processNameSuffix`), `provider/src/main/AndroidManifest.xml` (no provider entry; app must add it).

## 14. Test plan the user already has

`tools/voicefx/probe/README.md`: steps A–H (monitor without call, default dialer role,
place/answer call, RMS during call, mute test, tone, record 10 s, restore dialer).
A–H are answered for the current approach (see section 6). Any new experiment should get
its own short numbered steps for the user, written the same simple way.

## 15. Addendum: the root module uses the same mute-then-inject design

Checked after the handoff was written (sources: `tools/voicefx/`, `java/com/android/incallui/voicefx/VoiceFxController.java`, AOSP/LineageOS `AudioFlinger.cpp`):

- The root VoiceFX module (`VoiceFxController`) runs the same pipeline as the probe:
  `AudioRecord(VOICE_COMMUNICATION)` → DSP → `AudioTrack` to `TYPE_TELEPHONY`. It mutes the
  natural uplink with `TelecomAdapter.mute(true)`, the same call as the Mute button.
  Its comments assume the mute does **not** silence its own capture.
- `AudioFlinger::setMicMute` (frameworks/av, `services/audioflinger/AudioFlinger.cpp`)
  requires `MODIFY_AUDIO_SETTINGS`, then sets the mute on the HAL devices. It has **no
  per-client exception**. Privileged apps get no special treatment at this layer.
- Our logs (V8) show the mute silencing capture on this device. So the root module would
  most likely also silence its own capture here. This is unverified: the module has not been
  run on this phone, and the user has no root.

What root changes is **who runs the code** (a priv-app inside the Dialer, with no Shizuku
needed) and what can be modified in the system. It does not change the mute behaviour.
Any next experiment must be judged against this, not against the module's assumptions.
