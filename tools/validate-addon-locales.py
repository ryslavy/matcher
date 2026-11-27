#!/usr/bin/env python3
"""Validate all 8 Matcher GlassKit add-ons across all 31 supported locales.

Checks performed for every add-on x locale:
  1. Valid UTF-8 without BOM (0xEF 0xBB 0xBF forbidden).
  2. Well-formed Android XML (<resources>).
  3. Zero duplicate <string>/<plurals> keys across XML files in the same values-<lang>/.
  4. 100% coverage of all translatable <string> and <plurals> from base values/*.xml.
  5. Zero overrides of translatable="false" keys (e.g. weather temperature_unit).
  6. Non-empty translated values and valid Android apostrophe/quote escaping.
  7. Exact printf format-specifier parity (%1$s, %1$d, %1$+d, %%, etc.) on every string
     and every plural <item> (and mandatory quantity="other" on every <plurals>).
  8. Preserved trailing space inside double-quoted strings where base requires it
     (e.g. sleep-timer sched_5 / sched_11 / sched_12).
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ADDONS = [
    "brightness",
    "sleep-timer",
    "headphones",
    "bluetooth-connect",
    "quiet-hours",
    "weather",
    "world-clock",
    "capacity-stats",
]

TARGET_LOCALES = [
    "values-ar",
    "values-ast",
    "values-b+es+419",
    "values-b+sr+Latn",
    "values-bn",
    "values-ca",
    "values-cs",
    "values-da",
    "values-de",
    "values-es",
    "values-fi",
    "values-fr",
    "values-in",
    "values-it",
    "values-iw",
    "values-ko",
    "values-nb",
    "values-nl",
    "values-pl",
    "values-pt",
    "values-pt-rPT",
    "values-ru",
    "values-sk",
    "values-sv",
    "values-th",
    "values-tr",
    "values-uk",
    "values-uz",
    "values-vi",
    "values-zh-rCN",
    "values-zh-rTW",
]

FMT_RE = re.compile(r"%(?:\d+\$)?[+\-# 0,(]*\d*(?:\.\d+)?[sdfoxXeEgGaAcbBhHn%]")


def extract_formats(text: str) -> list[str]:
    tokens = FMT_RE.findall(text)
    non_pct = [t for t in tokens if t != "%%"]
    if len(non_pct) == 1:
        # Android allows both %d/%s and %1$d/%1$s when there is a single argument
        norm = []
        for t in tokens:
            if t != "%%" and "$" not in t:
                norm.append("%1$" + t[1:])
            else:
                norm.append(t)
        return sorted(norm)
    return sorted(tokens)


def check_android_escaping(text: str) -> str | None:
    """Validate Android string resource apostrophe/quote escaping."""
    stripped = text.strip()
    in_quotes = len(stripped) >= 2 and stripped.startswith('"') and stripped.endswith('"')
    if in_quotes:
        inner = stripped[1:-1]
        # Inside "..." unescaped double quotes are invalid unless preceded by backslash
        i = 0
        while i < len(inner):
            ch = inner[i]
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                return "unescaped double quote inside quoted string"
            i += 1
        return None

    # Outside "..." every ASCII apostrophe must be escaped as \'
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if ch == "'":
            return "unescaped ASCII apostrophe (use \\' or wrap in \"...\")"
        i += 1
    return None


def parse_xml_file(path: Path) -> tuple[dict[str, tuple[str, bool]], dict[str, tuple[dict[str, str], bool]], list[str]]:
    errors: list[str] = []
    raw_bytes = path.read_bytes()
    if raw_bytes.startswith(b"\xef\xbb\xbf"):
        errors.append(f"{path}: contains UTF-8 BOM")
    try:
        text = raw_bytes.decode("utf-8")
    except UnicodeDecodeError as exc:
        return {}, {}, [f"{path}: invalid UTF-8 ({exc})"]

    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        return {}, {}, [f"{path}: XML parse error ({exc})"]

    if root.tag != "resources":
        errors.append(f"{path}: root tag is <{root.tag}>, expected <resources>")

    strings: dict[str, tuple[str, bool]] = {}
    plurals: dict[str, tuple[dict[str, str], bool]] = {}
    for child in root:
        name = child.attrib.get("name")
        translatable = child.attrib.get("translatable", "true").lower() != "false"
        if child.tag == "string" and name:
            if name in strings:
                errors.append(f"{path}: duplicate <string name=\"{name}\"> in file")
            val = (child.text or "") + "".join(ET.tostring(sub, encoding="unicode") for sub in child)
            strings[name] = (val, translatable)
        elif child.tag == "plurals" and name:
            if name in plurals:
                errors.append(f"{path}: duplicate <plurals name=\"{name}\"> in file")
            items: dict[str, str] = {}
            for item in child.findall("item"):
                q = item.attrib.get("quantity")
                if not q:
                    errors.append(f"{path}: <plurals name=\"{name}\"> item missing quantity")
                    continue
                val = (item.text or "") + "".join(ET.tostring(sub, encoding="unicode") for sub in item)
                items[q] = val
            plurals[name] = (items, translatable)
    return strings, plurals, errors


def collect_merged_locale_files(
    upstream_res: Path, patches_res: Path | None, locale: str
) -> dict[str, Path]:
    """Return effective filename -> Path map after overlaying patches_res onto upstream_res."""
    files: dict[str, Path] = {}
    up_dir = upstream_res / locale
    if up_dir.is_dir():
        for p in sorted(up_dir.glob("*.xml")):
            files[p.name] = p
    if patches_res is not None:
        pa_dir = patches_res / locale
        if pa_dir.is_dir():
            for p in sorted(pa_dir.glob("*.xml")):
                files[p.name] = p
    return files


def validate(
    upstream_addons: Path,
    patches_addons: Path | None,
    locales: list[str],
    check_trailing_space: bool = True,
) -> int:
    all_errors: list[str] = []
    checked_pairs = 0

    for addon in ADDONS:
        up_res = upstream_addons / addon / "app/src/main/res"
        pa_res = (patches_addons / addon / "app/src/main/res") if patches_addons else None
        base_dir = up_res / "values"
        if not base_dir.is_dir():
            all_errors.append(f"[{addon}] Missing base values directory: {base_dir}")
            continue

        base_strings: dict[str, str] = {}
        non_translatable: set[str] = set()
        base_plurals: dict[str, dict[str, str]] = {}
        trailing_space_keys: set[str] = set()

        for xml_path in sorted(base_dir.glob("*.xml")):
            s_map, p_map, errs = parse_xml_file(xml_path)
            all_errors.extend(f"[{addon}/values] {e}" for e in errs)
            for k, (val, trans) in s_map.items():
                if trans:
                    base_strings[k] = val
                    if val.startswith('"') and val.endswith('"') and len(val) >= 3 and val[-2].isspace():
                        trailing_space_keys.add(k)
                else:
                    non_translatable.add(k)
            for k, (items, trans) in p_map.items():
                if trans:
                    base_plurals[k] = items
                else:
                    non_translatable.add(k)

        for locale in locales:
            checked_pairs += 1
            file_map = collect_merged_locale_files(up_res, pa_res, locale)
            if not file_map:
                all_errors.append(f"[{addon}/{locale}] Missing locale directory / XML files")
                continue

            loc_strings: dict[str, tuple[str, Path]] = {}
            loc_plurals: dict[str, tuple[dict[str, str], Path]] = {}

            for fname, fpath in sorted(file_map.items()):
                s_map, p_map, errs = parse_xml_file(fpath)
                all_errors.extend(f"[{addon}/{locale}] {e}" for e in errs)
                for k, (val, _trans) in s_map.items():
                    if k in loc_strings:
                        prev = loc_strings[k][1].name
                        all_errors.append(
                            f"[{addon}/{locale}] Duplicate string '{k}' across {prev} and {fname}"
                        )
                    loc_strings[k] = (val, fpath)
                for k, (items, _trans) in p_map.items():
                    if k in loc_plurals:
                        prev = loc_plurals[k][1].name
                        all_errors.append(
                            f"[{addon}/{locale}] Duplicate plural '{k}' across {prev} and {fname}"
                        )
                    loc_plurals[k] = (items, fpath)

            # Check non-translatable leaks
            for nt in sorted(non_translatable):
                if nt in loc_strings or nt in loc_plurals:
                    all_errors.append(
                        f"[{addon}/{locale}] Must not override non-translatable key '{nt}'"
                    )

            # Check strings
            for k, base_val in sorted(base_strings.items()):
                if k not in loc_strings:
                    all_errors.append(f"[{addon}/{locale}] Missing string '{k}'")
                    continue
                val, fpath = loc_strings[k]
                if not val.strip().strip('"').strip():
                    all_errors.append(f"[{addon}/{locale}/{fpath.name}] Empty string '{k}'")
                    continue
                esc_err = check_android_escaping(val)
                if esc_err:
                    all_errors.append(f"[{addon}/{locale}/{fpath.name}] '{k}': {esc_err}")
                base_fmts = extract_formats(base_val)
                loc_fmts = extract_formats(val)
                if base_fmts != loc_fmts:
                    all_errors.append(
                        f"[{addon}/{locale}/{fpath.name}] '{k}': format specifiers mismatch "
                        f"(expected {base_fmts}, got {loc_fmts})"
                    )
                if check_trailing_space and k in trailing_space_keys:
                    has_trailing_sep = (
                        val.startswith('"')
                        and val.endswith('"')
                        and len(val) >= 3
                        and (val[-2].isspace() or val[-2] == "：")
                    )
                    if not has_trailing_sep:
                        all_errors.append(
                            f"[{addon}/{locale}/{fpath.name}] '{k}': must preserve trailing space (or full-width colon) inside \"...\" (got {val!r})"
                        )

            # Check plurals
            for k, base_items in sorted(base_plurals.items()):
                if k not in loc_plurals:
                    all_errors.append(f"[{addon}/{locale}] Missing plural '{k}'")
                    continue
                items, fpath = loc_plurals[k]
                if "other" not in items:
                    all_errors.append(f"[{addon}/{locale}/{fpath.name}] Plural '{k}' missing quantity='other'")
                ref_fmts = extract_formats(next(iter(base_items.values())))
                for q, val in items.items():
                    if not val.strip().strip('"').strip():
                        all_errors.append(f"[{addon}/{locale}/{fpath.name}] Empty plural '{k}[{q}]'")
                        continue
                    esc_err = check_android_escaping(val)
                    if esc_err:
                        all_errors.append(f"[{addon}/{locale}/{fpath.name}] '{k}[{q}]': {esc_err}")
                    loc_fmts = extract_formats(val)
                    if ref_fmts != loc_fmts:
                        all_errors.append(
                            f"[{addon}/{locale}/{fpath.name}] '{k}[{q}]': format specifiers mismatch "
                            f"(expected {ref_fmts}, got {loc_fmts})"
                        )

    if all_errors:
        print(f"FAIL: {len(all_errors)} error(s) across {checked_pairs} add-on/locale checks:", file=sys.stderr)
        for err in all_errors[:120]:
            print(f"  - {err}", file=sys.stderr)
        if len(all_errors) > 120:
            print(f"  ... and {len(all_errors) - 120} more", file=sys.stderr)
        return 1

    print(f"OK: All {checked_pairs} add-on/locale resource sets passed ({len(ADDONS)} add-ons x {len(locales)} locales).")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate all 8 Matcher add-on locales.")
    parser.add_argument(
        "--upstream-addons",
        type=Path,
        default=Path("cleanyourtv/addons"),
        help="Path to upstream cleanyourtv/addons directory (default: cleanyourtv/addons)",
    )
    parser.add_argument(
        "--patches-addons",
        type=Path,
        default=Path("patches/addons"),
        help="Optional overlay path patches/addons (ignored when --merged-only is set)",
    )
    parser.add_argument(
        "--merged-only",
        action="store_true",
        help="Validate --upstream-addons directly without overlaying --patches-addons",
    )
    parser.add_argument(
        "--locales",
        type=str,
        default="",
        help="Comma-separated subset of locale dirs (e.g. values-pl,values-sk)",
    )
    args = parser.parse_args()

    locales = [x.strip() for x in args.locales.split(",") if x.strip()] if args.locales else TARGET_LOCALES
    patches = None if args.merged_only else args.patches_addons
    return validate(args.upstream_addons, patches, locales)


if __name__ == "__main__":
    raise SystemExit(main())
