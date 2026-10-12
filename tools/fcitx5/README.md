# fcitx5 pinyin integration

This directory contains the tooling needed to build the native side of the
"Simplified Chinese (pinyin) [fcitx5]" language option.

The pinyin engine itself is the one used by [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android):
it is implemented in [libime](https://github.com/fcitx/libime) (`libime::PinyinIME` +
`libime::PinyinContext`) and consumed by `fcitx5-chinese-addons`.

## What is in the app

* `native/jni/fcitx5pinyin/fcitx5_pinyin_jni.cpp` — a small JNI bridge that drives libime
  exactly the way fcitx5's pinyin addon does (system dictionary + user language model +
  per-composition `PinyinContext`).
* `java/src/org/futo/inputmethod/engine/fcitx5/` — the Kotlin side of that bridge
  (`Fcitx5Pinyin`, `Fcitx5PinyinData`).
* `java/src/org/futo/inputmethod/engine/general/Fcitx5PinyinIME.kt` — the `IMEInterface`
  implementation that plugs the engine into the keyboard.

If the libime headers/libraries are not provided at build time, CMake compiles
`fcitx5_pinyin_stub.cpp` instead. The app then builds and runs normally, and the fcitx5
option simply reports itself as unavailable (falling back to plain text input).

## 1. Build the native dependencies

Run:

```sh
tools/fcitx5/build-native-deps.sh /path/to/output
```

This clones fcitx5-android (with submodules), builds the `libime`, `fcitx5` and
`fcitx5-chinese-addons` AARs with the Android NDK, and lays their prefab contents out as:

```
/path/to/output/include/...            (libime + fcitx-utils + fcitx headers)
/path/to/output/lib/<abi>/*.so         (libIMEPinyin, libIMECore, libFcitx5Core, ...)
```

Then build the app pointing at that directory:

```sh
./gradlew assembleUnstable -Pfcitx5PinyinRoot=/path/to/output
```

If `-Pfcitx5PinyinRoot` is omitted (or does not contain `include/libime/pinyin/pinyinime.h`),
the stub library is built instead.

## 2. Provide the pinyin data

```sh
tools/fcitx5/fetch-pinyin-data.sh
```

This downloads `sc.dict` and `zh_CN.lm` into `java/assets/fcitx5-pinyin/`. See
`java/assets/fcitx5-pinyin/README.md`.

## Licensing

libime and fcitx5 are licensed under LGPL-2.1-or-later. When distributing a build that links
them, comply with the LGPL (provide the corresponding source and allow relinking). See
`NOTICE`.
