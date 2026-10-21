# AxionVoice Probe: call-audio test app (Shizuku, no root, no system install)

A plain APK that answers one question on your own phone before the voice changer and
call recorder are built on top of it:

1. **Does call-audio capture work during a cellular call** from a non-system app's
   helper (the probe's shell process)?
2. **Does the Dialer's mute (InCallService.setMuted) silence that capture**, or only the
   other party's side? This is the assumption the VoiceFX design rests on.
3. **Does a telephony-routed output reach the call** (the uplink path the voice changer
   would use)?
4. **Does a 10 s call-audio recording (VOICE_CALL) contain both sides?**

It does not change anything on the phone except during the tests you start.

## Why it is built this way

The probe's shell process runs under the ADB shell identity (uid 2000). Shell holds
`MODIFY_PHONE_STATE`, `CAPTURE_AUDIO_OUTPUT`, `CALL_AUDIO_INTERCEPTION` and
`MODIFY_AUDIO_ROUTING`, and `com.android.shell` is a privileged package
(`privapp-permissions-platform.xml`, `packages/Shell/Android.bp: privileged: true`).
Shizuku starts a service class in that process on request, so the APK itself needs no
root and no system install.

Source checks behind this design (AOSP / LineageOS lineage-24.0):

* `VOICE_CALL` capture needs `CALL_AUDIO_INTERCEPTION` or `CAPTURE_AUDIO_OUTPUT`
  (`AudioPolicyInterfaceImpl`, `checkPermissionForInput`).
* The telephony output route needs `MODIFY_PHONE_STATE` (`API_OUTPUT_TELEPHONY_TX`).
* While the phone is in `IN_CALL` mode, a recorder without the bypass permission is not
  allowed to capture (`AudioPolicyService`, `canCaptureIfInCallOrCommunication`). The
  shell identity has the permission, so it is not blocked.
* Telecom's call mute ends in `AudioManager.setMicrophoneMute()` on the same service the
  probe calls directly (`CallAudioRouteController.handleMuteChanged`). Whether that also
  silences the probe's capture is the open question the test answers.

## Requirements

* Android 13 or newer (API 33+).
* Shizuku 13.x installed and running. On Android 11+ you can start it without a PC using
  Wireless debugging (see Shizuku's own guide). Restart it after every reboot.
* Two phones or one phone plus someone who can call you, for the call tests.

## Install

1. Download `AxionVoiceProbe.apk` from the `probe-v1-shizuku` pre-release and install it
   (allow "install unknown apps" for your browser or file manager).
2. Start Shizuku. Open **AxionVoice Probe**.
3. Tap **Grant Shizuku** and allow it. The status bar shows `Shizuku: running, permission granted`.
4. Tap **Start probe**. The log should show `START uid=2000 ... ctxPkg=com.android.shell`.
   If it shows `uid=2000`, the shell identity is confirmed.

## Test plan

Run the steps in order. Each step says what to expect. Write down what you actually see.

| # | Do this | Expected if the design works | If it does not |
|---|---|---|---|
| A | With no call, tap **Monitor: call uplink**, then speak near the phone. | `RMS ... dbfs` rises well above -50 when you speak. | `OPEN failed` or `READ error` lines show the capture is refused. Note the line. |
| B | Tap **Make default phone app** and accept. | Status shows `Default phone app: yes`. | Role refused: the mute test cannot run. |
| C | Call the phone from another phone (or call the other phone from this one). Answer. | `CALL ... ACTIVE`, `telecom audio muted=false`. RMS keeps reporting. | Note whether the call connects and whether RMS drops to SILENT during the call. |
| D | During the call, speak. Then stay silent for 3 s. | RMS follows your voice during the call (captured from the uplink). | RMS stays SILENT during the call, so capture is blocked in-call. |
| E | Tap **Mute (InCallService)**. Stay silent for a moment, then speak. Tap again to unmute. | `telecom audio muted=true` appears in the log. **Key result:** if RMS drops to SILENT while muted, Telecom's mute also silences the capture. If RMS keeps following your voice, the capture is independent of the mute. Write down which. | Mute button disabled: the InCallService is not bound (step B). |
| F | Tap **Tone via telephony route**, with the call active and not muted. | `TONE telephony output present=true`, `setPreferredDevice(telephony)=true`, `routed=TELEPHONY`. **The other party should hear a 880 Hz beep.** | `present=false` or `setPreferredDevice=false`: that route is not available on this build. Report the exact lines. |
| G | Tap **Record 10 s call audio** during an active call. Speak, have the other party speak, then wait for `REC done`. | `REC done bytes=...`, file in `/sdcard/Download/AxionVoiceProbe/`. Play it: both your voice and the other party's should be audible. | Silent file or `REC ERROR`: record the line. |
| H | Hang up. Tap **Make default phone app** again and pick your normal dialer. | Normal dialer restored. | Restore it from Settings > Apps > Default apps > Phone app. |

For step E, the timestamps let you check the order: `[app] MUTE InCallService.setMuted(true)`
then `[telecom] telecom audio muted=true`, then the RMS lines.

Use **Copy log** and send the log with your answers to A through H.

## Notes and limits

* The probe makes itself the default phone app during the tests, which replaces the
  stock in-call screen. Restore your dialer after testing (step H).
* Recordings go to `/sdcard/Download/AxionVoiceProbe/` as 16-bit mono 48 kHz WAV.
* Recording calls is subject to the consent rules that apply where you are. Get the
  other party's agreement before recording a conversation.
* The probe was built and checked against AOSP source only. It has not been run on a
  phone by the author, so these tests are the first real evidence.

## Build

```bash
cd tools/voicefx/probe
gradle :app:assembleRelease     # needs JDK 17, Android SDK platform 35, build-tools 35.0.0
```

CI builds and publishes it: `.github/workflows/build-voicefx-probe.yml` (tag `voicefx-probe-*`).
