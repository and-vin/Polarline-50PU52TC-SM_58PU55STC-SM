# Заметки: причины и замеры

[English](notes.en.md)

Замерено на 58PU55STC-SM, прошивка `cvt_mt5886_eu_1g-userdebug 7.0 NRD90M eng.builde.20191218`.

## Устройство

- MediaTek MT5522 (4× Cortex-A53), Mali-450, 1 ГБ RAM: Android видит 847 МБ, из них 268 МБ — CMA видеодекодера.
- `ro.build.type=userdebug`, `ro.debuggable=1`, `/system` — ext4 без dm-verity: `adb root` и перемонтирование работают без взлома.
- Нет `com.android.settings`: в меню ТВ нет списка приложений, выбора клавиатуры, «Для разработчиков».
- `/system/bin/preeshare.sh` (служба init `preeshare`) включает adb по сети на порту 5555. Не трогать.
- Load average ~28 в простое — не нагрузка: ~30 потоков драйверов MediaTek (тюнеры, CEC, HDMI-аудио, ИК) спят в состоянии `D`.
- Кнопки пульта глобально обрабатывает `HotkeyManager` в `com.cvte.tv.api.impl`. Нажатия `adb shell input keyevent` (device id −1) он игнорирует, поэтому «Источник» и «Меню» проверяйте настоящим пультом.

## RUTUBE и VK Видео: корневые сертификаты

В Android 7.0 нет корней, которыми сейчас подписаны эти сервисы, поэтому соединения падают с `CertPathValidatorException: Trust anchor for certification path not found`. RUTUBE доверяет только системным сертификатам, установка пользовательского не поможет.

| Файл | Корень | Для чего | SHA-256 отпечаток |
|---|---|---|---|
| `6187b673.0` | ISRG Root X1 | rutube.ru | `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6` |
| `b92fd57f.0` | HARICA TLS RSA Root CA 2021 | vkvideo.ru, vk.ru, okcdn.ru, mycdn.me, userapi.com | `D9:5D:0E:8E:DA:79:52:5B:F9:BE:B1:1B:14:D2:10:0D:32:94:98:5F:0C:62:D9:FA:BD:9C:D9:99:EC:CB:7B:1D` |
| `2c63f966.0` | SSL.com TLS RSA Root CA 2022 | vk.com, login.vk.com, id.vk.com | `8F:AF:7D:2E:2C:B4:70:9B:B8:E0:B3:36:66:BF:75:A5:DD:45:B5:DE:48:0F:8E:A8:D4:BF:E6:BE:BC:17:F2:ED` |

Имена файлов — `subject_hash_old`, формат PEM, как в `/system/etc/security/cacerts`.

## Скорость запуска

Прошивка ставит и обновляет приложения с `pm.dexopt.install=interpret-only`, а фоновая компиляция (`bg-dexopt`) на ТВ не запускается, поэтому приложения всегда работают в интерпретаторе. Холодный старт (`am force-stop`, затем `am start -W`, 3 прогона):

| Приложение | Первый экран | Главный экран от запуска |
|---|---|---|
| КиноПоиск, interpret-only → speed | 2,0–2,6 с → 1,6–2,8 с | 10,5–13 с → 8,7–10,4 с |
| RUTUBE, interpret-only → speed | 3,6–4,0 с → 1,9–3,1 с | 6,5–7,5 с → 4,0–5,1 с |
| VK Видео, interpret-only → speed | 2,3–3,4 с → 2,5–2,8 с | тот же экран |

Оставшиеся ~6 с на заставке КиноПоиска — загрузка из сети. `speed` занимает ~50–110 МБ на `/data` на приложение.

## Память

| | Родное | После |
|---|---|---|
| Лаунчер | 45–75 МБ, ~15% CPU в простое | 18–25 МБ, 0% CPU |
| Доступно в простое | ~400 МБ | ~440–480 МБ |

При просмотре в КиноПоиске (~250 МБ PSS) доступно ~105–130 МБ. zRAM (200 МБ) заполняется, и система закрывает фоновые процессы: сервисы Google, лаунчер. Сам плеер не закрывается. Увеличение zRAM проверено и не помогает:

| zRAM / swappiness | Доступно | Закрыто | CPU kswapd |
|---|---|---|---|
| 200 МБ / 150 (штатно) | 103–130 МБ | passport, GMS, лаунчер | ~1% |
| 400 МБ / 150 | 115–117 МБ | ничего | ~23% постоянно |
| 300 МБ / 100 | 122–136 МБ | passport, GMS, лаунчер | ~1% |

Root-настройки убирают пробуждения на загрузке:
- КиноПоиск (~70 МБ) и YouTube публикуют рекомендации для главного экрана;
- процесс AppMetrica КиноПоиска (~40 МБ) больше не запускается. SDK раз в 5 секунд пишет в журнал о повторной попытке, это безвредно;
- EShare (`PERSISTENT`) стартует даже отключённым, поэтому он ещё и скрыт через `pm hide`. Если скрыть его на работающей системе, он один раз упадёт с окном «произошёл сбой»; после перезагрузки окно не появится.

## Клавиатура

Штатная LatinIME в поле поиска RUTUBE не получает фокус с пульта. Система может сама вернуть LatinIME по умолчанию (замечено после обновления LeanKey), поэтому LatinIME отключается командой `ime disable`. Выбор клавиатуры в меню настроек на этой прошивке падает, поэтому используется `ime set`.

## Wi‑Fi

Модуль AltoBeam (драйвер `atbm`, одно радио). Wi‑Fi Direct (приёмник Miracast «Wifi дисплей», `com.mediatek.androidbox`) слушает социальный канал 6. Если роутер на другом канале, модуль пропускает его маяки (`AP lost`), и связь возвращается только через ~2 минуты (`Auto connect disabled`). Помогает роутер на канале 6 или кабель Ethernet (100 Мбит/с).

## adb

- Окно подтверждения отладки появляется, только если отладка включена в «Для разработчиков» Android, а не через сервисное меню.
- Однажды adbd перестал отвечать (соединение принимается, `device offline`) после нескольких циклов `adb root`/`adb unroot`; процесс при этом был жив. Помогла перезагрузка. Скрипты переключаются в root один раз.

## Родной лаунчер и TvApi

Родной лаунчер `com.cvte.tv.launcher.fall` (`/system/priv-app/NebulaLauncher`) работает с ТВ-сервисом CVTE (`com.cvte.tv.api.impl`) через `cn.cvte.tv.api` из `BOOTCLASSPATH`. Любое приложение может пользоваться им без системной подписи:

- `TvApiSDKManager.init(Application)`, затем `getInstance().getTvApi(TvServiceConnection)`. Слушатели хранятся через `WeakReference`, поэтому держите на них сильную ссылку.
- `ITvApiManager.getTVApiSystemInputSource()`: `eventSystemInputSourceGetList()`, `eventSystemInputSourceGetInputSource()`, `eventSystemInputSourceSetInputSource(id)`.
- `ITvApiManager.getTVApiScreenWindow()`: `eventScreenWindowSetFull()`, `eventScreenWindowSetPipValue(x, y, w, h)`, `eventScreenWindowMute(boolean)`.
- Переход на вход: `SetInputSource(id)`, затем `SetFull()`, затем `startActivity(MAIN + категория TV_HOME)` (обрабатывает `com.cvte.tv.androidsetting/.TvSettingActivity`).
- Превью на главном экране: прозрачная дыра в окне плюс `SetPipValue`; при уходе — `Mute(true)`.
- Mali-450: `clipPath` не учитывает трансформацию холста. Скругления сделаны через `BitmapShader` + `drawRoundRect`, увеличение в фокусе — через `canvas.scale` в `onDraw`.
