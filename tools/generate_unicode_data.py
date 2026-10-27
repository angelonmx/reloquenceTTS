#!/usr/bin/env python3
"""Genera los datos de emojis y símbolos que viajan con la app.

Entradas (sin tocar, tal y como se descargan):
  tools/data/cldr/<idioma>.xml          anotaciones CLDR
  tools/data/unicode/UnicodeData.txt    nombres oficiales Unicode
  tools/data/unicode/NameAliases.txt    alias oficiales Unicode

Salidas:
  app/src/main/assets/cldr/emoji-<idioma>.tsv.gz
  app/src/main/assets/unicode/names.tsv.gz

Formato: una línea por entrada, clave y nombre separados por un
tabulador. La clave es la secuencia de puntos de código en
hexadecimal, separada por espacios. La variación FE0F no va en la
clave: CLDR ya la quita y el texto de Android la trae a veces.
"""

from __future__ import annotations

import gzip
import os
import sys
import unicodedata
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLDR = os.path.join(ROOT, "tools", "data", "cldr")
UNICODE = os.path.join(ROOT, "tools", "data", "unicode")
OUT_CLDR = os.path.join(ROOT, "app", "src", "main", "assets", "cldr")
OUT_UNICODE = os.path.join(ROOT, "app", "src", "main", "assets", "unicode")

VARIATION = {0xFE0E, 0xFE0F}
SKIN = range(0x1F3FB, 0x1F400)
TAGS = range(0xE0000, 0xE0080)


def key_of(text: str) -> str | None:
    cps = [
        ord(ch)
        for ch in text
        if ord(ch) not in VARIATION and not (0xE0000 <= ord(ch) <= 0xE007F)
    ]
    if not cps:
        return None
    return " ".join(f"{cp:X}" for cp in cps)


def write_gzip(path: str, lines: list[str]) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = ("\n".join(lines) + "\n").encode("utf-8")
    with open(path, "wb") as fh:
        with gzip.GzipFile(fileobj=fh, mode="wb", compresslevel=9, mtime=0) as gz:
            gz.write(raw)


def cldr_table(path: str) -> dict[str, str]:
    table: dict[str, str] = {}
    root = ET.parse(path).getroot()
    for node in root.iter("annotation"):
        if node.get("type") != "tts":
            continue
        name = (node.text or "").strip()
        if not name:
            continue
        key = key_of(node.get("cp") or "")
        if key is None:
            continue
        # Un tono de piel no se dice: el nombre base ya está en la
        # tabla y el motor actual lo usa para todas las variantes.
        if any(cp in SKIN for cp in (int(x, 16) for x in key.split())):
            continue
        table.setdefault(key, name)
    return table


def unicode_names() -> list[str]:
    lines: list[str] = []
    for raw in open(os.path.join(UNICODE, "UnicodeData.txt"), encoding="utf-8"):
        fields = raw.rstrip("\n").split(";")
        if len(fields) < 3:
            continue
        cp = int(fields[0], 16)
        if cp < 0x80 or cp in VARIATION or cp in SKIN or cp in TAGS:
            continue
        category = fields[2]
        if not category.startswith("S"):
            continue
        name = fields[1].strip()
        if not name or name.startswith("<"):
            continue
        lines.append(f"{cp:X}\t{name}")

    known = {line.split("\t", 1)[0] for line in lines}
    aliases_path = os.path.join(UNICODE, "NameAliases.txt")
    if os.path.exists(aliases_path):
        for raw in open(aliases_path, encoding="utf-8"):
            if raw.startswith("#") or ";" not in raw:
                continue
            code, alias, kind = raw.rstrip("\n").split(";")[:3]
            cp = int(code, 16)
            if cp < 0x80 or cp in VARIATION or cp in SKIN or cp in TAGS:
                continue
            if f"{cp:X}" in known or kind not in ("correction", "control", "alternate"):
                continue
            name = alias.strip()
            if name and not name.startswith("<"):
                lines.append(f"{cp:X}\t{name}")
    return lines


def main() -> int:
    print(f"CLDR: {CLDR}")
    seen = 0
    for name in sorted(os.listdir(CLDR)):
        if not name.endswith(".xml"):
            continue
        lang = name[:-4]
        table = cldr_table(os.path.join(CLDR, name))
        lines = [f"{key}\t{value}" for key, value in table.items()]
        write_gzip(os.path.join(OUT_CLDR, f"emoji-{lang}.tsv.gz"), lines)
        print(f"  {lang}: {len(lines)} anotaciones")
        seen += len(lines)

    names = unicode_names()
    write_gzip(os.path.join(OUT_UNICODE, "names.tsv.gz"), names)
    print(f"Unicode: {len(names)} símbolos en names.tsv.gz")
    print(f"Total: {seen} anotaciones CLDR")
    return 0


if __name__ == "__main__":
    sys.exit(main())
