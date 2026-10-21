package com.axionvoice.probe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Queue;

/**
 * Diagnostic service that Shizuku runs as the ADB shell user (uid 2000).
 *
 * <p>Shell holds MODIFY_PHONE_STATE, CAPTURE_AUDIO_OUTPUT, CALL_AUDIO_INTERCEPTION and
 * MODIFY_AUDIO_ROUTING (see privapp-permissions-platform.xml), which are the permissions the
 * voice changer and call recorder need. This class only measures and reports. It does not
 * change any call state except when the user asks for a tone, a recording or a mute.
 *
 * <p>Output is a timestamped line log (HH:mm:ss.SSS, wall clock) that the app polls with
 * {@link #drainLog()} and merges with its own events, so the mute press and the level drop can
 * be lined up on one timeline.
 */
public class ProbeService extends IProbeService.Stub {
  private static final String TAG = "AxionProbe";
  static final int RATE = 48000;
  /** 10 ms at 48 kHz. */
  private static final int FRAME = 480;
  private static final int LOG_CAP = 4000;
  private static final String OUT_DIR = "/sdcard/Download/AxionVoiceProbe";

  private final Context context;
  private final AudioManager audio;
  private final Object logLock = new Object();
  private final Queue<String> log = new ArrayDeque<>();
  private final Thread captureThread;

  private volatile int monitorSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
  /** Name of a pending call recording, or null. Set by recordCall, cleared when the file is closed. */
  private volatile String recordName = null;
  private volatile int recordSeconds = 0;
  private volatile boolean running = true;

  /** Shizuku calls this constructor (Shizuku API 13+). */
  public ProbeService(Context context) {
    this.context = shellContext(context);
    this.audio = this.context.getSystemService(AudioManager.class);
    line("START uid=" + android.system.Os.getuid() + " pid=" + android.system.Os.getpid()
        + " ctxPkg=" + this.context.getPackageName() + " sdk=" + Build.VERSION.SDK_INT
        + " device=" + Build.MANUFACTURER + " " + Build.MODEL + " build=" + Build.DISPLAY);
    captureThread = new Thread(this::captureLoop, "probe-capture");
    captureThread.setDaemon(true);
    captureThread.start();
  }

  /**
   * Audio services check that the package name belongs to the calling uid. Shizuku's default
   * context carries this app's package, which would not match uid 2000. The shell package is
   * special-cased by AppOps, so attribute all audio calls to it.
   */
  private static Context shellContext(Context base) {
    try {
      return base.createPackageContext("com.android.shell", 0);
    } catch (Exception e) {
      Log.w(TAG, "could not create shell context, using service context", e);
      return base;
    }
  }

  // ---------------------------------------------------------------- binder API

  @Override
  public String hello() {
    return "uid=" + android.system.Os.getuid() + " pid=" + android.system.Os.getpid()
        + " ctxPkg=" + context.getPackageName() + " sdk=" + Build.VERSION.SDK_INT;
  }

  @Override
  public void setMonitorSource(int code) {
    int src;
    switch (code) {
      case 1: src = MediaRecorder.AudioSource.MIC; break;
      case 2: src = MediaRecorder.AudioSource.VOICE_CALL; break;
      case 3: src = MediaRecorder.AudioSource.UNPROCESSED; break;
      default: src = MediaRecorder.AudioSource.VOICE_COMMUNICATION; break;
    }
    monitorSource = src;
    line("MONITOR source=" + srcName(src));
  }

  @Override
  public void playTone(int durationMs) {
    final int ms = Math.max(200, Math.min(durationMs, 5000));
    Thread t = new Thread(() -> tone(ms), "probe-tone");
    t.setDaemon(true);
    t.start();
  }

  @Override
  public void recordCall(int seconds, String fileName) {
    if (recordName != null) {
      line("REC busy: a recording is already running");
      return;
    }
    recordSeconds = Math.max(1, Math.min(seconds, 120));
    recordName = sanitize(fileName);
  }

  @Override
  public String drainLog() {
    StringBuilder sb = new StringBuilder();
    synchronized (logLock) {
      String s;
      while ((s = log.poll()) != null) {
        sb.append(s).append('\n');
      }
    }
    return sb.toString();
  }

  @Override
  public void setMicMuteViaAudioManager(boolean mute) {
    try {
      audio.setMicrophoneMute(mute);
      line("MUTE AudioManager.setMicrophoneMute(" + mute + ") ok, isMicrophoneMute="
          + audio.isMicrophoneMute());
    } catch (SecurityException e) {
      line("MUTE AudioManager.setMicrophoneMute denied: " + e.getMessage());
    } catch (RuntimeException e) {
      line("MUTE AudioManager.setMicrophoneMute failed: " + e);
    }
  }

  @Override
  public void exit() {
    destroy();
  }

  @Override
  public void destroy() {
    running = false;
    line("DESTROY");
    // Let the binder reply go out before the process ends.
    Thread t = new Thread(() -> {
      try {
        Thread.sleep(200);
      } catch (InterruptedException ignored) {
      }
      System.exit(0);
    });
    t.setDaemon(true);
    t.start();
  }

  // ---------------------------------------------------------------- capture

  /** Keeps one AudioRecord open and reports RMS every 250 ms. Owns all capture. */
  private void captureLoop() {
    short[] buf = new short[FRAME];
    AudioRecord rec = null;
    int openSrc = -1;
    WavWriter wav = null;
    long wavLeft = 0;
    double sumSq = 0;
    long n = 0;
    int peak = 0;
    int errors = 0;

    while (running) {
      try {
        String rn = recordName;
        int want = rn != null ? MediaRecorder.AudioSource.VOICE_CALL : monitorSource;
        if (rec == null || want != openSrc) {
          if (rec != null) {
            rec.release();
            rec = null;
          }
          rec = openRecord(want);
          openSrc = want;
          if (rec == null) {
            Thread.sleep(1000);
            continue;
          }
        }

        if (rn != null && wav == null) {
          try {
            File f = new File(OUT_DIR, rn + ".wav");
            wav = new WavWriter(f);
            wavLeft = (long) recordSeconds * RATE;
            line("REC start file=" + f.getAbsolutePath() + " seconds=" + recordSeconds
                + " source=" + srcName(want));
          } catch (IOException e) {
            line("REC ERROR cannot open file: " + e);
            recordName = null;
          }
        }

        int r = rec.read(buf, 0, FRAME);
        if (r < 0) {
          errors++;
          if (errors <= 5 || errors % 100 == 0) {
            line("READ error=" + r + " source=" + srcName(openSrc) + " count=" + errors);
          }
          rec.release();
          rec = null;
          Thread.sleep(500);
          continue;
        }
        if (r == 0) {
          continue;
        }
        errors = 0;

        for (int i = 0; i < r; i++) {
          int v = buf[i];
          sumSq += (double) v * v;
          int a = v < 0 ? -v : v;
          if (a > peak) {
            peak = a;
          }
        }
        n += r;

        if (wav != null) {
          wav.write(buf, r);
          wavLeft -= r;
          if (wavLeft <= 0) {
            long bytes = wav.close();
            line("REC done bytes=" + bytes + " file=" + wav.file.getAbsolutePath());
            wav = null;
            recordName = null;
          }
        }

        if (n >= RATE / 4) {
          double rms = Math.sqrt(sumSq / n) / 32768.0;
          double db = rms > 0 ? 20.0 * Math.log10(rms) : -120.0;
          line(String.format(Locale.US, "RMS src=%s dbfs=%.1f peak=%d%s", srcName(openSrc), db,
              peak, db < -70 ? " SILENT" : ""));
          sumSq = 0;
          n = 0;
          peak = 0;
        }
      } catch (InterruptedException e) {
        break;
      } catch (Exception e) {
        line("CAPTURE error: " + e);
        try {
          Thread.sleep(500);
        } catch (InterruptedException ie) {
          break;
        }
      }
    }

    if (wav != null) {
      try {
        wav.close();
      } catch (IOException ignored) {
      }
    }
    if (rec != null) {
      rec.release();
    }
  }

  private AudioRecord openRecord(int source) {
    try {
      int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT);
      AudioRecord r = new AudioRecord.Builder()
          .setAudioSource(source)
          .setAudioFormat(new AudioFormat.Builder()
              .setSampleRate(RATE)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .build())
          .setBufferSizeInBytes(Math.max(min, FRAME * 2 * 8))
          .setContext(context)
          .build();
      line("OPEN source=" + srcName(source) + " state=" + r.getState() + " minBuf=" + min);
      if (r.getState() != AudioRecord.STATE_INITIALIZED) {
        r.release();
        line("OPEN failed: AudioRecord not initialised for " + srcName(source));
        return null;
      }
      r.startRecording();
      line("OPEN recordingState=" + r.getRecordingState() + " (3 = RECORDING)");
      line("ROUTE capture device=" + describe(r.getRoutedDevice()));
      return r;
    } catch (Exception e) {
      line("OPEN exception source=" + srcName(source) + ": " + e);
      return null;
    }
  }

  // ---------------------------------------------------------------- tone

  private void tone(int ms) {
    AudioTrack t = null;
    try {
      AudioDeviceInfo tel = null;
      for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
        if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
          tel = d;
          break;
        }
      }
      line("TONE telephony output present=" + (tel != null));

      int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
          AudioFormat.ENCODING_PCM_16BIT);
      t = new AudioTrack.Builder()
          .setAudioAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build())
          .setAudioFormat(new AudioFormat.Builder()
              .setSampleRate(RATE)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .build())
          .setBufferSizeInBytes(Math.max(minBuf, RATE / 5 * 2))
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build();
      if (tel != null) {
        line("TONE setPreferredDevice(telephony)=" + t.setPreferredDevice(tel));
      }
      t.play();

      short[] pcm = new short[FRAME];
      long total = (long) RATE * ms / 1000L;
      long written = 0;
      double phase = 0;
      double step = 2 * Math.PI * 880.0 / RATE;
      while (written < total) {
        int len = (int) Math.min(FRAME, total - written);
        for (int i = 0; i < len; i++) {
          pcm[i] = (short) (8000 * Math.sin(phase));
          phase += step;
        }
        int w = t.write(pcm, 0, len);
        if (w < 0) {
          line("TONE write error " + w);
          break;
        }
        written += len;
      }
      line("TONE done frames=" + written + " routed=" + describe(t.getRoutedDevice()));
    } catch (Exception e) {
      line("TONE error: " + e);
    } finally {
      if (t != null) {
        try {
          t.stop();
        } catch (IllegalStateException ignored) {
        }
        t.release();
      }
    }
  }

  // ---------------------------------------------------------------- helpers

  private void line(String msg) {
    String stamped = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + msg;
    Log.i(TAG, msg);
    synchronized (logLock) {
      log.add(stamped);
      while (log.size() > LOG_CAP) {
        log.poll();
      }
    }
  }

  static String srcName(int s) {
    switch (s) {
      case MediaRecorder.AudioSource.MIC: return "MIC";
      case MediaRecorder.AudioSource.VOICE_CALL: return "VOICE_CALL";
      case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
      case MediaRecorder.AudioSource.UNPROCESSED: return "UNPROCESSED";
      case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
      default: return "source" + s;
    }
  }

  static String describe(AudioDeviceInfo d) {
    if (d == null) {
      return "null";
    }
    return typeName(d.getType()) + "(type=" + d.getType() + ",id=" + d.getId() + ")";
  }

  private static String typeName(int type) {
    switch (type) {
      case AudioDeviceInfo.TYPE_TELEPHONY: return "TELEPHONY";
      case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "BUILTIN_MIC";
      case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "EARPIECE";
      case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "SPEAKER";
      case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED_HEADSET";
      case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BT_SCO";
      default: return "OTHER";
    }
  }

  private static String sanitize(String name) {
    String base = name == null ? "" : name.replaceAll("[^A-Za-z0-9_.-]", "_");
    if (base.isEmpty()) {
      base = "probe-" + System.currentTimeMillis();
    }
    return base;
  }

  /** Writes 16-bit mono 48 kHz PCM with a RIFF header that is patched on close. */
  static final class WavWriter {
    final File file;
    private final RandomAccessFile raf;
    private long dataBytes = 0;

    WavWriter(File f) throws IOException {
      this.file = f;
      File dir = f.getParentFile();
      if (dir != null) {
        dir.mkdirs();
      }
      raf = new RandomAccessFile(f, "rw");
      raf.setLength(0);
      raf.write(header(0));
    }

    void write(short[] pcm, int n) throws IOException {
      ByteBuffer bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < n; i++) {
        bb.putShort(pcm[i]);
      }
      raf.write(bb.array());
      dataBytes += n * 2L;
    }

    long close() throws IOException {
      raf.seek(0);
      raf.write(header(dataBytes));
      raf.close();
      return dataBytes;
    }

    private static byte[] header(long data) {
      ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
      b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
      b.putInt((int) (36 + data));
      b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
      b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
      b.putInt(16);
      b.putShort((short) 1);            // PCM
      b.putShort((short) 1);            // mono
      b.putInt(RATE);
      b.putInt(RATE * 2);               // byte rate
      b.putShort((short) 2);            // block align
      b.putShort((short) 16);           // bits per sample
      b.put("data".getBytes(StandardCharsets.US_ASCII));
      b.putInt((int) data);
      return b.array();
    }
  }
}
