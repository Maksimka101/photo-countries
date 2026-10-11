#!/usr/bin/env python3
"""Лента пребываний по meta.json (см. probe.py) и полигонам стран Natural Earth."""
import json
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

HERE = Path(__file__).parent
NEAR_DEG = 0.3  # допуск для точек у берега/границы, не попавших в полигон
TRANSIT = timedelta(hours=24)
OWN = ("disk:/Фотокамера/", "disk:/Фотки/")  # только свои папки автозагрузки
HOLE_DAYS = 30  # дыра без фото внутри пребывания, о которой стоит предупредить


def load_countries():
    out = []
    for f in json.loads((HERE / "countries.geojson").read_text())["features"]:
        p, g = f["properties"], f["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        for rings in polys:
            xs = [x for x, _ in rings[0]]
            ys = [y for _, y in rings[0]]
            out.append((p.get("NAME_RU") or p["NAME"], (min(xs), min(ys), max(xs), max(ys)), rings))
    return out


def in_ring(x, y, ring):
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def locate(countries, lat, lon, cache={}):
    key = (round(lat, 2), round(lon, 2))
    if key in cache:
        return cache[key]
    name = None
    for n, (x0, y0, x1, y1), rings in countries:
        if x0 <= lon <= x1 and y0 <= lat <= y1 and in_ring(lon, lat, rings[0]) \
                and not any(in_ring(lon, lat, h) for h in rings[1:]):
            name = n
            break
    if name is None:
        best = NEAR_DEG ** 2
        for n, (x0, y0, x1, y1), rings in countries:
            if not (x0 - NEAR_DEG <= lon <= x1 + NEAR_DEG and y0 - NEAR_DEG <= lat <= y1 + NEAR_DEG):
                continue
            for x, y in rings[0]:
                d = (x - lon) ** 2 + (y - lat) ** 2
                if d < best:
                    best, name = d, n
    cache[key] = name
    return name


def load_points(countries):
    pts, lost = [], 0
    for it in json.loads((HERE / "meta.json").read_text()):
        if not it["path"].startswith(OWN):
            continue
        ex = it.get("exif") or {}
        lat, lon = ex.get("gps_latitude"), ex.get("gps_longitude")
        if lat is None or lon is None or not ex.get("date_time") or (lat == 0 and lon == 0):
            continue
        c = locate(countries, lat, lon)
        if c is None:
            lost += 1
            continue
        pts.append((datetime.fromisoformat(ex["date_time"]), c))
    pts.sort()
    return pts, lost


def load_ignores():
    # ignore.txt: "ГГГГ-ММ-ДД Страна" или "ГГГГ-ММ-ДД..ГГГГ-ММ-ДД Страна", даты по UTC
    out = []
    f = HERE / "ignore.txt"
    for line in f.read_text().splitlines() if f.exists() else []:
        line = line.split("#")[0].strip()
        if not line:
            continue
        span, country = line.split(None, 1)
        a, _, b = span.partition("..")
        out.append((a, b or a, country))
    return out


def apply_ignores(pts, ignores):
    keep = [p for p in pts
            if not any(a <= f"{p[0]:%Y-%m-%d}" <= b and p[1] == c for a, b, c in ignores)]
    return keep, len(pts) - len(keep)


def runs_of(pts):
    runs = []
    for t, c in pts:
        if runs and runs[-1]["c"] == c:
            r = runs[-1]
            r["hole"] = max(r["hole"], (t.date() - r["end"].date()).days)
            r["end"] = t
            r["n"] += 1
        else:
            runs.append({"c": c, "start": t, "end": t, "n": 1, "hole": 0})
    return runs


def days(a, b):
    return (b.date() - a.date()).days + 1


def main():
    countries = load_countries()
    pts, lost = load_points(countries)
    pts, ignored = apply_ignores(pts, load_ignores())
    runs = runs_of(pts)
    print(f"точек: {len(pts)}, вне стран: {lost}, в игноре: {ignored}\n")

    visits, total = Counter(), Counter()
    year = None
    for i, r in enumerate(runs):
        prev = runs[i - 1] if i else None
        nxt = runs[i + 1] if i + 1 < len(runs) else None
        lo = days(r["start"], r["end"])
        # верхняя граница: от дня после последнего фото до него до дня перед первым фото после
        hi = lo
        if prev:
            hi += (r["start"].date() - prev["end"].date()).days - 1
        if nxt:
            hi += (nxt["start"].date() - r["end"].date()).days - 1
        hi = max(hi, lo)
        # транзит: меньше суток, страны до и после разные,
        # и хотя бы с одной стороны фото из другой страны в тот же или соседний день
        short = bool(prev and nxt) and r["end"] - r["start"] < TRANSIT
        close = short and min((r["start"].date() - prev["end"].date()).days,
                              (nxt["start"].date() - r["end"].date()).days) <= 1
        transit = close and prev["c"] != nxt["c"]
        note = ""
        if transit:
            note = " · транзит?"
        elif short and prev["c"] == nxt["c"]:
            note = " · заезд?"
        elif r["hole"] > HOLE_DAYS:
            note = f" · дыра {r['hole']} дн."
        if not transit:
            visits[r["c"]] += 1
        total[r["c"]] += lo

        if r["start"].year != year:
            year = r["start"].year
            print(year)
        span = f"{lo}" if hi == lo else f"{lo}–{hi}"
        print(f"  {r['start']:%m-%d} {r['c']}: {span} дн., {r['n']} фото{note}")

    print("\nстраны (визитов / дней минимум):")
    for c, n in total.most_common():
        print(f"  {c}: {visits[c]} / {n}")


if __name__ == "__main__":
    main()
