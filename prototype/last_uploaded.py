#!/usr/bin/env python3
"""Разведка: годится ли /resources/last-uploaded для обновления «только новое».

Проверяет: приходит ли exif, в каком порядке идут файлы, какой limit и offset метод принимает,
совпадает ли его выдача с хвостом полного списка. Токен — как в probe.py. Ничего не сохраняет.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from probe import token

BASE = "https://cloud-api.yandex.net/v1/disk/resources/"
FIELDS = "path,resource_id,created,modified,exif.gps_latitude,exif.gps_longitude,exif.date_time"


def get(tok, method, **params):
    """(HTTP-код, items или текст ошибки, секунды)."""
    prefix = "items." if method == "files" else ""
    params["fields"] = ",".join(prefix + f for f in FIELDS.split(","))
    req = urllib.request.Request(
        f"{BASE}{method}?{urllib.parse.urlencode(params)}", headers={"Authorization": f"OAuth {tok}"}
    )
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.load(r)["items"], time.time() - t
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:200], time.time() - t


def has_gps(it):
    ex = it.get("exif") or {}
    return "gps_latitude" in ex and "gps_longitude" in ex and "date_time" in ex


def is_desc(items, key):
    v = [it[key] for it in items]
    return all(a >= b for a, b in zip(v, v[1:]))


def main():
    tok = token()

    print("== полный список /files, страницы по 500 ==")
    full, offset = [], 0
    while True:
        code, page, sec = get(tok, "files", media_type="image", limit=500, offset=offset)
        if code != 200:
            sys.exit(f"HTTP {code}: {page}")
        print(f"  offset {offset:5}: {len(page):4} файлов, {sec:.1f} с")
        if not page:
            break
        full += page
        offset += 500
    print(f"  всего {len(full)}, с точкой {sum(map(has_gps, full))}")
    print(f"  по убыванию created: {is_desc(full, 'created')}, modified: {is_desc(full, 'modified')}")

    print("== /last-uploaded: limit ==")
    last = []
    for limit in (20, 500, 1000, 5000, 20000):
        code, items, sec = get(tok, "last-uploaded", media_type="image", limit=limit)
        if code != 200:
            print(f"  limit {limit:5}: HTTP {code} {items}")
            continue
        print(f"  limit {limit:5}: {len(items):5} файлов, {sec:.1f} с, с точкой {sum(map(has_gps, items))}")
        if len(items) > len(last):
            last = items
    if not last:
        sys.exit("last-uploaded ничего не отдал")

    print("== /last-uploaded: порядок и offset ==")
    print(f"  по убыванию created: {is_desc(last, 'created')}, modified: {is_desc(last, 'modified')}")
    print(f"  первый {last[0]['created']}, последний {last[-1]['created']}")
    code, shifted, _ = get(tok, "last-uploaded", media_type="image", limit=20, offset=20)
    if code != 200:
        print(f"  offset=20: HTTP {code} {shifted}")
    else:
        same = [it["resource_id"] for it in shifted] == [it["resource_id"] for it in last[20:40]]
        print(f"  offset=20: {'сдвигает выдачу' if same else 'не действует (выдача не сдвинулась)'}")

    print("== сравнение с полным списком ==")
    full_ids = {it["resource_id"] for it in full}
    last_ids = [it["resource_id"] for it in last]
    print(f"  в last-uploaded {len(last_ids)}, из них нет в полном списке: {sum(i not in full_ids for i in last_ids)}")
    print(f"  в полном списке, но нет в last-uploaded: {len(full_ids - set(last_ids))}")
    for key in ("created", "modified"):
        newest = [it["resource_id"] for it in sorted(full, key=lambda it: it[key], reverse=True)[: len(last)]]
        n = min(200, len(last))
        print(f"  первые {n} совпадают с самыми новыми по {key}: {set(newest[:n]) == set(last_ids[:n])}")
    by_id = {it["resource_id"]: it for it in full}
    diff = sum(1 for it in last if it["resource_id"] in by_id and it.get("exif") != by_id[it["resource_id"]].get("exif"))
    print(f"  exif отличается от полного списка у {diff} файлов")


if __name__ == "__main__":
    main()
