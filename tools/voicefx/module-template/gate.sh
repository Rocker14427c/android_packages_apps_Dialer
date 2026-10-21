#!/system/bin/sh
# VoiceFX Dialer — runtime gate.
#
# Keeps the module's promises true at every boot and, in --watch mode, every
# 30 s while the phone runs:
#   * com.axionvoice.dialer has all of its runtime permissions,
#   * it is the Phone (DIALER role) app,
#   * exactly one dialer owns the in-call UI: while VoiceFX holds the role the
#     stock InCallService is disabled (Android 15+ restarts it in a loop
#     otherwise), and the moment VoiceFX stops holding the role the stock
#     service is re-enabled again.
#
# It is written so that it can never leave the phone without a working dialer:
# every change is verified, and every failure path hands the role back to the
# stock Phone app.
#
# Usage: gate.sh [--watch]

PKG=com.axionvoice.dialer
STOCK=com.android.dialer
STOCK_INCALL="$STOCK/com.android.incallui.InCallServiceImpl"
ROLE=android.app.role.DIALER
TAG=VoiceFXDialer
MODDIR=$(dirname "$0")
[ "$MODDIR" = "." ] && MODDIR=/data/adb/modules/axionvoice_dialer
PIDFILE=/data/local/tmp/.voicefx-gate.pid

log_msg() {
  log -t "$TAG" "$1" 2>/dev/null
  echo "$TAG: $1" > /dev/kmsg 2>/dev/null
}

# "com.android.dialer com.google.android.dialer" (one per line) or empty.
role_holders() {
  cmd role get-role-holders "$ROLE" 2>/dev/null | tr ' ' '\n' | sed '/^$/d'
}

holds_role() {
  role_holders | grep -qx "$PKG"
}

pkg_installed() {
  [ -n "$(pm path "$PKG" 2>/dev/null)" ]
}

# add_role <pkg>  -> 0 only when <pkg> is a verified holder afterwards.
add_role() {
  cmd role add-role-holder "$ROLE" "$1" >/dev/null 2>&1
  role_holders | grep -qx "$1" && return 0
  return 1
}

remove_role() {
  cmd role remove-role-holder "$ROLE" "$1" >/dev/null 2>&1
}

grant_runtime_permissions() {
  for p in \
    android.permission.READ_CONTACTS \
    android.permission.WRITE_CONTACTS \
    android.permission.READ_CALL_LOG \
    android.permission.WRITE_CALL_LOG \
    android.permission.CALL_PHONE \
    android.permission.READ_PHONE_STATE \
    android.permission.READ_PHONE_NUMBERS \
    android.permission.RECORD_AUDIO \
    android.permission.CAMERA \
    android.permission.GET_ACCOUNTS \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.RECEIVE_SMS \
    android.permission.SEND_SMS \
    android.permission.READ_SMS \
    android.permission.POST_NOTIFICATIONS \
    android.permission.BLUETOOTH_CONNECT \
    android.permission.ANSWER_PHONE_CALLS \
    android.permission.MANAGE_OWN_CALLS \
    android.permission.USE_FULL_SCREEN_INTENT \
    com.android.voicemail.permission.ADD_VOICEMAIL \
    com.android.voicemail.permission.READ_VOICEMAIL
  do
    pm grant "$PKG" "$p" >/dev/null 2>&1
  done
}

# The privileged permissions (MODIFY_PHONE_STATE etc.) can only be granted by the
# privapp allowlist at boot. If they are missing, the voice changer's uplink mute
# would fail, so say so loudly instead of failing silently mid-call.
check_privileged() {
  dumpsys package "$PKG" 2>/dev/null \
    | grep -q "android.permission.MODIFY_PHONE_STATE: granted=true" \
    && return 0
  return 1
}

sync_incall_service() {
  if holds_role && pkg_installed; then
    pm disable "$STOCK_INCALL" >/dev/null 2>&1
  else
    pm enable "$STOCK_INCALL" >/dev/null 2>&1
  fi
}

take_role() {
  # Already ours: nothing to do.
  holds_role && return 0

  # Free the role from whoever holds it, keeping the list so we can put it back
  # if the move fails. add-role-holder can fail when the role is exclusive.
  others=$(role_holders | grep -vx "$PKG" | tr '\n' ' ')
  if add_role "$PKG"; then
    log_msg "Phone role -> $PKG (was: ${others:-none})"
    return 0
  fi
  # The role is exclusive: remove the current holder, retry once.
  current=$(role_holders | head -n 1)
  [ -n "$current" ] && remove_role "$current"
  if add_role "$PKG"; then
    log_msg "Phone role -> $PKG (took it from $current)"
    return 0
  fi
  # Could not take it: never leave the phone without a dialer.
  for fallback in $current $STOCK; do
    if add_role "$fallback"; then
      log_msg "Phone role move failed; kept $fallback as the Phone app"
      return 1
    fi
  done
  log_msg "ERROR: could not establish any Phone role holder (role may be disabled)"
  return 1
}

run_once() {
  if ! pkg_installed; then
    log_msg "module files present but $PKG is not installed (not scanned yet?)"
    return 0
  fi

  grant_runtime_permissions

  if ! check_privileged; then
    log_msg "WARNING: privileged permissions missing - check the privapp allowlist"
  fi

  take_role
  sync_incall_service
}

watch() {
  echo $$ > "$PIDFILE"
  while true; do
    sleep 30
    # The module was removed (manager uninstall, recovery, manual rm): hand the
    # phone back and stop. This is the last resort if uninstall.sh could not run.
    if [ ! -d /data/adb/modules/axionvoice_dialer ]; then
      log_msg "module removed while running; restoring the stock dialer"
      restore_stock >/dev/null 2>&1
      rm -f "$PIDFILE"
      exit 0
    fi
    if ! holds_role; then
      # Someone else owns the Phone role now (the user picked another dialer, or
      # VoiceFX was disabled). Respect that: never steal the role back, only make
      # sure the stock in-call UI is available again.
      if [ -n "$(pm path "$STOCK" 2>/dev/null)" ]; then
        pm enable "$STOCK_INCALL" >/dev/null 2>&1
      fi
    fi
  done
}

# Hand everything back: stock InCallService re-enabled, role returned to the
# previous holder(s) recorded at install time, VoiceFX de-registered from it.
restore_stock() {
  pm enable "$STOCK_INCALL" >/dev/null 2>&1

  # Exclusive role: step off it before handing it back.
  holds_role && remove_role "$PKG"

  prev_file="$MODDIR/.state/install-state.txt"
  restored=""
  if [ -f "$prev_file" ]; then
    # First block, up to the "---" marker: the holders recorded at install time.
    sed -n '1,/^---$/p' "$prev_file" | sed '/^---$/d' | tr ' ' '\n' | sed '/^$/d' > "$PIDFILE.prev"
    while read -r p; do
      [ -z "$p" ] && continue
      [ "$p" = "$PKG" ] && continue
      if ! role_holders | grep -qx "$p"; then
        add_role "$p" && restored="$restored $p"
      else
        restored="$restored $p"
      fi
    done < "$PIDFILE.prev"
    rm -f "$PIDFILE.prev"
  fi

  if [ -z "$restored" ] || [ -z "$(role_holders)" ]; then
    add_role "$STOCK"
  fi
  log_msg "restored: Phone role holders now: $(role_holders | tr '\n' ' ')"
}

case "$1" in
  --watch) watch ;;
  --restore) restore_stock ;;
  *) run_once ;;
esac
