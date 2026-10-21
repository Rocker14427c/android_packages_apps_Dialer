#!/system/bin/sh
# VoiceFX Dialer — boot hook.
#
# Runs after Android has finished booting (the overlay must already be mounted
# and the package scanned), applies the runtime state, then starts the watchdog
# that keeps the Phone role and the permissions true for as long as the module
# stays installed.

TAG=VoiceFXDialer
MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 5

if [ ! -d "$MODDIR" ]; then
  exit 0
fi

# One application of the rules, then the 30 s watchdog in the background.
sh "$MODDIR/gate.sh" >> /dev/null 2>&1
sh "$MODDIR/gate.sh" --watch > /dev/null 2>&1 &

log -t "$TAG" "boot hook done; watchdog started"
exit 0
