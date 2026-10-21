#!/system/bin/sh
# VoiceFX Dialer module installer (Magisk / KernelSU / ReSukiSU / APatch / APatch-based).
#
# The module is a pure systemless overlay: everything lands under $MODPATH/system
# and is bind-mounted at boot. No real partition is ever written, so removing the
# module and rebooting restores the device exactly.
#
# What this script does NOT do: move the Phone role or grant runtime permissions.
# That is service.sh's job, and it runs only after Android has actually scanned
# this overlay at boot. Doing it here would target a package that does not exist
# yet (the APK is still only in the zip).

SKIPUNZIP=0

print_modname() {
  ui_print "****************************************"
  ui_print " VoiceFX Dialer + Voice Changer"
  ui_print " com.axionvoice.dialer  (module v1.6)"
  ui_print "****************************************"
}

on_install() {
  API_LEVEL="$API"
  [ -z "$API_LEVEL" ] && API_LEVEL="$(getprop ro.build.version.sdk)"
  case "$API_LEVEL" in
    ''|*[!0-9]*) ui_print "- WARNING: cannot read the API level; continuing anyway." ;;
    *)
      if [ "$API_LEVEL" -lt 33 ]; then
        abort "! Android $API_LEVEL is too old (need Android 13 / API 33 or newer). Nothing was installed."
      fi
      ui_print "- Android API level: $API_LEVEL"
      ;;
  esac

  ui_print "- Extracting module files"
  unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2 || abort "! Failed to extract the module zip."

  # The base module decides where the priv-app lives; do not assume the path.
  APK=$(find "$MODPATH/system" -name '*.apk' 2>/dev/null | head -n 1)
  if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    abort "! No APK inside the module. Nothing was installed."
  fi
  APK_SIZE="$(stat -c%s "$APK" 2>/dev/null || wc -c < "$APK")"
  ui_print "- APK: ${APK#$MODPATH/} ($APK_SIZE bytes)"
  if [ "$APK_SIZE" -lt 4194304 ]; then
    abort "! APK looks truncated (<4 MB). Re-download the zip. Nothing was installed."
  fi

  # Remember who holds the Phone role right now, so uninstall.sh can hand it
  # back to exactly that app (it may be a Play-Store dialer, not just the
  # stock one). Also remember the mute/role state for the build report.
  mkdir -p "$MODPATH/.state"
  {
    cmd role get-role-holders android.app.role.DIALER 2>/dev/null
    echo "---"
    getprop ro.build.version.sdk
  } > "$MODPATH/.state/install-state.txt" 2>/dev/null
  ui_print "- Saved current Phone role holder for clean uninstall:"
  head -n 1 "$MODPATH/.state/install-state.txt" 2>/dev/null | while read -r line; do
    [ -n "$line" ] && ui_print "    $line"
  done

  ui_print "- Module files staged. Nothing has been changed yet."
}

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  for s in service.sh post-fs-data.sh uninstall.sh gate.sh; do
    [ -f "$MODPATH/$s" ] && set_perm "$MODPATH/$s" 0 0 0755
  done

  ui_print "****************************************"
  ui_print "- Reboot once to finish the install."
  ui_print "- After the reboot, VoiceFX Dialer becomes"
  ui_print "  the Phone app and all permissions are"
  ui_print "  granted automatically (service.sh)."
  ui_print "- Check with: adb logcat -s VoiceFXDialer"
  ui_print "- Rollback: uninstall the module, reboot."
  ui_print "  The package, its data and the Phone"
  ui_print "  role are returned to the previous app."
  ui_print "****************************************"
}
