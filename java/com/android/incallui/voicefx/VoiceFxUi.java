/*
 * Copyright (C) 2026 The AxionOS Project
 *
 * In-call voice changer button menu + the single hook point called from
 * CallButtonPresenter. Centralizing the hook here keeps the presenter patch
 * to two tiny static calls (and made the identical smali hook possible).
 *
 * v1.3 crash fixes:
 *  - The preset dialog must be attached to the live call-screen Activity, not
 *    the application context (a window needs an activity token, otherwise
 *    BadTokenException on click). We resolve it via
 *    InCallButtonUi.getInCallButtonUiFragment(); if the screen is already gone,
 *    the opener degrades to a toast.
 *  - Applying a preset is wrapped so any engine/routing failure surfaces as a
 *    toast instead of an uncaught exception on the main thread.
 */
package com.android.incallui.voicefx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.core.os.UserManagerCompat;
import androidx.fragment.app.Fragment;

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

  /** Menu entry point: preset picker bound to the live call screen Activity. */
  public static void open(final Context context, final InCallButtonUi ui) {
    if (context == null || ui == null) {
      return;
    }
    VoiceFxController.attachContext(context);

    final Activity activity = resolveCallActivity(ui);
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
      Log.w(TAG, "open: no live call activity; aborting dialog");
      toast(context, context.getText(R.string.voice_changer_failed));
      return;
    }

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

    new AlertDialog.Builder(activity)
        .setTitle(R.string.voice_changer_dialog_title)
        .setSingleChoiceItems(items, current, (dialog, which) -> {
          try {
            if (which == VoiceFxProcessor.PRESET_NONE) {
              controller.stop();
            } else {
              controller.start(activity.getApplicationContext(), which);
            }
            ui.setVoiceChangerState(which != VoiceFxProcessor.PRESET_NONE);
            Log.i(TAG, "selected voice preset=" + which);
          } catch (Throwable t) {
            Log.e(TAG, "applying preset failed", t);
            ui.setVoiceChangerState(false);
            toast(context, context.getText(R.string.voice_changer_failed));
          }
          dialog.dismiss();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /**
   * v1.4: resolve via Fragment.getContext() — in the shipped (minified) APK the
   * androidx Fragment declares getActivity()Landroidx/fragment/app/FragmentActivity;
   * not android.app.Activity. Using framework-typed getContext() (the host context,
   * i.e. the Activity itself) cannot signature-mismatch against the packaged
   * minified library.
   */
  private static Activity resolveCallActivity(InCallButtonUi ui) {
    try {
      final Fragment f = ui.getInCallButtonUiFragment();
      if (f == null) {
        return null;
      }
      Context host = null;
      try {
        host = f.getContext();
      } catch (Throwable ignored) {
      }
      final Activity act = (host instanceof Activity) ? (Activity) host : null;
      if (act == null || act.isFinishing() || act.isDestroyed()) {
        return null;
      }
      return act;
    } catch (Throwable t) {
      Log.e(TAG, "resolveCallActivity failed", t);
      return null;
    }
  }

  private static void toast(Context context, CharSequence msg) {
    try {
      Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
    } catch (Throwable ignored) {
    }
  }
}
