#!/usr/bin/env python3
"""Arma las notas de una release con el changelog de las dos ediciones."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def load_versions(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def entry_for(changelog: dict, edition: str, version_code: int) -> dict | None:
    for entry in changelog.get("editions", {}).get(edition, []):
        if entry.get("versionCode") == version_code:
            return entry
    return None


def notes_for(entry: dict | None, language: str) -> list[str]:
    if not entry:
        return []
    notes = entry.get("notes", {})
    return notes.get(language) or notes.get("en") or notes.get("es") or []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--versions", type=Path, default=Path("version.properties"))
    parser.add_argument(
        "--changelog", type=Path, default=Path("app/src/main/assets/changelog.json")
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    versions = load_versions(args.versions)
    changelog = json.loads(args.changelog.read_text(encoding="utf-8"))

    full_name = versions["full.versionName"]
    full_code = int(versions["full.versionCode"])
    lite_name = versions["lite.versionName"]
    lite_code = int(versions["lite.versionCode"])

    full = entry_for(changelog, "full", full_code)
    lite = entry_for(changelog, "lite", lite_code)

    lines: list[str] = [
        f"# reloquenceTTS {full_name}",
        "",
        "APK firmados de la edición completa y de la edición Lite. "
        "Android pedirá confirmación antes de instalar.",
        "",
    ]
    for note in notes_for(full, "es"):
        lines.append(f"- {note}")
    english = notes_for(full, "en")
    if english:
        lines += ["", "<details><summary>English</summary>", ""]
        for note in english:
            lines.append(f"- {note}")
        lines += ["", "</details>"]

    lines += [
        "",
        f"## reloquenceTTS Lite {lite_name}",
        "",
    ]
    for note in notes_for(lite, "es"):
        lines.append(f"- {note}")
    english = notes_for(lite, "en")
    if english:
        lines += ["", "<details><summary>English</summary>", ""]
        for note in english:
            lines.append(f"- {note}")
        lines += ["", "</details>"]

    lines += [
        "",
        "## Instalación",
        "",
        "1. Descarga el APK de tu arquitectura (arm64-v8a en casi todos los "
        "teléfonos modernos; armeabi-v7a en equipos antiguos; universal si no "
        "estás seguro).",
        "2. Abre el archivo y permite instalar apps desde esta fuente cuando "
        "Android lo pida.",
        "3. Confirma la instalación.",
        "",
        "La app también puede actualizarse desde Ajustes > Actualizaciones.",
        "",
    ]
    args.output.write_text("\n".join(lines), encoding="utf-8")
    print(f"{args.output}: notas de {full_name} y Lite {lite_name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
