#!/usr/bin/env python3
"""Упаковывает countries.geojson (Natural Earth) в компактный бинарный файл для приложения.

Запуск: python3 prototype/pack_countries.py — пишет shared/src/commonMain/composeResources/files/countries.bin.

Ключ страны — ISO 3166 alpha-2: ISO_A2, а где там не код (-99, у Тайваня CN-TW) — ISO_A2_EH. Территории без кода и там
(Сомалиленд, Северный Кипр, ледник Сиачен) в файл не попадают. Порядок полигонов сохраняется:
при поиске побеждает первый подошедший, как в trips.py.

Формат (читает CountryIndex.parse):
  "PCB1"
  varint: число полигонов
  полигон: 2 байта ASCII-кода, varint число колец (первое — внешнее, остальные — дыры)
  кольцо: varint число точек, затем на точку zigzag-varint приращения долготы и широты
          в единицах 1e-5° (у первой точки — от нуля)
"""
import json
from pathlib import Path

HERE = Path(__file__).parent
OUT = HERE.parent / "shared/src/commonMain/composeResources/files/countries.bin"
SCALE = 100_000


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return out


def zigzag(n):
    return varint((n << 1) ^ (n >> 63))


def main():
    polys, skipped = [], []
    for f in json.loads((HERE / "countries.geojson").read_text())["features"]:
        p, g = f["properties"], f["geometry"]
        code = next((c for c in (p["ISO_A2"], p.get("ISO_A2_EH", "")) if len(c) == 2 and c.isalpha()), None)
        if code is None:
            skipped.append(p["NAME"])
            continue
        for rings in g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]:
            polys.append((code, rings))

    out = bytearray(b"PCB1")
    out += varint(len(polys))
    for code, rings in polys:
        out += code.encode("ascii")
        out += varint(len(rings))
        for ring in rings:
            out += varint(len(ring))
            px = py = 0
            for x, y in ring:
                qx, qy = round(x * SCALE), round(y * SCALE)
                out += zigzag(qx - px)
                out += zigzag(qy - py)
                px, py = qx, qy

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(out)
    print(f"{OUT.relative_to(HERE.parent)}: {len(out)} байт, полигонов {len(polys)}, "
          f"стран {len({c for c, _ in polys})}, без кода: {', '.join(skipped) or '—'}")


if __name__ == "__main__":
    main()
