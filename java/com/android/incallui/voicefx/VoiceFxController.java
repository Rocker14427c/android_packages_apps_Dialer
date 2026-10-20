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

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.telecom.CallAudioState;
import android.util.Log;
import android.widget.Toast;

import com.android.dialer.R;
import com.android.incallui.audiomode.AudioModeProvider;
import com.android.incallui.call.TelecomAdapter;

/**
 * In-call voice changer engine (BCP-style uplink injection, baked into the dialer).
 *
 * <p>While a call is ACTIVE and a non-normal preset is selected:
 * <pre>
 *   AudioRecord (VOICE_COMMUNICATION, 48 kHz, HW AEC/NS) -> VoiceFxProcessor
 *   -> AudioTrack (USAGE_VOICE_COMMUNICATION) routed to the telephony
 *      output device (AudioDeviceInfo.TYPE_TELEPHONY) = the call uplink.
 * </pre>
 * The natural mic uplink is soft-muted with the exact same call the dialer's Mute
 * button uses ({@link TelecomAdapter#mute} -> InCallService.setMuted): it silences
 * only the telephony voice path, so the capture above keeps working and only the
 * CHANGED voice reaches the other party. The previous mute state is saved and
 * restored, so a pre-existing user mute is never clobbered.
 *
 * <p>Requires the MODIFY_PHONE_STATE + RECORD_AUDIO permissions the dialer already
 * carries as a privileged app, and a device whose audio stack implements the
 * telephony output device (MediaTek and Pixel-like AOSP implementations; the same
 * prerequisite as the "BCP" app).
 */
public final class VoiceFxController {

  private static final String TAG = "VoiceFxController";
  private static final int SAMPLE_RATE = 48000;
  private static final int CHUNK_FRAMES = SAMPLE_RATE / 100; // 10 ms slices
  private static final String WAKELOCK_TAG = "dialer:voicefx";
  private static final long WAKELOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L; // 4 h cap

  private static VoiceFxController instance;
  private static Context appContext;

  public static synchronized VoiceFxController get() {
    if (instance == null) {
      instance = new VoiceFxController();
    }
    return instance;
  }

  /** Called by the presenter once so the controller can toast errors on the UI thread. */
  public static void attachContext(Context context) {
    appContext = context.getApplicationContext();
  }

  private EngineThread engine;
  private int activePreset = VoiceFxProcessor.PRESET_NONE;
  /** True only when WE enabled the mute (not pre-muted by the user). */
  private boolean mutedByUs;

  private VoiceFxController() {}

  /** Start (or restart) the engine with the given preset. UI thread only. */
  public synchronized void start(Context context, int preset) {
    stopInternal(false /* keepMute */);

    if (preset == VoiceFxProcessor.PRESET_NONE) {
      activePreset = VoiceFxProcessor.PRESET_NONE;
      return;
    }

    // Save current mute state, then silence the natural uplink so ONLY the
    // processed voice reaches the remote party. mute ownership (mutedByUs) is
    // deliberately NOT reset here: on a preset swap our mute from the previous
    // engine is still in effect and we must keep remembering that WE own it,
    // so the eventual stop() restores the user's original state.
    CallAudioState audioState = AudioModeProvider.getInstance().getAudioState();
    boolean alreadyMuted = audioState != null && audioState.isMuted();
    if (!alreadyMuted) {
      TelecomAdapter.getInstance().mute(true);
      mutedByUs = true;
    }

    engine = new EngineThread(context.getApplicationContext(), preset);
    activePreset = preset;
    engine.start();
    Log.i(TAG, "Voice FX engine started, preset=" + preset + ", mutedByUs=" + mutedByUs);
  }

  /** Stop the engine (if running) and restore the uplink mute as it was. UI thread only. */
  public synchronized void stop() {
    stopInternal(true /* restoreMute */);
  }

  private void stopInternal(boolean restoreMute) {
    if (engine != null) {
      engine.cancel();
      // The thread releases AudioTrack/AudioRecord itself in its finally block.
      engine = null;
    }
    if (restoreMute && mutedByUs) {
      TelecomAdapter.getInstance().mute(false);
      mutedByUs = false;
    }
    activePreset = VoiceFxProcessor.PRESET_NONE;
    Log.i(TAG, "Voice FX engine stopped, mute restored=" + restoreMute);
  }

  public synchronized boolean isActive() {
    return engine != null && engine.isAlive();
  }

  /** Current engine preset, or PRESET_NONE when the engine is not running. */
  public synchronized int getActivePreset() {
    return isActive() ? activePreset : VoiceFxProcessor.PRESET_NONE;
  }

  // ---------------------------------------------------------------------------

  private final class EngineThread extends Thread {

    private final Context context;
    private final int preset;
    private volatile boolean cancelled;

    EngineThread(Context context, int preset) {
      super("VoiceFxEngine");
      this.context = context;
      this.preset = preset;
    }

    void cancel() {
      cancelled = true;
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is held by the dialer itself
    @Override
    public void run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

      String failure = null;
      AudioRecord record = null;
      AudioTrack track = null;
      PowerManager.WakeLock wakeLock = null;
      try {
        final AudioManager am = context.getSystemService(AudioManager.class);

        AudioDeviceInfo telephonyOut = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
          if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
            telephonyOut = d;
            break;
          }
        }
        if (telephonyOut == null) {
          throw new Exception(context.getString(R.string.voice_changer_no_telephony_output));
        }

        final AudioFormat fmtIn = new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build();
        final AudioFormat fmtOut = new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build();

        final int inBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final int outBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (inBuf <= 0 || outBuf <= 0) {
          throw new Exception("Invalid audio buffer sizes: in=" + inBuf + " out=" + outBuf);
        }

        record = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(fmtIn)
            .setBufferSizeInBytes(Math.max(inBuf * 4, CHUNK_FRAMES * 2 * 4))
            .build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
          throw new Exception("Failed to open microphone");
        }

        track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build())
            .setAudioFormat(fmtOut)
            .setBufferSizeInBytes(Math.max(outBuf * 4, CHUNK_FRAMES * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
          throw new Exception("Failed to open telephony output track");
        }
        if (!track.setPreferredDevice(telephonyOut)) {
          throw new Exception("Failed to route to telephony output device");
        }

        final VoiceFxProcessor processor = new VoiceFxProcessor(SAMPLE_RATE);
        processor.setPreset(preset);

        final PowerManager pm = context.getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);

        record.startRecording();
        track.play();
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
          throw new Exception("AudioTrack failed to start");
        }
        Log.d(TAG, "Engine pipeline running (chunk=" + CHUNK_FRAMES + " frames)");

        final short[] buf = new short[CHUNK_FRAMES];
        while (!cancelled) {
          final int n = record.read(buf, 0, CHUNK_FRAMES);
          if (n <= 0) {
            Log.w(TAG, "AudioRecord.read -> " + n);
            continue;
          }
          processor.process(buf, n);
          track.write(buf, 0, n);
        }
      } catch (Exception e) {
        Log.e(TAG, "Voice FX engine error", e);
        if (!cancelled) {
          failure = e.getLocalizedMessage();
        }
      } finally {
        try { if (record != null) record.stop(); } catch (Throwable ignored) {}
        try { if (track != null) track.stop(); } catch (Throwable ignored) {}
        if (track != null) track.release();
        if (record != null) record.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        if (!cancelled) {
          // The engine died on its own (error or call end tore the pipe down):
          // tidy up the controller (restores mute) back on the UI thread.
          new Handler(Looper.getMainLooper()).post(VoiceFxController.this::stop);
          if (failure != null) showFailure(failure);
        }
        synchronized (VoiceFxController.this) {
          if (engine == this) {
            engine = null;
            activePreset = VoiceFxProcessor.PRESET_NONE;
          }
        }
      }
    }
  }

  private static void showFailure(String msg) {
    if (appContext != null) {
      new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
          appContext, appContext.getString(R.string.voice_changer_failed, msg),
          Toast.LENGTH_LONG).show());
    }
  }
}
