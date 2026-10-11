#!/usr/bin/env python3
"""Разведка: что Я.Диск отдаёт по фото (пути, EXIF, GPS).

Токен: переменная YADISK_TOKEN или файл .token рядом со скриптом.
Сырые метаданные сохраняются в meta.json.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

API = "https://cloud-api.yandex.net/v1/disk/resources/files"
HERE = Path(__file__).parent
PAGE = 1000


def token():
    t = os.environ.get("YADISK_TOKEN")
    f = HERE / ".token"
    if not t and f.exists():
        t = f.read_text().strip()
    if not t:
        sys.exit("нет токена: YADISK_TOKEN или файл .token")
    return t


def fetch(tok, offset):
    q = urllib.parse.urlencode({"media_type": "image", "limit": PAGE, "offset": offset})
    req = urllib.request.Request(f"{API}?{q}", headers={"Authorization": f"OAuth {tok}"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)["items"]
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code}: {e.read().decode(errors='replace')[:300]}")


def folder(path):
    # "disk:/Фотокамера/x.jpg" -> "disk:/Фотокамера", "photounlim:/x.jpg" -> "photounlim:/"
    root, _, rest = path.partition(":/")
    parts = rest.split("/")
    return f"{root}:/{parts[0]}" if len(parts) > 1 else f"{root}:/"


def main():
    tok = token()
    items, offset = [], 0
    while True:
        page = fetch(tok, offset)
        # страница бывает короче limit и до конца списка — идём до пустой
        if not page:
            break
        items += page
        print(f"\rзагружено {len(items)}", end="", file=sys.stderr)
        offset += PAGE
    print(file=sys.stderr)

    (HERE / "meta.json").write_text(json.dumps(items, ensure_ascii=False))

    folders, exif_keys, mimes = Counter(), Counter(), Counter()
    gps = size = 0
    for it in items:
        folders[folder(it["path"])] += 1
        mimes[it.get("mime_type", "?")] += 1
        size += it.get("size", 0)
        ex = it.get("exif") or {}
        exif_keys.update(ex.keys())
        if "gps_latitude" in ex and "gps_longitude" in ex:
            gps += 1

    n = len(items)
    print(f"всего: {n}, {size / 2**30:.1f} ГБ")
    print(f"с GPS: {gps} ({100 * gps // max(n, 1)}%)")
    print("\nполя exif:")
    for k, c in exif_keys.most_common():
        print(f"  {c:6} {k}")
    print("\nтипы:")
    for k, c in mimes.most_common(6):
        print(f"  {c:6} {k}")
    print("\nпапки:")
    for k, c in folders.most_common(15):
        print(f"  {c:6} {k}")
    print("\nполя ресурса:", ", ".join(sorted({k for it in items[:50] for k in it})))


if __name__ == "__main__":
    main()
