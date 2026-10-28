# "Киновечер" TV agent

[Русский](README.md)

A ~13 KB APK without AndroidX: a service keeps an outbound long-poll to the command queue (the cloud function or `alice/devqueue.py`) and opens films. No port forwarding needed. It starts after boot and reconnects by itself after network drops. Memory: about 3 MB PSS.

## Commands

| Command | Does |
|---|---|
| `{"app": "rutube", "action": "open", "uri": "rutube://rutube.ru/video/<id>/"}` | RUTUBE video |
| `{"app": "vk", "action": "search", "uri": "vkvideo://video/search?query=<query>"}` | VK Video search |
| `{"app": "kinopoisk", "action": "open", "uri": "kpatv://film?filmId=<hd_id>"}` | КиноПоиск card; the account prompt is confirmed automatically |
| `{"app": "kinopoisk", "action": "search_keys", "query": "<query>"}` | КиноПоиск search: Latin via `input text`, Cyrillic via presses on the app's keyboard |
| `{"action": "ping"}` | connectivity check |

Protocol: `GET <url>/tv/poll` (`Authorization: Bearer <secret>`) → 200 with a command, or 204 after ~25 s; reply `POST <url>/tv/ack` `{"id", "ok", "error"}`.

## Key presses

A normal app cannot press keys in other apps, the shell user can. So the agent connects to the TV's own adb (`127.0.0.1:5555`, enabled by the firmware) with its own RSA key and runs `input ...`.

- On the first connection the TV shows "Allow USB debugging?" for the agent's key — choose "Always allow from this computer".
- adbd on Android 7 keeps only one TCP connection: while a computer is connected (`adb connect`), the agent cannot press keys (links still work). The rejected connection hangs until adbd restarts.

## Build and install

```
./build.sh <android-sdk> <jdk-17>          # build/tvagent.apk; signing key in keystore/ (not in git)
adb install -r build/tvagent.apk
adb shell am startservice -n com.andvin.tvagent/.AgentService --es url <queue URL> --es secret <secret>
```

Testing without the cloud: on a computer `python3 alice/devqueue.py --secret <secret>`, queue URL `http://<computer IP>:8787`, commands via `curl -X POST <IP>:8787/send -H 'Authorization: Bearer <secret>' -d '<command>'`.
