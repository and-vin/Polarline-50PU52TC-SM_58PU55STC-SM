"""Runs search() on a fixed list of titles and prints the top candidates and timings.

KINOPOISK_API_KEY=... python3 alice/check_search.py
"""

import time

from search import search

TITLES = [
    "Интерстеллар", "Брат 2", "Иван Васильевич меняет профессию", "Москва слезам не верит",
    "Дюна", "Начало", "Холоп", "Т-34", "Кин-дза-дза", "Остров", "Ликвидация", "Игра престолов",
]

if __name__ == "__main__":
    for t in TITLES:
        t0 = time.monotonic()
        r = search(t)
        f = r["film"]
        print(f"== {t}: {time.monotonic() - t0:.1f} s, КиноПоиск: "
              + (f"{f['title']} ({f['year']}, {f['minutes']} мин, kp {f['kp_id']})" if f else "—"))
        rutube = [c for c in r["candidates"] if c["app"] == "rutube"]
        for c in rutube[:3]:
            print(f"   {c['score']:>5} {c['minutes']:>4} мин  {c['author']} | {c['title'][:70]}")
        if not rutube:
            print("   RUTUBE: ничего")
