package com.axionvoice.probe;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

/**
 * Only receives calls while this app holds the Dialer role. Its setMuted() call is the same
 * InCallService mute path the VoiceFX engine uses, so the probe tests that path directly.
 */
public class ProbeInCallService extends InCallService {

  private final Call.Callback callback = new Call.Callback() {
    @Override
    public void onStateChanged(Call call, int state) {
      ProbeState.event("call state -> " + ProbeState.stateName(state));
      ProbeState.notifyUi();
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    ProbeState.service = this;
  }

  @Override
  public void onDestroy() {
    ProbeState.service = null;
    super.onDestroy();
  }

  @Override
  public void onCallAdded(Call call) {
    ProbeState.addCall(call);
    call.registerCallback(callback);
    ProbeState.event("call added, state=" + ProbeState.stateName(call.getState()));
    if (call.getState() == Call.STATE_RINGING) {
      Intent i = new Intent(this, MainActivity.class);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      startActivity(i);
    }
    ProbeState.notifyUi();
  }

  @Override
  public void onCallRemoved(Call call) {
    call.unregisterCallback(callback);
    ProbeState.removeCall(call);
    ProbeState.event("call removed");
    ProbeState.notifyUi();
  }

  @Override
  public void onCallAudioStateChanged(CallAudioState audioState) {
    ProbeState.audio = audioState;
    ProbeState.event("telecom audio muted=" + audioState.isMuted()
        + " route=" + audioState.getRoute());
    ProbeState.notifyUi();
  }
}
