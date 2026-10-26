# TV Home — лаунчер

[English](README.en.md)

`com.andvin.tvhome`: обычные View без AndroidX и Gradle, APK ~1 МБ (почти всё — обои). Готовая сборка — [`apk/TvHome.apk`](../apk/TvHome.apk).

## Что умеет

- Сверху: стеклянная капсула входов (ТВ, HDMI, AV, VGA) и капсула «время, дата, сеть, настройки».
- Избранное на стеклянном лотке, ряд крупных карточек (живое превью ТВ/HDMI и последние приложения), сетка остальных приложений, в конце — системные плитки.
- «Меню» или долгое OK на плитке: переставить, избранное, скрыть, удалить.
- Входы переключаются через TvApi CVTE, как в родном лаунчере (см. [docs/notes.md](../docs/notes.md)). Кнопки пульта обрабатывает система, лаунчер дополнительно понимает `TV`, `TV_INPUT_HDMI_1..4`, `SETTINGS`, `TV_INPUT`.
- Home на главном экране переключает на ТВ (так ведёт себя система, как и с родным лаунчером).
- Если родной лаунчер остался в памяти, через 3 секунды после возврата на главный экран выгружает его (~50 МБ).

## Сборка (Windows)

Нужны JDK 17 и Android SDK с `platforms;android-25` и `build-tools;34.0.0`.

```
powershell -ExecutionPolicy Bypass -File build.ps1 -Sdk <android-sdk> -Jdk <jdk-17> [-Keystore <file.jks>] [-StorePass <пароль>]
adb install -r build\tvhome.apk
adb shell cmd package compile -m speed -f com.andvin.tvhome
adb shell cmd package set-home-activity com.andvin.tvhome/.HomeActivity
```

Без `-Keystore` создаётся новый ключ в `keystore/` (в git не попадает). APK, подписанный другим ключом, встанет поверх `apk/TvHome.apk` только после `adb uninstall com.andvin.tvhome`.

`stubs/` — заглушки API CVTE только для компиляции, в APK не попадают. Обои — Unsplash License, авторы в [CREDITS.md](app/assets/wallpapers/CREDITS.md).
