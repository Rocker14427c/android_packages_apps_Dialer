package com.axionvoice.probe;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.telecom.Call;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Test console. Everything the shell probe and the Telecom side report ends up in one log,
 * stamped with the same wall clock, so a mute press can be matched to the level drop.
 */
public class MainActivity extends Activity {
  private static final int REQ_ROLE = 2;
  private static final int REQ_PERMS = 3;
  private static final int PROBE_VERSION = 1;
  private static final int LOG_MAX = 80000;

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final StringBuilder logBuf = new StringBuilder();

  private volatile IProbeService probe;
  private Shizuku.UserServiceArgs serviceArgs;

  private TextView statusView;
  private TextView callView;
  private TextView logView;
  private ScrollView logScroll;
  private EditText numberField;
  private Button answerButton;
  private Button hangupButton;
  private Button muteButton;

  private interface ProbeCall {
    void run(IProbeService p) throws RemoteException;
  }

  private final ServiceConnection connection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      final IProbeService p = IProbeService.Stub.asInterface(service);
      probe = p;
      appLog("PROBE bound to the shell user service");
      refreshStatus();
      io.execute(() -> {
        try {
          String who = p.hello();
          main.post(() -> appLog("PROBE identity: " + who));
        } catch (RemoteException e) {
          main.post(() -> appLog("PROBE hello failed: " + e.getMessage()));
        }
      });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      probe = null;
      appLog("PROBE disconnected");
      refreshStatus();
    }
  };

  private final Shizuku.OnRequestPermissionResultListener permListener =
      new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
          appLog("SHIZUKU permission "
              + (grantResult == PackageManager.PERMISSION_GRANTED ? "granted" : "denied"));
          refreshStatus();
        }
      };

  private final Shizuku.OnBinderReceivedListener binderListener =
      new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
          appLog("SHIZUKU binder received");
          refreshStatus();
        }
      };

  private final Shizuku.OnBinderDeadListener deadListener = new Shizuku.OnBinderDeadListener() {
    @Override
    public void onBinderDead() {
      appLog("SHIZUKU binder dead (Shizuku stopped)");
      probe = null;
      refreshStatus();
    }
  };

  private final Runnable poll = new Runnable() {
    @Override
    public void run() {
      String events = ProbeState.pollEvents();
      if (!events.isEmpty()) {
        appendRaw(events);
      }
      final IProbeService p = probe;
      if (p != null) {
        io.execute(() -> {
          try {
            String s = p.drainLog();
            if (!s.isEmpty()) {
              main.post(() -> appendRaw(s));
            }
          } catch (RemoteException e) {
            main.post(() -> appLog("PROBE lost: " + e.getMessage()));
          }
        });
      }
      main.postDelayed(this, 300);
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    buildUi();
    Shizuku.addRequestPermissionResultListener(permListener);
    Shizuku.addBinderReceivedListenerSticky(binderListener);
    Shizuku.addBinderDeadListener(deadListener);
    ProbeState.uiListener = () -> {
      refreshCall();
      refreshStatus();
    };
    requestPermissions(new String[] {
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS
    }, REQ_PERMS);
    appLog("APP started. Probe version " + PROBE_VERSION + ". Follow the test plan in the README.");
    refreshStatus();
    refreshCall();
    main.post(poll);
  }

  @Override
  protected void onDestroy() {
    ProbeState.uiListener = null;
    main.removeCallbacks(poll);
    Shizuku.removeRequestPermissionResultListener(permListener);
    Shizuku.removeBinderReceivedListener(binderListener);
    Shizuku.removeBinderDeadListener(deadListener);
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    refreshStatus();
    refreshCall();
  }

  // ------------------------------------------------------------------ UI

  private void buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(12);
    root.setPadding(pad, pad, pad, pad);

    TextView title = text("AxionVoice Probe", 20);
    root.addView(title);

    statusView = text("", 13);
    root.addView(statusView);

    root.addView(section("1. Shizuku and the shell probe"));
    root.addView(row(
        button("Grant Shizuku", v -> requestShizuku()),
        button("Start probe", v -> startProbe()),
        button("Stop probe", v -> stopProbe())));

    root.addView(section("2. Capture source (what the probe measures)"));
    root.addView(row(
        button("Call uplink (VOICE_COMMUNICATION)", v -> setSource(0)),
        button("Mic (MIC)", v -> setSource(1)),
        button("Call audio (VOICE_CALL)", v -> setSource(2))));

    root.addView(section("3. Tests you can run during a call"));
    root.addView(row(
        button("Tone via telephony route (1.5 s)", v -> withProbe("TONE", p -> p.playTone(1500))),
        button("Record 10 s call audio", v -> withProbe("REC", p -> p.recordCall(10,
            "probe-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()))))));
    root.addView(row(
        button("Mute via AudioManager ON", v -> withProbe("MUTE", p -> p.setMicMuteViaAudioManager(true))),
        button("Mute via AudioManager OFF", v -> withProbe("MUTE", p -> p.setMicMuteViaAudioManager(false)))));

    root.addView(section("4. Phone app (needed for Telecom mute and for calls)"));
    root.addView(row(
        button("Make default phone app", v -> requestDefaultDialer()),
        button("Copy log", v -> copyLog()),
        button("Clear log", v -> clearLog())));

    root.addView(section("5. Call"));
    callView = text("", 14);
    root.addView(callView);

    numberField = new EditText(this);
    numberField.setHint("number to call (e.g. +91...)");
    numberField.setSingleLine(true);
    root.addView(numberField);
    root.addView(row(button("Place call", v -> placeCall())));

    root.addView(row(
        answerButton = button("Answer", v -> answerCall()),
        hangupButton = button("Hang up", v -> hangUp()),
        muteButton = button("Mute (InCallService)", v -> toggleMute())));

    root.addView(section("Log (app + Telecom + shell probe, one clock)"));
    logView = new TextView(this);
    logView.setTextSize(11);
    logView.setTypeface(android.graphics.Typeface.MONOSPACE);
    logView.setMovementMethod(new ScrollingMovementMethod());
    logScroll = new ScrollView(this);
    logScroll.addView(logView);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(300));
    root.addView(logScroll, lp);

    ScrollView outer = new ScrollView(this);
    outer.addView(root);
    setContentView(outer);
  }

  private TextView text(String s, int sp) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(sp);
    t.setPadding(0, dp(4), 0, dp(4));
    return t;
  }

  private TextView section(String s) {
    TextView t = text(s, 15);
    t.setPadding(0, dp(14), 0, dp(4));
    return t;
  }

  private LinearLayout row(View... views) {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.HORIZONTAL);
    for (View v : views) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
          LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      r.addView(v, lp);
    }
    return r;
  }

  private Button button(String label, View.OnClickListener l) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(12);
    b.setOnClickListener(l);
    return b;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  // ------------------------------------------------------------------ log

  /** Thread-safe app log line. */
  private void appLog(String msg) {
    String s = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
        + " [app] " + msg + "\n";
    main.post(() -> appendRaw(s));
  }

  /** UI thread only. */
  private void appendRaw(String s) {
    logBuf.append(s);
    if (logBuf.length() > LOG_MAX) {
      logBuf.delete(0, logBuf.length() - LOG_MAX);
    }
    logView.setText(logBuf.toString());
    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
  }

  private void clearLog() {
    logBuf.setLength(0);
    logView.setText("");
  }

  private void copyLog() {
    ClipboardManager cm = getSystemService(ClipboardManager.class);
    cm.setPrimaryClip(ClipData.newPlainText("AxionVoice Probe log", logBuf.toString()));
    Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
  }

  // ------------------------------------------------------------------ Shizuku

  private void requestShizuku() {
    try {
      if (!Shizuku.pingBinder()) {
        appLog("SHIZUKU is not running. Start Shizuku (Wireless debugging) first.");
        return;
      }
      if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        appLog("SHIZUKU permission already granted");
        return;
      }
      Shizuku.requestPermission(1);
    } catch (Exception e) {
      appLog("SHIZUKU error: " + e);
    }
  }

  private void startProbe() {
    try {
      if (!Shizuku.pingBinder()) {
        appLog("SHIZUKU is not running. Start Shizuku (Wireless debugging) first.");
        return;
      }
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        appLog("SHIZUKU permission missing. Tap 'Grant Shizuku' first.");
        return;
      }
      if (probe != null) {
        appLog("PROBE already running");
        return;
      }
      serviceArgs = new Shizuku.UserServiceArgs(
              new ComponentName(getPackageName(), ProbeService.class.getName()))
          .daemon(false)
          .tag("axionvoice-probe")
          // Required by Shizuku 13.x (it throws NPE without it); process becomes com.axionvoice.probe:probe.
          .processNameSuffix("probe")
          .version(PROBE_VERSION)
          .debuggable(false);
      Shizuku.bindUserService(serviceArgs, connection);
      appLog("PROBE binding to shell user service...");
    } catch (Exception e) {
      appLog("PROBE start failed: " + e);
    }
  }

  private void stopProbe() {
    final IProbeService p = probe;
    try {
      if (serviceArgs != null) {
        Shizuku.unbindUserService(serviceArgs, connection, true);
      }
    } catch (Exception e) {
      appLog("PROBE unbind: " + e);
    }
    if (p != null) {
      io.execute(() -> {
        try {
          p.exit();
        } catch (Exception ignored) {
          // the process may already be gone
        }
      });
    }
    probe = null;
    appLog("PROBE stopped");
    refreshStatus();
  }

  private void setSource(int code) {
    withProbe("MONITOR", p -> p.setMonitorSource(code));
  }

  private void withProbe(String what, ProbeCall c) {
    final IProbeService p = probe;
    if (p == null) {
      appLog(what + ": probe not running. Tap 'Start probe' first.");
      return;
    }
    io.execute(() -> {
      try {
        c.run(p);
        main.post(() -> appLog(what + " command sent"));
      } catch (Exception e) {
        main.post(() -> appLog(what + " failed: " + e));
      }
    });
  }

  // ------------------------------------------------------------------ phone app and calls

  private void requestDefaultDialer() {
    RoleManager rm = getSystemService(RoleManager.class);
    if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
      appLog("ROLE dialer role not available on this device");
      return;
    }
    if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
      appLog("ROLE this app is already the default phone app");
      return;
    }
    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_ROLE) {
      RoleManager rm = getSystemService(RoleManager.class);
      boolean held = rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
      appLog("ROLE result=" + resultCode + " this app is default phone app=" + held);
      refreshStatus();
    }
  }

  private void placeCall() {
    String number = numberField.getText().toString().trim();
    if (number.isEmpty()) {
      appLog("CALL type a number first");
      return;
    }
    if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
      appLog("CALL permission missing: allow Phone in app permissions");
      return;
    }
    try {
      getSystemService(TelecomManager.class).placeCall(
          Uri.fromParts("tel", number, null), new Bundle());
      appLog("CALL placeCall(" + number + ") sent");
    } catch (SecurityException e) {
      appLog("CALL denied: " + e.getMessage());
    }
  }

  private void answerCall() {
    Call c = ProbeState.current();
    if (c != null) {
      c.answer(VideoProfile.STATE_AUDIO_ONLY);
      appLog("CALL answer() requested");
    }
  }

  private void hangUp() {
    Call c = ProbeState.current();
    if (c == null) {
      return;
    }
    if (c.getState() == Call.STATE_RINGING) {
      c.reject(false, null);
    } else {
      c.disconnect();
    }
    appLog("CALL hang up requested");
  }

  private void toggleMute() {
    ProbeInCallService s = ProbeState.service;
    if (s == null) {
      appLog("MUTE InCallService not bound: make this app the default phone app");
      return;
    }
    boolean now = ProbeState.audio != null && ProbeState.audio.isMuted();
    s.setMuted(!now);
    appLog("MUTE InCallService.setMuted(" + !now + ") requested");
  }

  private void refreshCall() {
    Call c = ProbeState.current();
    if (c == null) {
      callView.setText("No call. Make this app the default phone app, then place or receive a call.");
      answerButton.setEnabled(false);
      hangupButton.setEnabled(false);
      muteButton.setEnabled(false);
      return;
    }
    String num = "unknown";
    if (c.getDetails() != null && c.getDetails().getHandle() != null) {
      num = c.getDetails().getHandle().getSchemeSpecificPart();
    }
    boolean muted = ProbeState.audio != null && ProbeState.audio.isMuted();
    callView.setText("Call: " + ProbeState.stateName(c.getState()) + "   number=" + num
        + "   Telecom muted=" + muted);
    answerButton.setEnabled(c.getState() == Call.STATE_RINGING);
    hangupButton.setEnabled(true);
    muteButton.setEnabled(ProbeState.service != null);
    muteButton.setText(muted ? "Unmute (InCallService)" : "Mute (InCallService)");
  }

  private void refreshStatus() {
    String shizuku;
    String perm;
    try {
      shizuku = Shizuku.pingBinder() ? "running" : "NOT running";
      perm = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
          ? "granted" : "not granted";
    } catch (Exception e) {
      shizuku = "error";
      perm = "unknown";
    }
    RoleManager rm = getSystemService(RoleManager.class);
    boolean role = rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
    statusView.setText("Shizuku: " + shizuku + ", permission " + perm
        + "   |   Probe: " + (probe != null ? "bound" : "stopped")
        + "   |   Default phone app: " + (role ? "yes" : "no")
        + "   |   Android " + android.os.Build.VERSION.RELEASE);
  }
}
