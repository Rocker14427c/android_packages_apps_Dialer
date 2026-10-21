#!/system/bin/sh
# VoiceFX Dialer — uninstall hook.
#
# The point of this script is that removing the module must leave NOTHING
# behind, as if the app had never been installed:
#
#   1. the stock InCallService is re-enabled,
#   2. the Phone (DIALER) role goes back to whatever app held it before the
#      module was installed (recorded by customize.sh; the stock Phone app if
#      that record is missing),
#   3. com.axionvoice.dialer is uninstalled for every user, which also wipes
#      its data directory, its notification channels and its saved pitch
#      calibration,
#   4. a one-shot boot job is scheduled, because a priv-app cannot always be
#      fully uninstalled while its APK is still bind-mounted (the module is
#      still present at this moment). That job re-checks after the next boot,
#      when the overlay is gone, and removes itself.
#
# Every step is best-effort and verified; a failure never leaves the phone
# without a working dialer.

PKG=com.axionvoice.dialer
STOCK=com.android.dialer
STOCK_INCALL="$STOCK/com.android.incallui.InCallServiceImpl"
ROLE=android.app.role.DIALER
TAG=VoiceFXDialer

MODDIR=${MODPATH:-${0%/*}}
[ -d "$MODDIR" ] || MODDIR=/data/adb/modules/axionvoice_dialer

STATE=/data/local/tmp/.voicefx-uninstall-state.txt
CLEANUP=/data/adb/service.d/voicefx-cleanup.sh

log_msg() {
  log -t "$TAG" "$1" 2>/dev/null
}

role_holders() {
  cmd role get-role-holders "$ROLE" 2>/dev/null | tr ' ' '\n' | sed '/^$/d'
}

holds_role() {
  role_holders | grep -qx "$PKG"
}

add_role() {
  cmd role add-role-holder "$ROLE" "$1" >/dev/null 2>&1
  role_holders | grep -qx "$1"
}

# ---------------------------------------------------------------------------
# 0. stop the watchdog first, so it cannot fight this script.
if [ -f /data/local/tmp/.voicefx-gate.pid ]; then
  kill "$(cat /data/local/tmp/.voicefx-gate.pid)" 2>/dev/null
  rm -f /data/local/tmp/.voicefx-gate.pid
fi
# ---------------------------------------------------------------------------
# 1. remember the pre-install holder, then hand the role back. The stock
#    in-call UI is re-enabled first, so the role can move back safely.
if [ -f "$MODDIR/.state/install-state.txt" ]; then
  cp "$MODDIR/.state/install-state.txt" "$STATE" 2>/dev/null
fi

pm enable "$STOCK_INCALL" >/dev/null 2>&1

prev=""
if [ -f "$STATE" ]; then
  # The first block of the file (before the --- marker) is the holder list
  # captured at install time.
  prev=$(sed -n '1,/^---$/p' "$STATE" 2>/dev/null | sed '/^---$/d' | tr ' ' '\n' | sed '/^$/d' | grep -vx "$PKG" | tr '\n' ' ')
fi

# The DIALER role is exclusive: the previous holder cannot take it back while
# we still hold it, so let go of it first and hand it straight over.
if holds_role; then
  cmd role remove-role-holder "$ROLE" "$PKG" >/dev/null 2>&1
fi

restored=""
for p in $prev; do
  if role_holders | grep -qx "$p" || add_role "$p"; then
    restored="$restored $p"
  fi
done

# If nothing usable was restored (fresh boot, different ROM, ...), fall back to
# the stock Phone app so calls always have a default dialer.
if [ -z "$(role_holders)" ]; then
  add_role "$STOCK"
fi
log_msg "uninstall: Phone role restored to: $(role_holders | tr '\n' ' ')"

# ---------------------------------------------------------------------------
# 3. uninstall the package. It may be refused while the APK is still mounted;
#    the boot job below finishes the job in that case.
pm uninstall --user 0 "$PKG" >/dev/null 2>&1
pm uninstall "$PKG" >/dev/null 2>&1
if [ -z "$(pm path "$PKG" 2>/dev/null)" ]; then
  log_msg "uninstall: $PKG removed"
else
  log_msg "uninstall: $PKG still present (APK mounted); scheduled the boot cleanup"
fi

# ---------------------------------------------------------------------------
# 4. one-shot boot cleanup: the only place where the APK is really gone.
mkdir -p /data/adb/service.d 2>/dev/null
cat > "$CLEANUP" <<'CLEANUP_EOF'
#!/system/bin/sh
# One-shot cleanup written by the VoiceFX Dialer module uninstaller.
# Finishes the removal after the reboot that dropped the module, then deletes
# itself. Safe to run repeatedly: it only acts when the module is really gone.
PKG=com.axionvoice.dialer
STOCK=com.android.dialer
STOCK_INCALL="$STOCK/com.android.incallui.InCallServiceImpl"
ROLE=android.app.role.DIALER
STATE=/data/local/tmp/.voicefx-uninstall-state.txt
TAG=VoiceFXDialer
SELF="$0"

rm -f /data/local/tmp/.voicefx-gate.pid

# Module came back (reinstall): its own gate owns the device now.
if [ -d /data/adb/modules/axionvoice_dialer ]; then
  rm -f "$SELF"
  exit 0
fi

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 5

pm enable "$STOCK_INCALL" >/dev/null 2>&1

if [ -n "$(pm path "$PKG" 2>/dev/null)" ] || [ -d "/data/user/0/$PKG" ] || [ -d "/data/user_de/0/$PKG" ]; then
  pm uninstall --user 0 "$PKG" >/dev/null 2>&1
  pm uninstall "$PKG" >/dev/null 2>&1
fi

# Last resort if the package record survived: strip it from every user.
if [ -n "$(pm path "$PKG" 2>/dev/null)" ]; then
  for u in $(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9]*\):.*/\1/p'); do
    pm uninstall --user "$u" "$PKG" >/dev/null 2>&1
  done
fi

# Only wipe directories once the package manager no longer knows the app,
# so a half-removed package can never look "installed but broken".
if [ -z "$(pm path "$PKG" 2>/dev/null)" ]; then
  rm -rf "/data/user/0/$PKG" "/data/user_de/0/$PKG" 2>/dev/null
  for d in /data/user/*/"$PKG"; do rm -rf "$d" 2>/dev/null; done
fi

# The Phone role must point at something real.
if ! cmd role get-role-holders "$ROLE" 2>/dev/null | grep -q .; then
  cmd role add-role-holder "$ROLE" "$STOCK" >/dev/null 2>&1
fi

log -t "$TAG" "cleanup done: $(cmd role get-role-holders "$ROLE" 2>/dev/null | tr '\n' ' ')"
rm -f "$STATE"
rm -f "$SELF"
CLEANUP_EOF
chmod 0755 "$CLEANUP" 2>/dev/null
log_msg "uninstall: scheduled boot cleanup at $CLEANUP"
exit 0
