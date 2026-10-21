package com.axionvoice.probe;

import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared state between the in-call service (Telecom thread) and the UI (main thread). */
final class ProbeState {
  static volatile ProbeInCallService service;
  static volatile CallAudioState audio;

  private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  private static final ConcurrentLinkedQueue<String> EVENTS = new ConcurrentLinkedQueue<>();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  static volatile Runnable uiListener;

  private ProbeState() {}

  static void addCall(Call c) {
    CALLS.add(c);
  }

  static void removeCall(Call c) {
    CALLS.remove(c);
  }

  static Call current() {
    return CALLS.isEmpty() ? null : CALLS.get(CALLS.size() - 1);
  }

  /** Telecom-side event, shown in the app log next to the shell probe's lines. */
  static void event(String msg) {
    String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    EVENTS.add(stamp + " [telecom] " + msg);
  }

  static String pollEvents() {
    StringBuilder sb = new StringBuilder();
    String s;
    while ((s = EVENTS.poll()) != null) {
      sb.append(s).append('\n');
    }
    return sb.toString();
  }

  static void notifyUi() {
    Runnable r = uiListener;
    if (r != null) {
      MAIN.post(r);
    }
  }

  static String stateName(int state) {
    switch (state) {
      case Call.STATE_NEW: return "NEW";
      case Call.STATE_DIALING: return "DIALING";
      case Call.STATE_RINGING: return "RINGING";
      case Call.STATE_HOLDING: return "HOLDING";
      case Call.STATE_ACTIVE: return "ACTIVE";
      case Call.STATE_DISCONNECTED: return "DISCONNECTED";
      case Call.STATE_CONNECTING: return "CONNECTING";
      case Call.STATE_DISCONNECTING: return "DISCONNECTING";
      default: return "STATE" + state;
    }
  }
}
