#!/usr/bin/env python3
"""Genera updates/<edición>.json a partir de los APK publicados.

Lo usa .github/workflows/release.yml después de compilar las dos
ediciones. También sirve a mano:

    python tools/update_manifest.py \
        --edition full \
        --version-name 5.4 --version-code 5400 \
        --application-id com.angelonmx.reloquence \
        --apk-dir dist/full/release \
        --base-url https://github.com/angelonmx/reloquenceTTS/releases/download/v5.4 \
        --output updates/full.json

El manifiesto es lo que lee la app: un JSON pequeño, sin token y sin
API de GitHub. Cada APK lleva su tamaño y su SHA-256; la app no
instala nada que no coincida.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ABI_ORDER = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal"]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_notes(path: Path | None, edition: str, version_code: int) -> dict:
    if path is None or not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"aviso: no se pudo leer {path}: {error}", file=sys.stderr)
        return {}
    for entry in data.get("editions", {}).get(edition, []):
        if entry.get("versionCode") == version_code:
            notes = entry.get("notes", {})
            if isinstance(notes, dict):
                return notes
    return {}


def collect_apks(
    apk_dir: Path,
    edition: str,
    version_name: str,
    version_code: int,
    base_url: str,
) -> list[dict]:
    # El nombre completo es
    # reloquence-<edición>-<versión>-<código>-<abi>-release.apk.
    # Se busca el prefijo exacto en vez de partir por guiones porque
    # un versionName puede llevarlos ("5.5-beta").
    prefix = f"reloquence-{edition}-{version_name}-{version_code}-"
    suffix = "-release.apk"
    found: list[dict] = []
    for apk in sorted(apk_dir.glob("*.apk")):
        name = apk.name
        if not name.startswith(prefix) or not name.endswith(suffix):
            continue
        abi = name[len(prefix) : -len(suffix)]
        if not abi:
            print(f"aviso: nombre de APK inesperado: {name}", file=sys.stderr)
            continue
        found.append(
            {
                "abi": abi,
                "url": f"{base_url.rstrip('/')}/{name}",
                "sha256": sha256(apk),
                "size": apk.stat().st_size,
            }
        )
    if not found:
        raise SystemExit(f"no hay APK de {edition} {version_name} en {apk_dir}")
    found.sort(
        key=lambda item: (
            ABI_ORDER.index(item["abi"]) if item["abi"] in ABI_ORDER else len(ABI_ORDER),
            item["abi"],
        )
    )
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--edition", required=True, choices=["full", "lite"])
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--application-id", required=True)
    parser.add_argument("--apk-dir", required=True, type=Path)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--notes",
        type=Path,
        default=Path("app/src/main/assets/changelog.json"),
        help="changelog del que se copian las notas de esta versión",
    )
    parser.add_argument("--published-at", default=None)
    parser.add_argument("--min-sdk", type=int, default=23)
    args = parser.parse_args()

    if not args.base_url.lower().startswith("https://"):
        raise SystemExit("--base-url tiene que ser HTTPS")
    if not args.apk_dir.is_dir():
        raise SystemExit(f"no existe la carpeta de APK: {args.apk_dir}")

    apks = collect_apks(
        args.apk_dir,
        args.edition,
        args.version_name,
        args.version_code,
        args.base_url,
    )
    published = args.published_at or (
        datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    )
    notes = load_notes(args.notes, args.edition, args.version_code)
    if not notes:
        print(
            f"aviso: no hay notas para {args.edition} {args.version_name} "
            f"({args.version_code}) en {args.notes}",
            file=sys.stderr,
        )
    manifest = {
        "schema": 1,
        "applicationId": args.application_id,
        "edition": args.edition,
        "versionName": args.version_name,
        "versionCode": args.version_code,
        "publishedAt": published,
        "minSdk": args.min_sdk,
        "notes": notes,
        "apks": apks,
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"{args.output}: {args.edition} {args.version_name} ({args.version_code}), "
        f"{len(apks)} APK(s)"
    )
    for apk in apks:
        print(f"  {apk['abi']}: {apk['size']} bytes, {apk['sha256'][:16]}…")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
