#!/system/bin/sh

SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=false

ui_print "*******************************"
ui_print " Zaid Screen Recorder v1.0.0"
ui_print " Privileged AudioPolicy module"
ui_print "*******************************"
ui_print "- Installs the release APK under /system/priv-app"
ui_print "- Grants MODIFY_AUDIO_ROUTING"
ui_print "- Grants CAPTURE_AUDIO_OUTPUT / CAPTURE_MEDIA_OUTPUT"
ui_print "- Enables Remote Submix AudioPolicy loopback without MediaProjection"
ui_print "- Reboot is required"

set_perm_recursive "$MODPATH" 0 0 0755 0644
if [ -f "$MODPATH/system/priv-app/ZaidScreenRecorder/ZaidScreenRecorder.apk" ]; then
  set_perm "$MODPATH/system/priv-app/ZaidScreenRecorder" 0 0 0755
  set_perm "$MODPATH/system/priv-app/ZaidScreenRecorder/ZaidScreenRecorder.apk" 0 0 0644
else
  abort "Release APK missing from module package"
fi
set_perm "$MODPATH/system/etc/permissions/privapp-permissions-com.zaid.screenrecorder.xml" 0 0 0644
