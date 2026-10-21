#!/bin/sh
# Simulated install / boot / uninstall cycle for the VoiceFX module scripts.
#
# The interesting failures in a system module are not in the DSP, they are in the
# handover of the Phone role and in what is left behind when the module is
# removed. Those paths cannot be unit-tested from Java, so this script mocks
# `pm`, `cmd` and `log` and runs the real scripts in module-template/ against
# them, checking the state after every step:
#
#   A  first boot        -> role moves to VoiceFX, stock in-call service disabled,
#                           runtime permissions granted
#   B  uninstall         -> role returned to the app that had it, stock in-call
#                           re-enabled, a boot cleanup job is scheduled because
#                           the APK is still mounted
#   C  reboot, no module -> the cleanup job uninstalls the package, wipes its
#                           data and deletes itself
#   D  role refused      -> the phone keeps its current dialer
#   E  user switched     -> uninstall does not fight the user's choice
#   F  nobody holds it   -> uninstall hands it to the stock dialer
#   G  module vanished   -> the watchdog restore path returns everything
#
# Needs root (it writes under /data and the module scripts expect Android's
# paths). Run on a Linux box or in CI; it never touches a real device.
#
#   sudo ./tools/voicefx/test-lifecycle.sh
set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
T="$HERE/module-template"
S=${SIM_STATE:-/tmp/voicefx-sim}
MOD=/data/adb/modules/axionvoice_dialer
PKG=com.axionvoice.dialer
STOCK=com.android.dialer

if [ "$(id -u)" -ne 0 ]; then
  if command -v sudo >/dev/null 2>&1; then
    exec sudo -E sh "$0" "$@"
  fi
  echo "this test needs root (or sudo)" >&2
  exit 2
fi

fail=0
pass() { echo "  PASS  $1"; }
bad() { echo "  FAIL  $1"; fail=$((fail + 1)); }
check() { if [ "$2" = "$3" ]; then pass "$1 ($3)"; else bad "$1: want '$2' got '$3'"; fi; }

holders() { cat "$S/state/role_holders" 2>/dev/null; }
incall() { [ -f "$S/state/incall_disabled" ] && echo disabled || echo enabled; }

install_mocks() {
  rm -rf "$S"
  mkdir -p "$S/bin" "$S/state"
  cat > "$S/bin/cmd" <<'EOF'
#!/bin/sh
# Mock 'cmd': an exclusive DIALER role, like the real RoleManager.
S=${SIM_STATE:-/tmp/voicefx-sim}/state
echo "cmd $*" >> "$S/calls.log"
case "$1 $2" in
  "role get-role-holders") cat "$S/role_holders" 2>/dev/null ;;
  "role add-role-holder")
    role=$3; pkg=$4
    [ "$(cat "$S/role_disabled" 2>/dev/null)" = "$pkg" ] && exit 1
    cur=$(head -n1 "$S/role_holders" 2>/dev/null)
    if [ -n "$cur" ] && [ "$cur" != "$pkg" ]; then exit 1; fi
    printf '%s\n' "$pkg" > "$S/role_holders"; exit 0 ;;
  "role remove-role-holder")
    pkg=$4
    if [ "$(head -n1 "$S/role_holders" 2>/dev/null)" = "$pkg" ]; then : > "$S/role_holders"; fi
    exit 0 ;;
esac
exit 1
EOF
  cat > "$S/bin/pm" <<'EOF'
#!/bin/sh
# Mock 'pm': a priv-app whose APK cannot be deleted while the module is mounted.
S=${SIM_STATE:-/tmp/voicefx-sim}/state
echo "pm $*" >> "$S/calls.log"
case "$1" in
  path)
    [ -f "$S/installed" ] && { echo "package:/product/priv-app/AxionVoiceDialer/AxionVoiceDialer.apk"; exit 0; }
    exit 1 ;;
  uninstall)
    [ -f "$S/apk_mounted" ] && exit 1
    rm -f "$S/installed"; exit 0 ;;
  grant) exit 0 ;;
  enable) rm -f "$S/incall_disabled"; exit 0 ;;
  disable) touch "$S/incall_disabled"; exit 0 ;;
  list)
    if [ "${2:-}" = users ]; then echo "Users:"; echo "	UserInfo{0:Owner:c13} running"; exit 0; fi
    exit 1 ;;
esac
exit 1
EOF
  printf '#!/bin/sh\necho "log $*" >> "${SIM_STATE:-/tmp/voicefx-sim}/state/logcat.txt"\n' > "$S/bin/log"
  printf '#!/bin/sh\n[ "${1:-}" = sys.boot_completed ] && echo 1 || echo 33\n' > "$S/bin/getprop"
  printf '#!/bin/sh\n[ "${1:-}" = package ] && echo "    android.permission.MODIFY_PHONE_STATE: granted=true"\n' > "$S/bin/dumpsys"
  printf '#!/bin/sh\nexit 0\n' > "$S/bin/sleep"
  chmod 0755 "$S/bin"/*
  mkdir -p /data/local/tmp
  chmod 1777 /data/local/tmp
  export SIM_STATE PATH="$S/bin:$PATH"
}

reset() {
  rm -rf "$MOD" /data/adb/service.d/voicefx-cleanup.sh
  rm -rf "$S/state"
  mkdir -p "$S/state" "$MOD/.state"
  : > "$S/state/role_holders"
  : > "$S/state/calls.log"
  rm -f /data/local/tmp/.voicefx-gate.pid /data/local/tmp/.voicefx-uninstall-state.txt
  chmod -R 0777 "$S"
}

echo "== mocks in $S"
install_mocks
mkdir -p /data/adb

echo
echo "=== A: first boot after install"
reset
echo "com.google.android.dialer" > "$S/state/role_holders"
touch "$S/state/installed" "$S/state/apk_mounted"
printf 'com.google.android.dialer\n---\n33\n' > "$MOD/.state/install-state.txt"
cp "$T/gate.sh" "$T/service.sh" "$T/uninstall.sh" "$MOD/"
chmod -R 0755 "$MOD"
sh "$MOD/gate.sh" >/dev/null 2>&1
check "Phone role moved to VoiceFX" "$PKG" "$(holders)"
check "stock in-call service disabled" disabled "$(incall)"
if grep -q "pm grant $PKG android.permission.RECORD_AUDIO" "$S/state/calls.log"; then
  pass "runtime permissions granted"
else
  bad "runtime permissions were not granted"
fi

echo
echo "=== B: uninstall (the APK is still bind-mounted)"
sh "$MOD/uninstall.sh" >/dev/null 2>&1
check "stock in-call service re-enabled" enabled "$(incall)"
check "Phone role returned to the previous app" "com.google.android.dialer" "$(holders)"
check "package still there (cannot be deleted while mounted)" yes \
  "$([ -f "$S/state/installed" ] && echo yes || echo no)"
check "boot cleanup scheduled" yes \
  "$([ -f /data/adb/service.d/voicefx-cleanup.sh ] && echo yes || echo no)"

echo
echo "=== C: reboot, module gone, cleanup runs"
rm -rf "$MOD"
rm -f "$S/state/apk_mounted"
mkdir -p /data/user/0/$PKG
sh /data/adb/service.d/voicefx-cleanup.sh >/dev/null 2>&1
check "package uninstalled" no "$([ -f "$S/state/installed" ] && echo yes || echo no)"
check "app data wiped" no "$([ -d /data/user/0/$PKG ] && echo yes || echo no)"
check "cleanup script removed itself" no \
  "$([ -f /data/adb/service.d/voicefx-cleanup.sh ] && echo yes || echo no)"
check "Phone role still points at a real app" "com.google.android.dialer" "$(holders)"

echo
echo "=== D: our package is refused the role - the phone keeps its dialer"
reset
echo "com.google.android.dialer" > "$S/state/role_holders"
echo "$PKG" > "$S/state/role_disabled"
touch "$S/state/installed"
cp "$T/gate.sh" "$MOD/"
chmod -R 0755 "$MOD"
sh "$MOD/gate.sh" >/dev/null 2>&1
check "current dialer still holds the role" "com.google.android.dialer" "$(holders)"
check "stock in-call service left enabled" enabled "$(incall)"

echo
echo "=== E: user switched to another dialer, then uninstalled the module"
reset
echo "com.google.android.dialer" > "$S/state/role_holders"
touch "$S/state/installed"
printf 'com.android.dialer\n---\n33\n' > "$MOD/.state/install-state.txt"
cp "$T/gate.sh" "$T/uninstall.sh" "$MOD/"
chmod -R 0755 "$MOD"
sh "$MOD/uninstall.sh" >/dev/null 2>&1
check "the user's choice is kept" "com.google.android.dialer" "$(holders)"

echo
echo "=== F: nobody holds the role at uninstall time"
reset
touch "$S/state/installed"
cp "$T/gate.sh" "$T/uninstall.sh" "$MOD/"
chmod -R 0755 "$MOD"
sh "$MOD/uninstall.sh" >/dev/null 2>&1
check "role handed to the stock dialer" "$STOCK" "$(holders)"

echo
echo "=== G: module vanished while running (watchdog restore)"
reset
echo "$PKG" > "$S/state/role_holders"
touch "$S/state/installed" "$S/state/incall_disabled"
printf 'com.google.android.dialer\n---\n33\n' > "$MOD/.state/install-state.txt"
cp "$T/gate.sh" "$MOD/"
chmod -R 0755 "$MOD"
sh "$MOD/gate.sh" --restore >/dev/null 2>&1
check "role handed back" "com.google.android.dialer" "$(holders)"
check "stock in-call service re-enabled" enabled "$(incall)"

echo
rm -rf "$MOD" /data/adb/service.d/voicefx-cleanup.sh
echo "$fail failure(s)"
[ "$fail" -eq 0 ] || exit 1
