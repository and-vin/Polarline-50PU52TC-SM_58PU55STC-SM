"""Film search for the Alice skill: turns a title into commands the TV service understands.

Standard library only, so it runs as is in Yandex Cloud Functions (python312).

Sources:
- RUTUBE site search (no auth) -> full-length videos, opened by id.
- kinopoiskapiunofficial.tech (env KINOPOISK_API_KEY) -> canonical title, year, length.
  It answers in 7-10 s, so it runs in parallel with a deadline and is cached.
- VK Video and КиноПоиск: no usable search API, so the TV opens the in-app search.

Command format for the TV service (docs/alice-plan.md, step 1):
  {"app": "rutube", "action": "open", "uri": "rutube://rutube.ru/video/<id>/"}
  {"app": "vk", "action": "search", "uri": "vkvideo://video/search?query=<query>"}
  {"app": "kinopoisk", "action": "search_keys", "query": "<query>"}
  {"app": "kinopoisk", "action": "open", "uri": "kpatv://film?filmId=<hd_id>"}  (when an HD id is known)
"""

import json
import os
import re
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, wait

RUTUBE_URL = "https://rutube.ru/pangolin/api/web/v1/search/combined/video_playlist"
KP_URL = "https://kinopoiskapiunofficial.tech/api/v2.1/films/search-by-keyword"
UA = "Mozilla/5.0 (Linux; Android 7.0) Kinovecher/0.1"

MIN_FILM_SEC = 60 * 60
MIN_EPISODE_SEC = 18 * 60
GOOD_CATEGORIES = {"Фильмы", "Сериалы", "Мультфильмы", "Аниме", "Разное", "Телепередачи"}
JUNK = re.compile(
    r"трейлер|trailer|тизер|обзор|разбор|анализ|смысл|киноляп|ляп|приколы|shorts|#|"
    r"саундтрек|soundtrack|\bost\b|музык|music|asmr|стрим|смотрим|реакци|reaction|"
    r"озвучк[аи] от|пародия|интервью|за кадром|как снимал|фанат|fan ?made|gameplay|"
    r"о фильме|стоит посмотреть|премьера|в кинотеатр|отзыв|рецензи|сравни|вырезанн|сцен[аы]|"
    r"лучшие моменты|нарезка|финал сериала|все смерти",
    re.IGNORECASE,
)

# Words that may follow the title in a video name without making it a different film.
TITLE_TAIL_OK = {
    "фильм", "кино", "кинофильм", "сериал", "серия", "сезон", "комедия", "драма", "боевик", "мелодрама",
    "фантастика", "триллер", "ужасы", "мультфильм", "реж", "режиссер", "год", "смотреть", "онлайн",
    "hd", "full", "fullhd", "4k", "1080p", "720p", "в", "хорошем", "качестве", "ссср", "россия", "все",
    "серии", "полностью", "версия", "the", "к", "ф", "военный", "исторический", "советский",
    "художественный", "семейный", "приключения", "детектив",
}
TITLE_HEAD_OK = {"к", "ф", "кф", "фильм", "кинофильм", "сериал", "мультфильм", "смотреть", "онлайн"}
DEMOTE = re.compile(r"тифлокомментари|сурдоперевод|субтитр", re.IGNORECASE)

# Separate pools: slow КиноПоиск lookups must not starve the RUTUBE requests.
_kp_pool = ThreadPoolExecutor(max_workers=4)
_rt_pool = ThreadPoolExecutor(max_workers=4)
_http_pool = ThreadPoolExecutor(max_workers=8)
_kp_cache = {}


def _get_json(url, params, headers=None, timeout=10.0):
    req = urllib.request.Request(
        url + "?" + urllib.parse.urlencode(params),
        headers={"User-Agent": UA, "Accept": "application/json", **(headers or {})},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def _norm(s):
    s = s.lower().replace("ё", "е")
    return re.sub(r"[^\w]+", " ", s).strip()


def kinopoisk_lookup(title, timeout=10.0):
    """Best КиноПоиск match: {kp_id, title, year, minutes, is_series} or None."""
    key = os.environ.get("KINOPOISK_API_KEY")
    if not key:
        return None
    if title in _kp_cache:
        return _kp_cache[title]
    d = _get_json(KP_URL, {"keyword": title, "page": 1}, {"X-API-KEY": key}, timeout)
    films = [f for f in d.get("films", []) if f.get("nameRu") or f.get("nameEn")]
    # The API orders by popularity: "Холоп" comes back as "Холоп 3" first. Prefer an exact name.
    exact = [f for f in films if _norm(title) in (_norm(f.get("nameRu") or ""), _norm(f.get("nameEn") or ""))]
    f = (exact or films or [None])[0]
    best = None
    if f:
        m = re.match(r"(\d+):(\d+)", f.get("filmLength") or "")
        best = {
            "kp_id": f["filmId"],
            "title": f.get("nameRu") or f.get("nameEn"),
            "year": (f.get("year") or "")[:4],
            "minutes": int(m.group(1)) * 60 + int(m.group(2)) if m else None,
            "is_series": f.get("type") in ("TV_SERIES", "MINI_SERIES", "TV_SHOW"),
        }
    _kp_cache[title] = best
    return best


def _rutube_search(query, timeout):
    d = _get_json(RUTUBE_URL, {"query": query, "client": "wdp", "page": 1}, timeout=timeout)
    return [r for r in d.get("results", []) if "duration" in r and "id" in r]


def _sequel_mismatch(video_title, film_title):
    """'Интерстеллар 2 (2027)' for 'Интерстеллар': a number right after the title that the title lacks."""
    vt, ft = _norm(video_title), _norm(film_title)
    i = vt.find(ft)
    if i < 0:
        return False
    rest = vt[i + len(ft):].split()
    return bool(rest) and rest[0].isdigit() and len(rest[0]) <= 2 and not re.search(r"\d", ft)


def _title_fit(video_title, film_title):
    """How well a video name matches the film title: None (no match), 0 (loose) or 1 (clean)."""
    vt, ft = _norm(video_title).split(), _norm(film_title).split()
    n = len(ft)
    for i in range(len(vt) - n + 1):
        if vt[i:i + n] != ft:
            continue
        head = vt[:i]
        tail = vt[i + n:]
        clean_head = all(w in TITLE_HEAD_OK for w in head)
        clean_tail = not tail or tail[0].isdigit() or tail[0] in TITLE_TAIL_OK
        return 1 if clean_head and clean_tail else 0
    return None


def rutube_candidates(title, year=None, minutes=None, series=False, timeout=2.5):
    """Full-length RUTUBE videos for a title, best first."""
    queries = [title, f"{title} {year}" if year else f"{title} фильм"]
    futures = [_http_pool.submit(_rutube_search, q, timeout) for q in queries]
    seen, results = set(), []
    for f in futures:
        try:
            batch = f.result(timeout=timeout)
        except Exception:
            continue
        for r in batch:
            if r["id"] not in seen:
                seen.add(r["id"])
                results.append(r)
    min_sec = MIN_EPISODE_SEC if series else MIN_FILM_SEC
    out = []
    for r in results:
        vtitle = r.get("title") or ""
        cat = (r.get("category") or {}).get("name")
        if r.get("is_livestream") or r.get("is_paid") or r["duration"] < min_sec:
            continue
        if cat and cat not in GOOD_CATEGORIES:
            continue
        fit = _title_fit(vtitle, title)
        # fit 0 is a different work that only contains the title ("Остров сокровищ", "Группа «Дюна»").
        if not fit or JUNK.search(vtitle) or _sequel_mismatch(vtitle, title):
            continue
        score = min(r.get("hits") or 0, 1_000_000) ** 0.5
        if DEMOTE.search(vtitle):
            score -= 400
        if r.get("is_official"):
            score += 300
        # With КиноПоиск data: a clearly different length or year means a different film
        # ("Начало" 1970, 86 min, for Nolan's "Начало" 2010, 148 min).
        years = set(re.findall(r"\b(?:19|20)\d\d\b", vtitle))
        if year and years and year not in years:
            continue
        if minutes and not series:
            off = abs(r["duration"] / 60 - minutes)
            if off > 30:
                continue
            score += 200 if off <= 10 else 0
        if year and year in years:
            score += 200
        out.append({
            "app": "rutube",
            "action": "open",
            "uri": f"rutube://rutube.ru/video/{r['id']}/",
            "title": vtitle,
            "minutes": r["duration"] // 60,
            "author": (r.get("author") or {}).get("name"),
            "score": round(score),
        })
    out.sort(key=lambda c: -c["score"])
    return out


def search(title, deadline=2.5):
    """Candidates for a spoken title within `deadline` seconds.

    Returns {"film": <КиноПоиск match or None>, "candidates": [...]} where candidates are
    TV commands in preference order: RUTUBE videos, then КиноПоиск and VK Video searches.
    """
    t0 = time.monotonic()
    # КиноПоиск is slow (7-10 s): it runs in the background, fills the cache for the next
    # request and is used now only if it is already cached or answers before RUTUBE does.
    film = _kp_cache.get(title)
    kp_future = None if title in _kp_cache else _kp_pool.submit(kinopoisk_lookup, title, 15.0)
    # Search RUTUBE by the spoken title; КиноПоиск data, if cached, adds the year and length.
    args = (title, film["year"], film["minutes"], film["is_series"]) if film else (title,)
    rt_future = _rt_pool.submit(rutube_candidates, *args, timeout=deadline)
    wait([rt_future], timeout=deadline)

    if film is None and kp_future and kp_future.done() and not kp_future.exception():
        film = kp_future.result()
    rutube = rt_future.result() if rt_future.done() and not rt_future.exception() else []

    name = film["title"] if film else title
    candidates = rutube[:5] + [
        {"app": "kinopoisk", "action": "search_keys", "query": name},
        {"app": "vk", "action": "search",
         "uri": "vkvideo://video/search?query=" + urllib.parse.quote(name)},
    ]
    return {"film": film, "candidates": candidates, "seconds": round(time.monotonic() - t0, 2)}


if __name__ == "__main__":
    import sys
    print(json.dumps(search(" ".join(sys.argv[1:]) or "Интерстеллар"), ensure_ascii=False, indent=2))
