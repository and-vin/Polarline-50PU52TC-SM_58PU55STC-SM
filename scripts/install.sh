#!/usr/bin/env bash
# Applies (or reverts) all optimizations to a Polarline 50PU52TC-SM / 58PU55STC-SM over adb.
#   scripts/install.sh 192.168.1.50                 apply everything
#   scripts/install.sh 192.168.1.50 --revert        undo everything
#   options: --no-root --no-launcher --no-keyboard
# adb is taken from PATH, otherwise Google platform-tools are downloaded into .tools/.
# Use at your own risk. See README.md.
set -u
[ $# -ge 1 ] || { sed -n '2,7p' "$0"; exit 1; }
TV=$1; shift
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
MODE=apply NOROOT=0 NOLAUNCHER=0 NOKEYBOARD=0
for a in "$@"; do
    case "$a" in
        --revert) MODE=revert ;; --no-root) NOROOT=1 ;;
        --no-launcher) NOLAUNCHER=1 ;; --no-keyboard) NOKEYBOARD=1 ;;
        *) echo "unknown option $a"; exit 1 ;;
    esac
done
REPO=$(cd "$(dirname "$0")/.." && pwd)
TOOLS=$REPO/.tools
REMOTE=/data/local/tmp/polarline
LEANKEY_URL=https://github.com/yuliskov/LeanKeyboard/releases/download/6.1.31/LeanKeyboard_v6.1.31_playstore_r.apk
LEANKEY_SHA=5a90529fcae55c664128fb36e752f90e84d158266eced85084594c1d336a1468

fail() { echo "ERROR: $*" >&2; exit 1; }
step() { echo "== $*"; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

if command -v adb >/dev/null; then ADB=adb; else
    ADB=$TOOLS/platform-tools/adb
    if [ ! -x "$ADB" ]; then
        step "downloading Android platform-tools (adb) from dl.google.com"
        os=linux; [ "$(uname)" = Darwin ] && os=darwin
        mkdir -p "$TOOLS"
        curl -fsSL -o "$TOOLS/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" || fail "download failed"
        unzip -q -o "$TOOLS/pt.zip" -d "$TOOLS" && rm "$TOOLS/pt.zip"
    fi
fi
A() { "$ADB" -s "$TV" "$@"; }
sh_tv() { "$ADB" -s "$TV" shell "$@" | tr -d '\r'; }

wait_device() {
    local end=$(( $(date +%s) + ${1:-180} )) warned=0
    while [ "$(date +%s)" -lt $end ]; do
        "$ADB" connect "$TV" >/dev/null 2>&1
        local state; state=$("$ADB" -s "$TV" get-state 2>&1)
        if [ "$state" = device ]; then
            [ "$(sh_tv getprop sys.boot_completed)" = 1 ] && return 0
        elif [[ "$state" == *unauthorized* ]] && [ $warned = 0 ]; then
            echo 'Confirm "Allow USB debugging" on the TV screen (tick "Always allow").'; warned=1
        fi
        sleep 3
    done
    fail "no adb connection to $TV. Check the IP address, the network (no VPN routing the LAN) and that USB debugging is on."
}
muted() { sh_tv dumpsys audio | grep -m1 Muted | grep -q true; }

step "connecting to $TV"
wait_device
product=$(sh_tv getprop ro.product.name)
[ "$product" = cvt_mt5886_eu_1g ] || fail "unexpected firmware '$product' (expected cvt_mt5886_eu_1g)"
was_muted=0; muted && was_muted=1

if [ $MODE = apply ]; then
    if [ $NOKEYBOARD = 0 ]; then
        apk=$TOOLS/LeanKeyboard_v6.1.31.apk
        if [ ! -f "$apk" ]; then
            step "downloading LeanKey keyboard from its author's GitHub releases"
            mkdir -p "$TOOLS"; curl -fsSL -o "$apk" "$LEANKEY_URL" || fail "download failed"
        fi
        [ "$(sha256 "$apk")" = $LEANKEY_SHA ] || { rm -f "$apk"; fail "LeanKey APK checksum mismatch - deleted, run again"; }
        step "installing LeanKey"; A install -r "$apk"
    fi
    if [ $NOLAUNCHER = 0 ]; then
        step "installing TV Home launcher"
        A install -r "$REPO/apk/TvHome.apk" || echo "If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE: adb uninstall com.andvin.tvhome, then run again."
    fi
fi

step "copying scripts to the TV"
sh_tv "rm -rf $REMOTE; mkdir -p $REMOTE/certs" >/dev/null
A push "$REPO/scripts/device/optimize.sh" "$REMOTE/optimize.sh" >/dev/null
A push "$REPO/scripts/device/root.sh" "$REMOTE/root.sh" >/dev/null
for c in "$REPO"/certs/*.0; do A push "$c" "$REMOTE/certs/$(basename "$c")" >/dev/null; done

step "optimize.sh $MODE"
sh_tv "sh $REMOTE/optimize.sh $MODE"

if [ $NOROOT = 0 ]; then
    [ "$(sh_tv getprop ro.debuggable)" = 1 ] || fail "ro.debuggable is not 1 - adb root is not available; rerun with --no-root"
    step "adb root"; A root; sleep 5; wait_device
    step "root.sh $MODE"; sh_tv "sh $REMOTE/root.sh $MODE $REMOTE/certs"
    A unroot >/dev/null 2>&1; sleep 5; wait_device
fi

sh_tv "rm -rf $REMOTE" >/dev/null
if [ $NOROOT = 0 ]; then
    step "rebooting (about a minute)"
    A reboot >/dev/null; sleep 20; wait_device 300
    # The TV unmutes itself on every boot.
    if [ $was_muted = 1 ]; then for i in 1 2 3 4 5 6 7 8 9 10; do muted && break; sh_tv input keyevent 164; sleep 2; done; fi
fi
step "done"
