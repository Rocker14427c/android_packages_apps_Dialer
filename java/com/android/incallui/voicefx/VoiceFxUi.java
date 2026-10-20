/*
 * Copyright (C) 2026 The AxionOS Project
 *
 * In-call voice changer button menu + the single hook point called from
 * CallButtonPresenter. Centralizing the hook here keeps the presenter patch
 * to two tiny static calls (and made the identical smali hook possible).
 */
package com.android.incallui.voicefx;

import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;

import androidx.core.os.UserManagerCompat;

import com.android.dialer.R;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.state.DialerCallState;
import com.android.incallui.incall.protocol.InCallButtonIds;
import com.android.incallui.incall.protocol.InCallButtonUi;

public final class VoiceFxUi {

  private static final String TAG = "VoiceFxUi";

  private VoiceFxUi() {}

  /**
   * Called from CallButtonPresenter.updateButtonsState. Shows/hides the Voice
   * changer button and stops the engine whenever the call is no longer eligible.
   */
  public static void updateForCall(Context context, InCallButtonUi ui, DialerCall call) {
    if (ui == null || call == null) {
      return;
    }
    final boolean show =
        context != null
            && context.getResources().getBoolean(R.bool.voice_changer_enabled)
            && UserManagerCompat.isUserUnlocked(context)
            && !call.isVideoCall()
            && !call.isEmergencyCall()
            && call.getState() == DialerCallState.ACTIVE;
    ui.showButton(InCallButtonIds.BUTTON_VOICE_CHANGER, show);

    final VoiceFxController controller = VoiceFxController.get();
    if (controller.isActive() && !show) {
      Log.i(TAG, "stopping voice changer (call no longer eligible)");
      controller.stop();
      ui.setVoiceChangerState(false);
    }
  }

  /** Menu entry point: preset picker. Applies selection & updates the button. */
  public static void open(final Context context, final InCallButtonUi ui) {
    if (context == null) {
      return;
    }
    VoiceFxController.attachContext(context);

    final VoiceFxController controller = VoiceFxController.get();
    final int current = controller.getActivePreset();
    final CharSequence[] items = {
        context.getText(R.string.voice_changer_mode_normal),
        context.getText(R.string.voice_changer_mode_girl),
        context.getText(R.string.voice_changer_mode_boy),
        context.getText(R.string.voice_changer_mode_robot),
        context.getText(R.string.voice_changer_mode_child),
        context.getText(R.string.voice_changer_mode_agent),
    };

    new AlertDialog.Builder(context)
        .setTitle(R.string.voice_changer_dialog_title)
        .setSingleChoiceItems(items, current, (dialog, which) -> {
          if (which == VoiceFxProcessor.PRESET_NONE) {
            controller.stop();
          } else {
            controller.start(context.getApplicationContext(), which);
          }
          if (ui != null) {
            ui.setVoiceChangerState(which != VoiceFxProcessor.PRESET_NONE);
          }
          Log.i(TAG, "selected voice preset=" + which);
          dialog.dismiss();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }
}
