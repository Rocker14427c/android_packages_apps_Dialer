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
import android.telecom.Call;
import android.util.Log;
import android.widget.Toast;

import com.android.dialer.R;

/**
 * In-call voice changer engine (BCP-style uplink injection, baked into the dialer).
 *
 * <p>While a call is ACTIVE and a non-normal preset is selected:
 * <pre>
 *   AudioRecord (VOICE_COMMUNICATION, HW AEC/NS) -> VoiceFxProcessor
 *   -> AudioTrack (USAGE_VOICE_COMMUNICATION) routed to the telephony
 *     output device (AudioDeviceInfo.TYPE_TELEPHONY) = the call uplink.
 * </pre>
 * The natural mic uplink is soft-muted via {@link Call#mute(boolean)} (voice path
 * only; the capture used above keeps running) and restored on stop.
 *
 * <p>Requires the MODIFY_PHONE_STATE + RECORD_AUDIO permissions the dialer already
 * carries as a privileged app, and a device whose audio stack implements the
 * telephony output device (e.g. MediaTek and Pixel-like AOSP implementations;
 * this is exactly what makes apps like BCP work on those devices).
 */
public final class VoiceFxController {

  private static final String TAG = "VoiceFxController";
  private static final int SAMPLE_RATE = 48000;
  private static final int CHUNK_FRAMES = SAMPLE_RATE / 100; // 10 ms slices
  private static final String WAKELOCK_TAG = "dialer:voicefx";

  private static VoiceFxController instance;

  public static synchronized VoiceFxController get() {
    if (instance == null) {
      instance = new VoiceFxController();
    }
    return instance;
  }

  private EngineThread engine;
  private Call mutedCall;
  private Call watchedCall;
  private Call.Callback watcher;
  private int activePreset = VoiceFxProcessor.PRESET_NONE;

  private VoiceFxController() {}

  /** Start (or restart) the engine for the given call + preset. UI thread only. */
  public synchronized void startForCall(Context context, int preset, Call telecomCall) {
    stopInternal(false);

    if (preset == VoiceFxProcessor.PRESET_NONE || telecomCall == null) {
      activePreset = VoiceFxProcessor.PRESET_NONE;
      return;
    }

    try {
      mutedCall = telecomCall;
      telecomCall.mute(true); // soft-mute the voice path; our capture keeps running
    } catch (Throwable t) {
      Log.w(TAG, "Call is not mutable; user should mute manually in the dialer", t);
    }

    // Self-cleanup when the call ends mid-effect.
    watchedCall = telecomCall;
    watcher = new Call.Callback() {
      @Override
      public void onStateChanged(Call call, int state) {
        if (state == Call.STATE_DISCONNECTING || state == Call.STATE_DISCONNECTED) {
          mainHandler().post(VoiceFxController.this::stop);
        }
      }
    };
    watchedCall.registerCallback(watcher);

    engine = new EngineThread(context.getApplicationContext(), preset);
    activePreset = preset;
    engine.start();
    Log.i(TAG, "Voice FX engine started, preset=" + preset);
  }

  /** Stop the engine (if running) and unmute the uplink. UI thread only. */
  public synchronized void stop() {
    stopInternal(true);
  }

  private void stopInternal(boolean unmute) {
    if (engine != null) {
      engine.cancel();
      // Do not block the UI: let the thread drain; track/record are released in its finally.
      engine = null;
    }
    if (watcher != null && watchedCall != null) {
      try {
        watchedCall.unregisterCallback(watcher);
      } catch (Throwable ignored) {
      }
    }
    watcher = null;
    if (unmute && mutedCall != null) {
      try {
        mutedCall.mute(false);
      } catch (Throwable ignored) {
      }
    }
    watchedCall = null;
    mutedCall = null;
    activePreset = VoiceFxProcessor.PRESET_NONE;
    Log.i(TAG, "Voice FX engine stopped");
  }

  private static android.os.Handler mainHandler() {
    return new android.os.Handler(android.os.Looper.getMainLooper());
  }

  public synchronized boolean isActive() {
    return engine != null && engine.isAlive();
  }

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

    @SuppressLint("MissingPermission")
    @Override
    public void run() {
      String failure = null;
      AudioRecord record = null;
      AudioTrack track = null;
      try {
        AudioManager am = context.getSystemService(AudioManager.class);
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

        final AudioFormat fmt = new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build();
        final AudioFormat fmtOut = new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build();

        int inBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int outBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (inBuf <= 0 || outBuf <= 0) {
          throw new Exception("Invalid audio buffer sizes: in=" + inBuf + " out=" + outBuf);
        }

        record = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(Math.max(inBuf * 2, CHUNK_FRAMES * 2 * 4))
            .build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
          throw new Exception("Failed to open microphone");
        }

        track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build())
            .setAudioFormat(fmtOut)
            .setBufferSizeInBytes(Math.max(outBuf * 2, CHUNK_FRAMES * 2 * 4))
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

        record.startRecording();
        track.play();
        Log.d(TAG, "Engine pipeline running");

        final short[] buf = new short[CHUNK_FRAMES];
        int presetNow = preset;
        while (!cancelled) {
          final int n = record.read(buf, 0, CHUNK_FRAMES);
          if (n <= 0) {
            Log.w(TAG, "AudioRecord.read -> " + n);
            continue;
          }
          // Live preset switching requires a configure on the audio thread; poll each slice.
          final int wanted = getActivePreset();
          if (wanted != presetNow) {
            processor.setPreset(wanted);
            presetNow = wanted;
          }
          processor.process(buf, n);
          track.write(buf, 0, n);
        }
      } catch (Exception e) {
        Log.e(TAG, "Voice FX engine error", e);
        failure = e.getLocalizedMessage();
      } finally {
        try { if (track != null) track.stop(); } catch (Throwable ignored) {}
        try { if (record != null) record.stop(); } catch (Throwable ignored) {}
        if (track != null) track.release();
        if (record != null) record.release();
        if (failure != null) {
          showFailure(failure);
        }
        // Thread exited: drop our reference so isActive() becomes false.
        synchronized (VoiceFxController.this) {
          if (engine == this) {
            engine = null;
            stopInternal(true);
          }
        }
      }
    }
  }

  private void showFailure(final String msg) {
    Context c = appContext;
    if (c != null) {
      mainHandler().post(() -> Toast.makeText(
          c, c.getString(R.string.voice_changer_failed, msg), Toast.LENGTH_LONG).show());
    }
  }

  private static Context appContext;

  /** Called from the presenter once so the controller can toast errors. */
  public static void attachContext(Context context) {
    appContext = context.getApplicationContext();
  }
}
