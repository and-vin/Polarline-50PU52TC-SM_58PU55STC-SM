#!/system/bin/sh
# Runs on the TV as the adb shell user (no root). Idempotent.
#   sh optimize.sh apply    debloat, animations off, location off, LeanKey, TV Home, precompile apps
#   sh optimize.sh revert   undo all of the above
MODE=${1:-apply}

# Preinstalled extras: AirPlay/EShare/DLNA receivers, vendor stores and ads, factory test,
# TalkBack, print/TTS/feedback services, OTA client (no updates since 2019), demo game.
# Never add com.example.user.myapplication: it is FallbackHome, without it the TV hangs on boot.
DEBLOAT="com.ecloud.eairplay com.ecloud.eshare.server com.ecloud.emedia com.cvte.tv.pictorial
com.cvte.tv.appstore cn.zeasn.cvte.mtk5522.vstoresubclient cn.cvte.tv.factory.autotest
com.google.android.marvin.talkback com.android.printservice.recommendation com.svox.pico
com.google.android.feedback com.zeasn.services.general com.cvte.tv.systemupgrade com.cvte.tvclean
com.FDGEntertainment.redball4.gp com.android.managedprovisioning com.google.android.partnersetup"

# The firmware installs apps as interpret-only and never runs background compilation.
COMPILE="com.andvin.tvhome ru.kinopoisk.tv rtb.mobile.android com.vk.tv com.google.android.youtube.tv
ru.more.play ru.ivi.client"

LEANKEY=org.liskovsoft.androidtv.rukeyboard/com.liskovsoft.leankeyboard.ime.LeanbackImeService
LATIN=com.android.inputmethod.latin/.LatinIME
HOME_NEW=com.andvin.tvhome/.HomeActivity
HOME_STOCK=com.cvte.tv.launcher.fall/com.cvte.tv.launcher.NebulaFallActivity

installed() { pm path "$1" >/dev/null 2>&1; }

if [ "$(getprop ro.product.name)" != "cvt_mt5886_eu_1g" ] && [ "$FORCE" != "1" ]; then
    echo "not a cvt_mt5886_eu_1g firmware ($(getprop ro.product.name)) - stopping; FORCE=1 to override"
    exit 2
fi

if [ "$MODE" = "apply" ]; then
    echo "-- disabling preinstalled extras"
    for p in $DEBLOAT; do installed $p && pm disable-user --user 0 $p >/dev/null && echo "   $p"; done

    echo "-- animations off, location off"
    settings put global window_animation_scale 0
    settings put global transition_animation_scale 0
    settings put global animator_duration_scale 0
    settings put secure location_providers_allowed -gps
    settings put secure location_providers_allowed -network

    if installed org.liskovsoft.androidtv.rukeyboard; then
        # The system falls back to LatinIME on its own (e.g. after a LeanKey update); with LatinIME
        # the RUTUBE search field does not react to the remote, so LatinIME is disabled.
        echo "-- keyboard: LeanKey only"
        ime enable $LEANKEY >/dev/null
        ime set $LEANKEY
        ime disable $LATIN >/dev/null
    fi

    if installed com.andvin.tvhome; then
        echo "-- home screen: TV Home"
        cmd package set-home-activity $HOME_NEW
    fi

    echo "-- precompiling apps (1-3 min each)"
    for p in $COMPILE; do
        installed $p || continue
        echo "   $p: $(cmd package compile -m speed -f $p)"
    done
else
    echo "-- enabling preinstalled extras"
    for p in $DEBLOAT; do installed $p && pm enable $p >/dev/null && echo "   $p"; done

    echo "-- animations on, location on"
    settings put global window_animation_scale 1
    settings put global transition_animation_scale 1
    settings put global animator_duration_scale 1
    settings put secure location_providers_allowed +network
    settings put secure location_providers_allowed +gps

    echo "-- keyboard: stock LatinIME"
    ime enable $LATIN >/dev/null
    ime set $LATIN

    echo "-- home screen: stock launcher"
    cmd package set-home-activity $HOME_STOCK

    echo "-- dropping precompiled code"
    for p in $COMPILE; do installed $p && cmd package compile --reset $p >/dev/null; done
fi
echo "-- optimize.sh $MODE done"
