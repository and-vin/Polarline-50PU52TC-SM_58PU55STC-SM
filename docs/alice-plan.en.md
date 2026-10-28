# Plan: Alice → a film on the TV

[Русский](alice-plan.md)

Goal: ask Alice on a Yandex station (e.g. "Alice Mini") to recommend a film, then say "play it" and have the film open on the TV in КиноПоиск, RUTUBE or VK Video.

Status: plan only, nothing implemented. Read [AGENTS.md](../AGENTS.md) before working on the TV.

## Architecture

```
Station ──voice──▶ Yandex Dialogs ──webhook──▶ cloud function (skill)
                                                   │  film lookup (КиноПоиск API / RUTUBE)
                                                   ▼
                                             command queue
                                                   ▲  outbound connection (long-poll / WebSocket)
                                                   │
                                 TV service (APK, root) ──am start──▶ app
```

1. **A private Alice skill** (e.g. "Киновечер"). The whole conversation happens inside the skill: "recommend" → a film → "another" / "play". Short form: "Alice, ask Киновечер to play Dune".
2. **A cloud function** (Yandex Cloud Functions or similar): handles phrases, looks up the film, queues a command.
3. **A TV service** — a small APK (like `launcher/`, no AndroidX) that keeps an outbound connection to the cloud, so no router port forwarding is needed. On a command it wakes the screen and opens the film. Android 7 allows starting activities from the background (restricted only since Android 10). Memory budget: 10–15 MB.
4. **App choice:** explicit ("on RUTUBE") or in order КиноПоиск → RUTUBE → VK Video. With several candidates (film, trailer, review) — a list on the TV screen, picked by voice ("the second one") or remote.

## Findings

- **The context of a normal conversation with Alice is not available.** A third-party skill does not see earlier turns. [ha-yandex-station-intents](https://github.com/dext0r/ha-yandex-station-intents) only matches a predefined phrase list (no wildcards). [YandexStation](https://github.com/AlexxIT/YandexStation): the spoken phrase cannot be read, and Alice's text answer is returned only for a limited set (weather, time, smart home). So the recommendation has to come from the skill itself.
- **Skill:** a private skill is moderated only for its activation name and icon. Name: 2–5 words, without "Яндекс", "Алиса", "Станция". The skill must answer in ~3 s, at most 1 server error per 10 requests ([requirements](https://yandex.ru/dev/dialogs/alice/doc/ru/requirements)).
- **RUTUBE:** the site search `GET https://rutube.ru/pangolin/api/web/v1/search/combined/video_playlist?query=<text>&client=wdp&page=1` works without auth and returns `id` (32 hex), `title`, `duration` (s), `author.name`, `is_official`. The old `/api/search/video/` returns an empty list. Results are noisy (trailers, music, fakes like "Interstellar 2 (2027)") — filter by duration (≥ 60 min) and author, show doubtful matches as a list.
- **КиноПоиск title search:** the unofficial `kinopoiskapiunofficial.tech` (`/api/v2.1/films/search-by-keyword?keyword=`) needs a free key (`X-API-KEY`); without it, `401`. Unofficial APIs break from time to time. Tested on 2026-10-28 with a key: search finds the film and returns the numeric kp id (`Интерстеллар` → 258687), but takes 7–10 s — over the skill's ~3 s limit, so it needs a cache or an earlier lookup. `/api/v2.2/films/<kp id>/external_sources` lists other streaming services; it has no КиноПоиск HD link or HD id.
- **VK Video:** the VK API search needs a user token — most likely only opening the in-app search screen.
- **Other apps:** only launching them or passing text to their search, if they accept it.
- **Risk:** VK Video for Android TV 2.118 needs Android 9 while the TV runs 2.22 — apps on this firmware will soon be stuck on old versions, and integrations may break once services drop them.

## Step 1: which links the apps accept (on the TV)

Which links (`VIEW` with `scheme/host/path`) and search intents (`android.intent.action.SEARCH`, extra `query`) each app accepts is not documented anywhere — check on the TV.

PowerShell (VPN off):

```powershell
adb connect <TV IP>:5555
foreach ($p in 'ru.kinopoisk.tv','rtb.mobile.android','com.vk.tv') {
  "=== $p"
  adb shell "dumpsys package $p | grep -E 'Scheme|Authority|Path|Action:|android.intent.action.(VIEW|SEARCH)' | sort -u"
}
```

Then try launches, for example:

```
adb shell am start -a android.intent.action.VIEW -d "https://rutube.ru/video/<id>/" rtb.mobile.android
adb shell am start -a android.intent.action.VIEW -d "https://www.kinopoisk.ru/film/258687/" ru.kinopoisk.tv
adb shell am start -a android.intent.action.SEARCH -e query "Interstellar" <package>
```

Result: a table "app → method: film link / search screen with the query / typing via key presses (`input text` as root, fallback)".

### Result (2026-10-28)

Tested with КиноПоиск 2.262.1, RUTUBE 31.14.2.TV-google and VK Video 2.22. The query in a link is URL-encoded (`%D0%98…`).

| App | Video link | Search screen with the query | `SEARCH` + `query` |
|---|---|---|---|
| RUTUBE | works: `rutube://rutube.ru/video/<id>/` → player | works: `rutube://rutube.ru/search/?query=<query>` → results | not accepted |
| VK Video | works: `https://vk.com/video<owner>_<id>` → starts playing | works: `vkvideo://video/search?query=<query>` → results | filter exists, query ignored |
| КиноПоиск | works only with an HD id: `kpatv://film?filmId=<hd_id>` or `https://hd.kinopoisk.ru/film/<hd_id>` → card; how to get the HD id from a title without signing in is not established | not via links; typing via key presses works (see below) | filter exists, query ignored |

Working commands:

```
adb shell am start -a android.intent.action.VIEW -d "rutube://rutube.ru/video/<id>/" rtb.mobile.android
adb shell am start -a android.intent.action.VIEW -d "'rutube://rutube.ru/search/?query=<query>'" rtb.mobile.android
adb shell am start -a android.intent.action.VIEW -d "https://vk.com/video<owner>_<id>" com.vk.tv
adb shell am start -a android.intent.action.VIEW -d "'vkvideo://video/search?query=<query>'" com.vk.tv
adb shell am start -a android.intent.action.VIEW -d "'kpatv://film?filmId=<hd_id>'" ru.kinopoisk.tv
```

Details:

- **RUTUBE:** `https://rutube.ru/...` is not accepted (`unable to resolve Intent`), only the `rutube://rutube.ru` scheme (`ru.rutube.app.SplashActivity`). 18+ videos first ask "Are you 18?", with "No" selected by default.
- **VK Video:** all links go to `com.vk.tv/.presentation.base.links.TvLinkRedirectActivity` (`https://vk.com/video.*`, `vkvideo://video|vk.com/.*`). That activity has a `SEARCH` filter without the `DEFAULT` category: without `-n` the intent does not resolve, and with `-n` the app opens its home screen. Cold start is about 30 s.
- **КиноПоиск, film link:** the КиноПоиск HD id is 32 hex (e.g. `07e9b11ab8574bd3a863184ad89fd72e`), not the kinopoisk.ru id. With a numeric id (`258687`, `43395`) the card opens with "Server unavailable". The format was found in `/data/data/ru.kinopoisk.tv/databases/YandexPlayer.db` (root, read only): the app puts `kpatv://film/?filmId=<hd_id>&utm_source=androidtv&utm_medium=channels` links (base64 twice) into the Android TV recommendations. Both `kpatv://film?filmId=<hd_id>` and `https://hd.kinopoisk.ru/film/<hd_id>` open `HdContentCardActivity` with the card in about 6 s when the app is already running. `https://www.kinopoisk.ru/film/...` is not accepted. No public request "title or kp id → HD id" without signing in was found.
- **КиноПоиск, search:** links do not open it. The app has a `kpatv://home` handler with a `search_query` parameter, but `kpatv://home/search?search_query=…` and `kpatv://home?page=search&search_query=…` open the home screen. SEARCH does not resolve without `-n`; `.splash.impl.presentation.SplashScreenActivity` is disabled, `DeepLinkTargetActivity` opens the home screen. Typing via key presses works, no root needed:

  ```
  adb shell am start -n ru.kinopoisk.tv/.presentation.splash.SplashActivity   # ~15 s to the home screen
  # if "Choose an account" (SelectFamilyAccountActivity) opens: adb shell input keyevent 23
  adb shell input keyevent 21   # left, into the side menu
  adb shell input keyevent 19   # up, to "Search"
  adb shell input keyevent 23   # OK, the search screen opens with the cursor in the field
  adb shell input text Interstellar   # results in ~5–8 s
  ```

  `input text` types ASCII only; Cyrillic needs another way (e.g. an IME app, or a Latin-script query). The key sequence depends on where the focus is on the home screen; the service should check `mCurrentFocus` after each step.
- **КиноПоиск, account choice:** on a cold start and for many links КиноПоиск shows "Choose an account" (family account). After `input keyevent 23` a film link is kept, a search link is lost.
- **Memory:** during the tests the system killed КиноПоиск, RUTUBE, the launcher and Google services at once (`am_proc_died`). The service should close other video apps before opening a link.

## Step 3: film search module

`alice/search.py` uses only the Python standard library, so it fits Yandex Cloud Functions. `search("<title>")` returns КиноПоиск data (if any) and a list of commands for the TV service in the step 1 format: RUTUBE videos (`open`), then КиноПоиск search (`search_keys`, typing via key presses) and VK Video search (`search`). The КиноПоиск key comes from the `KINOPOISK_API_KEY` environment variable.

- RUTUBE is queried twice in parallel ("<title>" and "<title> фильм" or "<title> <year>"). Filtered out: shorter than 60 min (18 min for series), paid videos and streams, categories outside "Фильмы/Сериалы/…", trailers, reviews, music, clip compilations, "sequels" like "Интерстеллар 2", videos where the title is part of another name ("Остров сокровищ" for "Остров"). With КиноПоиск data, videos with another year in the name or a length more than 30 min off are dropped.
- КиноПоиск answers in 7–10 s, so it runs in the background and is cached: the first request for a title has no КиноПоиск data, a repeated one has it.
- Check: `KINOPOISK_API_KEY=… python3 alice/check_search.py` (12 titles, Russian films and series). Response 0.9–1.6 s. RUTUBE found the full film for 6 of 12: Интерстеллар, Иван Васильевич меняет профессию, Москва слезам не верит, Т-34, Кин-дза-дза!, Остров (Lungin). Not found: Брат 2, Дюна, Начало (2010), Холоп, Ликвидация, Игра престолов — for these the КиноПоиск and VK Video searches remain.

## Next steps

- **The TV is unreachable over the network while asleep (backlog).** The power button puts the TV into suspend-to-RAM (`ro.CVTE_EN_STR=1`): the whole SoC sleeps, Wi‑Fi included, even though `wifi_sleep_policy=2`. The TV answers neither ping nor adb, and the TV service cannot receive a command. Options: Wake-on-WLAN — the firmware has `MtkTvNetwork.setWifiWolCtl(boolean)`/`getWifiWolCtl()`, wake the TV with a magic packet from the LAN (e.g. the router or the station); blocking suspend (a wakelock as root) — higher standby power; the "STR" switch in TV settings — with STR off the TV powers off completely, so the network is gone too. Check what actually works on this firmware.

2. TV service: APK with an outbound connection, commands `open(app, ref)` / `search(app, query)` / `show_list(items)`, start on boot; measure RAM.
3. Cloud function + queue; a private skill in the Yandex Dialogs console, intents "recommend / another / play / on <app>".
4. Recommendations based on the owner's taste.
5. End-to-end voice test from the station; document in `README.md`, `README.en.md` and `AGENTS.md`.
