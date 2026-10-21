#!/system/bin/sh
# VoiceFX Dialer — intentionally no early work.
# The priv-app must be visible during the early package scan, which the
# standard module mount already guarantees. Keep your manager on its
# default (early) mount mode for this module — do NOT use late/lazy mount.
exit 0
