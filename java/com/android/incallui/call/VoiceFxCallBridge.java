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

package com.android.incallui.call;

import androidx.annotation.Nullable;

/**
 * Bridge exposing the wrapped {@link android.telecom.Call} of a {@link DialerCall} to the
 * voice changer engine. {@link DialerCall#getTelecomCall()} is package-private, hence this
 * tiny accessor that lives in the same package.
 */
public final class VoiceFxCallBridge {

  private VoiceFxCallBridge() {}

  @Nullable
  public static android.telecom.Call getTelecomCall(DialerCall call) {
    return call == null ? null : call.getTelecomCall();
  }
}
