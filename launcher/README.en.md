# TV Home launcher

[Русский](README.md)

`com.andvin.tvhome`: plain Views, no AndroidX, no Gradle, ~1 MB APK (mostly wallpapers). Prebuilt: [`apk/TvHome.apk`](../apk/TvHome.apk).

## Features

- Top: glass capsule with inputs (TV, HDMI, AV, VGA) and a capsule with time, date, network and settings.
- Favorites on a glass tray, a row of large cards (live TV/HDMI preview and recent apps), the grid of other apps, system tiles at the end.
- Menu or long OK on a tile: move, favorites, hide, uninstall.
- Inputs are switched through the CVTE TvApi, as the stock launcher does (see [docs/notes.en.md](../docs/notes.en.md)). Remote buttons are handled by the system; the launcher also handles `TV`, `TV_INPUT_HDMI_1..4`, `SETTINGS`, `TV_INPUT`.
- Home on the home screen switches to TV (system behaviour, same as with the stock launcher).
- If the stock launcher stayed in memory, it is unloaded 3 s after returning home (~50 MB).

## Build (Windows)

Needs JDK 17 and an Android SDK with `platforms;android-25` and `build-tools;34.0.0`.

```
powershell -ExecutionPolicy Bypass -File build.ps1 -Sdk <android-sdk> -Jdk <jdk-17> [-Keystore <file.jks>] [-StorePass <password>]
adb install -r build\tvhome.apk
adb shell cmd package compile -m speed -f com.andvin.tvhome
adb shell cmd package set-home-activity com.andvin.tvhome/.HomeActivity
```

Without `-Keystore` a new key is created in `keystore/` (git-ignored). An APK signed with a different key replaces `apk/TvHome.apk` only after `adb uninstall com.andvin.tvhome`.

`stubs/` are compile-only stubs of the CVTE API, not packaged. Wallpapers: Unsplash License, authors in [CREDITS.md](app/assets/wallpapers/CREDITS.md).
