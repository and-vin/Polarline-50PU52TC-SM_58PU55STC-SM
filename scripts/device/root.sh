#!/system/bin/sh
# Runs on the TV as root (`adb root`: the firmware is a userdebug build, /system has no dm-verity).
#   sh root.sh apply  <dir>   add root CAs from <dir>/*.0, disable boot-time background components
#   sh root.sh revert <dir>   remove those CAs, re-enable the components
# A reboot is needed afterwards.
MODE=${1:-apply}
CERTS=${2:-/data/local/tmp/polarline/certs}
DST=/system/etc/security/cacerts

# Wake КиноПоиск / YouTube on every boot just to publish home-screen recommendations, and run
# КиноПоиск's separate analytics process (~40 MB).
COMPONENTS="ru.kinopoisk.tv/ru.kinopoisk.tv.platform.recommendation.RecommendationsUpdateReceiver
ru.kinopoisk.tv/io.appmetrica.analytics.internal.AppMetricaService
com.google.android.youtube.tv/com.google.android.libraries.youtube.tv.recommendations.RecommendationsScheduler\$Receiver"
# PERSISTENT system app: Android 7 starts it even when disabled, but skips hidden packages.
# Do not touch /system/bin/preeshare.sh: besides the EShare daemon it enables adb over tcp (port 5555).
ESHARE=com.ecloud.eshare.server

[ "$(id -u)" = "0" ] || { echo "root.sh needs adb root"; exit 3; }
installed() { pm path "$1" >/dev/null 2>&1; }

mount -o rw,remount /system || { echo "cannot remount /system rw"; exit 4; }
if [ "$MODE" = "apply" ]; then
    echo "-- root CAs (RUTUBE, VK Video)"
    for f in "$CERTS"/*.0; do
        n=$(basename "$f")
        cp "$f" "$DST/$n" && chown root:root "$DST/$n" && chmod 644 "$DST/$n" && chcon u:object_r:system_file:s0 "$DST/$n"
        echo "   $n"
    done
else
    echo "-- removing added root CAs"
    for f in "$CERTS"/*.0; do rm -f "$DST/$(basename "$f")"; done
fi
sync
mount -o ro,remount /system

if [ "$MODE" = "apply" ]; then
    echo "-- background components off"
    for c in $COMPONENTS; do installed "${c%%/*}" && pm disable "$c" >/dev/null && echo "   $c"; done
    installed $ESHARE && pm disable $ESHARE >/dev/null && pm hide $ESHARE >/dev/null && echo "   $ESHARE hidden"
else
    echo "-- background components on"
    for c in $COMPONENTS; do installed "${c%%/*}" && pm enable "$c" >/dev/null; done
    pm unhide $ESHARE >/dev/null 2>&1
    pm enable $ESHARE >/dev/null 2>&1
fi
echo "-- root.sh $MODE done"
