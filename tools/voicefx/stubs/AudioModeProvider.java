/*
 * Compile-time stub — NEVER shipped.
 *
 * VoiceFxController.java talks to a handful of dialer-internal classes
 * (AudioModeProvider, TelecomAdapter) that only exist as compiled smali inside
 * the base APK. javac cannot read smali, so the builder compiles this stub to
 * get the types, then throws the .class away: only com.android.incallui.voicefx.*
 * is handed to d8.
 *
 * The stub signature is verified against the real smali in the base APK by
 * build_v16.py (ABI gate), so a mismatch here fails the build instead of
 * crashing on the phone with NoSuchMethodError.
 */
package com.android.incallui.audiomode;

import android.telecom.CallAudioState;

public final class AudioModeProvider {

  private AudioModeProvider() {}

  public static AudioModeProvider getInstance() {
    throw new RuntimeException("stub");
  }

  public CallAudioState getAudioState() {
    throw new RuntimeException("stub");
  }
}
