#!/usr/bin/env bash
#
# Downloads the libime pinyin data files (sc.dict, zh_CN.lm) into
# java/assets/fcitx5-pinyin/.
#
# The files are published by the fcitx5-android project in the "data" repository
# (https://github.com/fcitx5-android/data). If the automatic download does not work in your
# environment, obtain the two files manually (they are installed by fcitx5 packages under
# share/libime/) and copy them into java/assets/fcitx5-pinyin/.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST="$REPO_ROOT/java/assets/fcitx5-pinyin"
DATA_REPO="fcitx5-android/data"

mkdir -p "$DEST"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading latest fcitx5-android data release..."
if command -v gh >/dev/null 2>&1; then
    gh release download --repo "$DATA_REPO" --dir "$WORK" --pattern '*.tar.*' --clobber
else
    echo "error: the GitHub CLI (gh) is required for automatic download." >&2
    echo "       Download the data release from https://github.com/$DATA_REPO/releases" >&2
    echo "       and copy sc.dict and zh_CN.lm into $DEST manually." >&2
    exit 1
fi

ARCHIVE="$(find "$WORK" -maxdepth 1 -name '*.tar.*' | head -n1)"
if [ -z "$ARCHIVE" ]; then
    echo "error: no data archive found in the release." >&2
    exit 1
fi

echo "Extracting $ARCHIVE ..."
mkdir -p "$WORK/extracted"
tar -xf "$ARCHIVE" -C "$WORK/extracted"

for name in sc.dict zh_CN.lm; do
    src="$(find "$WORK/extracted" -name "$name" | head -n1)"
    if [ -z "$src" ]; then
        echo "error: $name not found in the data archive." >&2
        exit 1
    fi
    cp "$src" "$DEST/$name"
    echo "installed $DEST/$name"
done

echo "Done."
