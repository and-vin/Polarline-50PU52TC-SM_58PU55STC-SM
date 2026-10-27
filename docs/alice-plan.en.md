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
- **КиноПоиск title search:** the unofficial `kinopoiskapiunofficial.tech` (`/api/v2.1/films/search-by-keyword?keyword=`) needs a free key (`X-API-KEY`); without it, `401`. Unofficial APIs break from time to time.
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

## Next steps

2. TV service: APK with an outbound connection, commands `open(app, ref)` / `search(app, query)` / `show_list(items)`, start on boot; measure RAM.
3. Cloud function + queue; a private skill in the Yandex Dialogs console, intents "recommend / another / play / on <app>".
4. Recommendations based on the owner's taste.
5. End-to-end voice test from the station; document in `README.md`, `README.en.md` and `AGENTS.md`.
