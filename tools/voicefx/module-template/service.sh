#!/system/bin/sh
# VoiceFX Dialer — late-start service.
# After every boot: re-grant runtime permissions, hold the DIALER role for
# com.axionvoice.dialer (transaction-style: never leave the phone without a
# dialer), and keep the stock dialer's InCallService component-disabled
# while VoiceFX holds the role (prevents the Android 15 FGS crash loop in
# the stock app; automatically re-enabled when the role goes back).

PKG=com.axionvoice.dialer
STOCK=com.android.dialer
STOCK_INCALL=com.android.incallui.InCallServiceImpl
TAG=VoiceFXDialer

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 3

# --- runtime permissions (idempotent belt & braces over the XML grants) ---
for p in \
  android.permission.READ_CONTACTS \
  android.permission.WRITE_CONTACTS \
  android.permission.READ_CALL_LOG \
  android.permission.WRITE_CALL_LOG \
  android.permission.CALL_PHONE \
  android.permission.READ_PHONE_STATE \
  android.permission.RECORD_AUDIO \
  android.permission.CAMERA \
  android.permission.GET_ACCOUNTS \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.ACCESS_COARSE_LOCATION \
  android.permission.RECEIVE_SMS \
  android.permission.SEND_SMS \
  android.permission.POST_NOTIFICATIONS \
  android.permission.BLUETOOTH_CONNECT \
  com.android.voicemail.permission.ADD_VOICEMAIL \
  com.android.voicemail.permission.READ_VOICEMAIL
do
  pm grant "$PKG" "$p" >/dev/null 2>&1
done

# --- DIALER role (move, verify, restore-on-failure) ---
holders() { cmd role get-role-holders android.app.role.DIALER 2>/dev/null; }

if holders | grep -q "$PKG"; then
  :
else
  cmd role add-role-holder android.app.role.DIALER "$PKG" 2>/dev/null
  if ! holders | grep -q "$PKG"; then
    # Role was full: free it from the stock holder, then retry once.
    cmd role remove-role-holder android.app.role.DIALER "$STOCK" 2>/dev/null
    cmd role add-role-holder android.app.role.DIALER "$PKG" 2>/dev/null
  fi
  if ! holders | grep -q "$PKG"; then
    # Could not move the role: restore the stock dialer so calls never break.
    cmd role add-role-holder android.app.role.DIALER "$STOCK" 2>/dev/null
    log -t "$TAG" "role move failed; stock dialer restored" 2>/dev/null
  fi
fi

# --- stock InCallService gate (only while VoiceFX holds the role) ---
if holders | grep -q "$PKG"; then
  pm disable "$STOCK/$STOCK_INCALL" >/dev/null 2>&1
else
  pm enable "$STOCK/$STOCK_INCALL" >/dev/null 2>&1
fi
