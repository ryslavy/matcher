#!/usr/bin/env bash
# Matcher Fast Binary Updater (macOS / Linux) — No JDK, No Android SDK, No Gradle required!
# Downloads manifest.json & only changed signed APKs from GitHub Releases, verifies SHA-256,
# installs in-place via `adb install -r`, preserves theme/debloat/folders/keys, and re-binds Accessibility services.
#
# Usage:
#   ./update.sh 192.168.1.50
#   ./update.sh 192.168.1.50:37891 --launcher-only
#   ./update.sh 192.168.1.50 --force
set -euo pipefail

TV_HOST="${1:-}"
shift || true
REPO="ryslavy/matcher"
LAUNCHER_ONLY=0
FORCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --launcher-only) LAUNCHER_ONLY=1 ;;
    --force) FORCE=1 ;;
    --repo) REPO="${2:?missing repo}"; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

if [ -z "$TV_HOST" ]; then
  echo "Usage: $0 <TV_IP[:PORT]> [--launcher-only] [--force] [--repo owner/repo]" >&2
  exit 2
fi

command -v adb >/dev/null || { echo "adb not found in PATH." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl not found in PATH." >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found in PATH." >&2; exit 1; }

adb connect "$TV_HOST" >/dev/null
adb -s "$TV_HOST" get-state | grep -q device || { echo "Cannot connect to $TV_HOST via ADB." >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Fetching latest release manifest from github.com/$REPO ..."
curl -fsSL -H "Accept: application/vnd.github+json" -H "User-Agent: Matcher-Updater" \
  "https://api.github.com/repos/$REPO/releases/latest" -o "$TMP_DIR/release.json"

MANIFEST_URL="$(python3 -c '
import json, sys
rel = json.load(open(sys.argv[1]))
for a in rel.get("assets", []):
    if a.get("name") == "manifest.json":
        print(a.get("browser_download_url", ""))
        sys.exit(0)
' "$TMP_DIR/release.json")"

if [ -z "$MANIFEST_URL" ]; then
  echo "Latest release has no manifest.json asset yet." >&2
  exit 1
fi

curl -fsSL -H "User-Agent: Matcher-Updater" "$MANIFEST_URL" -o "$TMP_DIR/manifest.json"
PREV_A11Y="$(adb -s "$TV_HOST" shell "settings get secure enabled_accessibility_services" | tr -d '\r\n')"

get_vcode() {
  local pkg="$1"
  local line
  line="$(adb -s "$TV_HOST" shell "dumpsys package $pkg 2>/dev/null | grep -m1 'versionCode='" | tr -d '\r' || true)"
  if [[ "$line" =~ versionCode=([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  else
    echo "-1"
  fi
}

verify_sha() {
  local file="$1" expected="$2"
  [ -z "$expected" ] && return 0
  local actual
  actual="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$file")"
  if [ "${actual,,}" != "${expected,,}" ]; then
    echo "SHA-256 mismatch for $(basename "$file")!" >&2
    exit 1
  fi
}

UPDATED=0
while IFS=$'\t' read -r kind pkg name vcode apk url sha; do
  [ "$kind" = "addon" ] && [ "$LAUNCHER_ONLY" -eq 1 ] && continue
  cur="$(get_vcode "$pkg")"
  if [ "$kind" = "addon" ] && [ "$cur" -lt 0 ]; then
    continue
  fi
  if [ "$FORCE" -eq 1 ] || [ "$cur" -lt "$vcode" ]; then
    echo "Updating $name ($cur -> $vcode): $apk ..."
    curl -fsSL "$url" -o "$TMP_DIR/$apk"
    verify_sha "$TMP_DIR/$apk" "$sha"
    if adb -s "$TV_HOST" install -r "$TMP_DIR/$apk"; then
      UPDATED=$((UPDATED + 1))
    else
      if [ "$kind" = "launcher" ]; then
        echo "Launcher install failed (signing key mismatch?)." >&2
        exit 1
      fi
      echo "Warning: skipped add-on $name due to install error." >&2
    fi
  else
    echo "$name is already up to date (versionCode $cur)."
  fi
done < <(python3 -c '
import json, sys
m = json.load(open(sys.argv[1]))
l = m["launcher"]
print(f"launcher\t{l[\"packageName\"]}\tLauncher\t{l[\"versionCode\"]}\t{l[\"apk\"]}\t{l[\"url\"]}\t{l.get(\"sha256\",\"\")}")
for a in m.get("addons", []):
    print(f"addon\t{a[\"packageName\"]}\t{a[\"name\"]}\t{a[\"versionCode\"]}\t{a[\"apk\"]}\t{a[\"url\"]}\t{a.get(\"sha256\",\"\")}")
' "$TMP_DIR/manifest.json")

if [ "$UPDATED" -gt 0 ]; then
  adb -s "$TV_HOST" shell "pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; true" >/dev/null
  if [ -n "$PREV_A11Y" ] && [ "$PREV_A11Y" != "null" ]; then
    echo "Re-binding Accessibility services: $PREV_A11Y"
    adb -s "$TV_HOST" shell "settings put secure enabled_accessibility_services ''"
    sleep 1
    adb -s "$TV_HOST" shell "settings put secure enabled_accessibility_services '$PREV_A11Y'; settings put secure accessibility_enabled 1; sync"
  else
    adb -s "$TV_HOST" shell "sync"
  fi
  sleep 2
  adb -s "$TV_HOST" shell "am start -n com.gathin.tvhome/.MainActivity" >/dev/null
  echo ""
  echo "=== Accessibility Check ==="
  adb -s "$TV_HOST" shell "dumpsys accessibility | grep -E 'Bound services|Service\[label=|Crashed services'"
  echo ""
  echo "Done! Updated $UPDATED package(s). Wait 15 seconds before rebooting your TV."
else
  echo "Everything installed on $TV_HOST is already up to date!"
fi
