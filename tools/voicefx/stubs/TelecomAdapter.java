/*
 * Compile-time stub — NEVER shipped. See AudioModeProvider.java in this folder.
 *
 * The real implementation lives in the base APK (com.android.incallui.call.TelecomAdapter)
 * and its mute()/isMuted() signatures are checked against that smali by the ABI gate.
 */
package com.android.incallui.call;

public final class TelecomAdapter {

  private TelecomAdapter() {}

  public static TelecomAdapter getInstance() {
    throw new RuntimeException("stub");
  }

  public void mute(boolean shouldMute) {
    throw new RuntimeException("stub");
  }
}
