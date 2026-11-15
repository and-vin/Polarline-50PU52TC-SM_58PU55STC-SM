# Notes: causes and measurements

[Русский](notes.md)

Measured on a 58PU55STC-SM, firmware `cvt_mt5886_eu_1g-userdebug 7.0 NRD90M eng.builde.20191218`.

## Device

- MediaTek MT5522 (4× Cortex-A53), Mali-450, 1 GB RAM (847 MB visible to Android, 268 MB of it CMA for the video decoder).
- `ro.build.type=userdebug`, `ro.debuggable=1`, `/system` is ext4 without dm-verity: `adb root` and remount work with no exploit.
- No `com.android.settings` UI: no app list, no keyboard picker, no developer options in the TV menu.
- `/system/bin/preeshare.sh` (init service `preeshare`) enables adb over tcp on port 5555. Keep it.
- Load average ~28 at idle is not load: ~30 MediaTek driver threads (tuners, CEC, HDMI audio, IR) sleep in state `D`.
- Remote buttons are handled globally by `HotkeyManager` in `com.cvte.tv.api.impl`; it ignores `adb shell input keyevent` (device id -1), so test Source/Menu with the real remote.

## RUTUBE and VK Video: root CAs

Android 7.0 lacks the roots these services use now, so connections fail with `CertPathValidatorException: Trust anchor for certification path not found`. RUTUBE trusts only system CAs, so a user-installed CA is not enough.

| File | Root | Needed by | SHA-256 fingerprint |
|---|---|---|---|
| `6187b673.0` | ISRG Root X1 | rutube.ru | `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6` |
| `b92fd57f.0` | HARICA TLS RSA Root CA 2021 | vkvideo.ru, vk.ru, okcdn.ru, mycdn.me, userapi.com | `D9:5D:0E:8E:DA:79:52:5B:F9:BE:B1:1B:14:D2:10:0D:32:94:98:5F:0C:62:D9:FA:BD:9C:D9:99:EC:CB:7B:1D` |
| `2c63f966.0` | SSL.com TLS RSA Root CA 2022 | vk.com, login.vk.com, id.vk.com | `8F:AF:7D:2E:2C:B4:70:9B:B8:E0:B3:36:66:BF:75:A5:DD:45:B5:DE:48:0F:8E:A8:D4:BF:E6:BE:BC:17:F2:ED` |

File names are `subject_hash_old`, PEM format, as in `/system/etc/security/cacerts`.

## Start-up speed

The firmware installs and updates apps with `pm.dexopt.install=interpret-only`, and background compilation (`bg-dexopt`) never runs on a TV, so every app stays interpreted. Cold start (`am force-stop`, then `am start -W`, 3 runs):

| App | First screen | Main screen from launch |
|---|---|---|
| КиноПоиск, interpret-only → speed | 2.0–2.6 s → 1.6–2.8 s | 10.5–13 s → 8.7–10.4 s |
| RUTUBE, interpret-only → speed | 3.6–4.0 s → 1.9–3.1 s | 6.5–7.5 s → 4.0–5.1 s |
| VK Video, interpret-only → speed | 2.3–3.4 s → 2.5–2.8 s | same screen |

The remaining ~6 s on the КиноПоиск splash is network loading. `speed` costs ~50–110 MB of `/data` per app.

## Memory

| | Stock | After |
|---|---|---|
| Launcher | 45–75 MB, ~15% CPU idle | 18–25 MB, 0% CPU |
| Available at idle | ~400 MB | ~440–480 MB |

During КиноПоиск playback (~250 MB PSS) ~105–130 MB stays available, zRAM (200 MB) fills up and the system kills background processes (Google services, the launcher). The player itself is not killed. Larger zRAM was tested and does not help:

| zRAM / swappiness | Available | Killed | kswapd CPU |
|---|---|---|---|
| 200 MB / 150 (stock) | 103–130 MB | passport, GMS, launcher | ~1% |
| 400 MB / 150 | 115–117 MB | none | ~23% constantly |
| 300 MB / 100 | 122–136 MB | passport, GMS, launcher | ~1% |

Root tweaks remove boot-time wakeups: КиноПоиск (~70 MB) and YouTube publishing home-screen recommendations, КиноПоиск's AppMetrica process (~40 MB; the SDK then logs a bind retry every 5 s, harmless), EShare (`PERSISTENT`, starts even when disabled; hidden with `pm hide`, otherwise it crashes once with an "app stopped" dialog until the next reboot).

## Keyboard

The stock LatinIME does not take focus from the remote in the RUTUBE search field. The system may switch the default back to LatinIME on its own (seen after a LeanKey update), so LatinIME is disabled with `ime disable`. Picking a keyboard in the settings menu crashes on this firmware, hence `ime set`.

## Wi‑Fi

AltoBeam module (`atbm` driver, single radio). Wi‑Fi Direct (the "Wifi display" Miracast receiver, `com.mediatek.androidbox`) listens on social channel 6. If the router is on another channel the module misses its beacons (`AP lost`), and the link comes back only after ~2 minutes (`Auto connect disabled`). Router on channel 6 or Ethernet (100 Mbit/s) fixes it.

## adb

- Enabling ADB through the service menu (Menu → 1147 → Debug → ADB Enable → On) works on the project owner's TV: connecting from a PC triggers the authorization prompt (owner confirmed on 2026-11-15). The previous claim that the prompt appears only when debugging is enabled through Android developer options was incorrect. See also [4PDA, post #661](https://4pda.to/forum/index.php?showtopic=980343&st=640). Alternatives and limitations are in [README.en.md](../README.en.md#installation).
- Once, adbd stopped answering (connection accepted, `device offline`) after several `adb root`/`adb unroot` cycles; the process was alive. A reboot fixed it. The scripts switch to root once.

## Stock launcher and TvApi

The stock launcher `com.cvte.tv.launcher.fall` (`/system/priv-app/NebulaLauncher`) talks to the CVTE TV service (`com.cvte.tv.api.impl`) through `cn.cvte.tv.api` from `BOOTCLASSPATH`. Any app can use it without a system signature:

- `TvApiSDKManager.init(Application)`, then `getInstance().getTvApi(TvServiceConnection)` (listeners are held through `WeakReference`, keep a strong reference).
- `ITvApiManager.getTVApiSystemInputSource()`: `eventSystemInputSourceGetList()`, `eventSystemInputSourceGetInputSource()`, `eventSystemInputSourceSetInputSource(id)`.
- `ITvApiManager.getTVApiScreenWindow()`: `eventScreenWindowSetFull()`, `eventScreenWindowSetPipValue(x, y, w, h)`, `eventScreenWindowMute(boolean)`.
- Switching to an input: `SetInputSource(id)`, then `SetFull()`, then `startActivity(MAIN + category TV_HOME)` (handled by `com.cvte.tv.androidsetting/.TvSettingActivity`).
- Preview on the home screen: a transparent hole in the window plus `SetPipValue`; `Mute(true)` when leaving.
- Mali-450: `clipPath` ignores the canvas transform. Round corners use a `BitmapShader` with `drawRoundRect`; focus zoom uses `canvas.scale` in `onDraw`.
