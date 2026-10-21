#!/system/bin/sh
# VoiceFX Dialer module installer (Magisk / KernelSU / ReSukiSU / APatch).
# Systemless overlay only: everything lands under $MODPATH/system and is
# bind-mounted at boot. No real partition is ever written; removing the
# module and rebooting restores the device byte-for-byte.

SKIPUNZIP=0

print_modname() {
  ui_print "********************************"
  ui_print " VoiceFX Dialer (voice changer)"
  ui_print " com.axionvoice.dialer"
  ui_print "********************************"
}

on_install() {
  API_LEVEL="$API"
  [ -z "$API_LEVEL" ] && API_LEVEL="$(getprop ro.build.version.sdk)"
  ui_print "- Android API level: $API_LEVEL"
  case "$API_LEVEL" in
    33|34|35|36) ;;
    *) abort "! Unsupported Android API $API_LEVEL (need 33-36 / Android 13-16). Nothing was installed." ;;
  esac

  ui_print "- Extracting module files"
  unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2 || abort "! Failed to extract the module zip."

  APK="$MODPATH/system/product/priv-app/AxionVoiceDialer/AxionVoiceDialer.apk"
  if [ ! -f "$APK" ]; then
    abort "! AxionVoiceDialer.apk missing from the zip. Nothing was installed."
  fi
  APK_SIZE="$(stat -c%s "$APK" 2>/dev/null || wc -c < "$APK")"
  ui_print "- APK size: $APK_SIZE bytes"
  if [ "$APK_SIZE" -lt 4194304 ]; then
    abort "! APK looks truncated (<4 MB). Re-download the zip. Nothing was installed."
  fi
}

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  [ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755
  [ -f "$MODPATH/post-fs-data.sh" ] && set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
  [ -f "$MODPATH/uninstall.sh" ] && set_perm "$MODPATH/uninstall.sh" 0 0 0755
  ui_print "- Done. Reboot once; the Phone role moves to VoiceFX Dialer after boot."
  ui_print "- Rollback any time: uninstall this module -> reboot (stock dialer returns)."
}
