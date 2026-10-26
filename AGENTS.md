# AGENTS.md

Instructions for AI agents helping an owner of a Polarline 50PU52TC-SM / 58PU55STC-SM. Human docs: `README.md` (ru), `README.en.md`; causes and measurements: `docs/notes.en.md`.

## Device

- Chassis TP.MT5522S.PC822 (CVTE), MediaTek MT5522, Mali-450, 1 GB RAM, Android 7.0 (SDK 24).
- Firmware identity: `ro.product.name=cvt_mt5886_eu_1g`, `ro.build.type=userdebug`, `ro.debuggable=1`. Stop if `ro.product.name` differs.
- ADB over the network on port 5555. Debugging must be enabled in Android developer options (via Activity Launcher → `com.android.settings` if the TV menu does not show it); enabling it through the service menu (Menu → 1147) never shows the authorization prompt.

## Hard rules

- Never disable `com.example.user.myapplication` (FallbackHome, `/system/priv-app/directBootApk`): the TV hangs on the boot logo. Fix: `pm enable` it and reboot.
- Never run `adb reboot bootloader`: recovery needs reflashing.
- Never modify `/system/bin/preeshare.sh`: it enables adb over tcp.
- Do not disable `com.mediatek.*`, `com.cvte.tvservice`, `com.cvte.tv.api.impl`, `com.cvte.channel`, `com.cvte.rro.tvapiservice`, `com.android.providers.tv`, `com.cvte.tv.androidsetting`, `com.cvte.fac.menu`, `com.cvte.tv.remotedebug`, Google services, WebView, or the stock launcher `com.cvte.tv.launcher.fall` (fallback home).
- Do not start "Wifi display" (`com.mediatek.androidbox`) while working over adb: Wi‑Fi Direct drops the link for ~2 minutes.
- The TV unmutes itself on every boot. If the owner keeps it muted, check `dumpsys audio | grep Muted` after a reboot and send `input keyevent 164` until it is `true`.
- Ask before rebooting or force-stopping apps if something may be playing (`dumpsys media_session | grep state=`; state 3 means playing).
- Switch adb to root as rarely as possible: repeated `adb root`/`adb unroot` cycles once left adbd alive but unresponsive (`device offline`) until a reboot.

## Repository

| Path | What |
|---|---|
| `scripts/install.ps1`, `scripts/install.sh` | Host wrappers: find/download adb, install LeanKey (SHA-256 pinned) and `apk/TvHome.apk`, push and run the device scripts, reboot, restore mute |
| `scripts/device/optimize.sh` | On the TV as shell: debloat, animations, location, IME, home activity, `cmd package compile -m speed` (`apply`/`revert`) |
| `scripts/device/root.sh` | On the TV as root: root CAs into `/system/etc/security/cacerts`, disable boot-time components, hide EShare (`apply`/`revert`) |
| `certs/` | ISRG Root X1, HARICA TLS RSA Root CA 2021, SSL.com TLS RSA Root CA 2022 (`subject_hash_old.0`, PEM) |
| `launcher/` | TV Home sources, compile-only CVTE stubs, `build.ps1` (aapt2 → javac → d8 → zipalign → apksigner, no Gradle) |
| `apk/TvHome.apk` | Prebuilt launcher; rebuild and replace it when launcher sources change |

Keep `*.sh` with LF line endings (`.gitattributes`); keep `*.ps1` ASCII-only, Windows PowerShell 5.1 misreads UTF-8 without BOM.

## Verify after applying

```
adb shell getprop ro.product.name                      # cvt_mt5886_eu_1g
adb shell pm list packages -d                          # the DEBLOAT list from optimize.sh
adb shell settings get secure default_input_method     # ...LeanbackImeService
adb shell ime list -s                                  # LeanKey only
adb shell ls /system/etc/security/cacerts/6187b673.0 /system/etc/security/cacerts/b92fd57f.0 /system/etc/security/cacerts/2c63f966.0
adb shell dumpsys package dexopt | grep -A3 ru.kinopoisk.tv    # compilation_filter=speed
adb shell ps | grep eshare                             # nothing after reboot
adb shell dumpsys window windows | grep mCurrentFocus  # com.andvin.tvhome/.HomeActivity
```

Apps to check by hand (with sound muted): КиноПоиск, RUTUBE (`rtb.mobile.android`, search must open LeanKey), VK Video (`com.vk.tv`): start, search, playback.

## Measuring

Cold start: `am force-stop <pkg>`, wait, `am start -W -n <component>`, read `TotalTime` and the last `ActivityManager: Displayed <pkg>` line in logcat. Memory: `dumpsys meminfo` (Total PSS by process, Free RAM, ZRAM). Background kills: `logcat -b events | grep -E 'am_kill|am_proc_died'`. Load average ~28 is normal (driver threads in `D` state).

## Known limits

- WebView 51 and Google Play Services 13.2 are outdated and cannot be updated on this firmware.
- RuStore TV needs Android 8 and does not install.
- During КиноПоиск playback (~250 MB PSS) only ~105–130 MB stays available and background processes get killed; larger zRAM was tested and does not help (see `docs/notes.en.md`).
- `pm disable` of other apps' components needs root (`Shell cannot change component state`).
