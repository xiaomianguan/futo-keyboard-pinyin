#!/usr/bin/env bash
#
# Builds the fcitx5 / libime native libraries needed by the "Simplified Chinese (pinyin)
# [fcitx5]" option and lays them out in the form expected by
# native/jni/fcitx5pinyin/CMakeLists.txt (FCITX5_PINYIN_ROOT).
#
# Usage:
#   tools/fcitx5/build-native-deps.sh /path/to/output [abi...]
#
# The heavy lifting is delegated to fcitx5-android's own Gradle/CMake build: it produces
# Android library AARs whose prefab packages contain both the headers and the .so files.
# This script assembles those into a single include/ + lib/<abi>/ tree.
#
set -euo pipefail

OUT="${1:-}"
shift || true
ABIS=("$@")
if [ -z "$OUT" ]; then
    echo "usage: $0 /path/to/output [abi...]" >&2
    exit 1
fi
if [ "${#ABIS[@]}" -eq 0 ]; then
    ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
fi

FCITX5_ANDROID_REPO="https://github.com/fcitx5-android/fcitx5-android.git"
FCITX5_ANDROID_REF="${FCITX5_ANDROID_REF:-master}"

WORK="${WORK_DIR:-$(mktemp -d)}"
SRC="$WORK/fcitx5-android"
echo "Using work directory: $WORK"

if [ ! -d "$SRC/.git" ]; then
    echo "Cloning fcitx5-android ($FCITX5_ANDROID_REF) with submodules..."
    git clone --depth 1 --branch "$FCITX5_ANDROID_REF" \
        --recurse-submodules --shallow-submodules "$FCITX5_ANDROID_REPO" "$SRC"
else
    echo "Reusing existing checkout at $SRC"
fi

echo "Building libime / fcitx5 / fcitx5-chinese-addons AARs..."
( cd "$SRC" && ./gradlew --no-daemon \
    :lib:libime:assembleRelease \
    :lib:fcitx5:assembleRelease \
    :lib:fcitx5-chinese-addons:assembleRelease )

mkdir -p "$OUT/include"
for abi in "${ABIS[@]}"; do
    mkdir -p "$OUT/lib/$abi"
done

extract_aar() {
    local aar="$1"
    local tmp="$2"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    ( cd "$tmp" && unzip -qo "$aar" )

    # Headers
    if [ -d "$tmp/prefab/modules" ]; then
        find "$tmp/prefab/modules" -type d -name include -print0 |
            while IFS= read -r -d '' inc; do
                cp -R "$inc/." "$OUT/include/" 2>/dev/null || true
            done

        # Shared libraries
        for abi in "${ABIS[@]}"; do
            find "$tmp/prefab/modules" -type d -path "*libs/android.$abi" -print0 |
                while IFS= read -r -d '' libdir; do
                    find "$libdir" -name '*.so' -exec cp -n {} "$OUT/lib/$abi/" \;
                done
        done
    fi
}

for module in libime fcitx5 fcitx5-chinese-addons; do
    # fcitx5-android uses kebab/underscore naming for the module directories.
    dir="$SRC/lib/$module/build/outputs/aar"
    aar="$(find "$dir" -name '*.aar' 2>/dev/null | head -n1 || true)"
    if [ -z "$aar" ]; then
        echo "warning: no AAR produced for $module" >&2
        continue
    fi
    echo "Extracting $aar"
    extract_aar "$aar" "$WORK/extract-$module"
done

echo
echo "Native dependencies laid out at: $OUT"
find "$OUT" -maxdepth 3 -type f | sed 's/^/  /'
echo
echo "Build the app with:"
echo "  ./gradlew assembleUnstable -Pfcitx5PinyinRoot=$OUT"
