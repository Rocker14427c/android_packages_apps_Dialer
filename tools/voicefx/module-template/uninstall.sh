#!/system/bin/sh
# VoiceFX Dialer — uninstall hook: hand everything back to the stock dialer
# so phone behaviour is byte-for-byte stock after removal + reboot.

PKG=com.axionvoice.dialer
STOCK=com.android.dialer
STOCK_INCALL=com.android.incallui.InCallServiceImpl

# Re-enable the stock in-call service (service.sh disabled it while the
# VoiceFX package held the DIALER role).
pm enable "$STOCK/$STOCK_INCALL" >/dev/null 2>&1

# Return the Phone (DIALER) role to the stock Phone app.
cmd role add-role-holder android.app.role.DIALER "$STOCK" >/dev/null 2>&1
cmd role remove-role-holder android.app.role.DIALER "$PKG" >/dev/null 2>&1
