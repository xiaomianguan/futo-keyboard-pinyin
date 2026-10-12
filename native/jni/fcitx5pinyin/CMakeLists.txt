cmake_minimum_required(VERSION 3.22)

# ------------------------------------------------------------------
# fcitx5 / libime pinyin engine JNI bridge
#
# By default a stub is built so that the app compiles and simply reports the
# fcitx5 pinyin engine as unavailable. To build with the real engine, point
# FCITX5_PINYIN_ROOT at a directory laid out like:
#
#   <root>/include/...            (libime + fcitx-utils headers)
#   <root>/lib/<android-abi>/*.so (libIMEPinyin, libIMECore, libFcitx5Utils, ...)
#
# tools/fcitx5/build-native-deps.sh produces exactly that layout.
# ------------------------------------------------------------------

set(FCITX5_PINYIN_ROOT "" CACHE PATH "Root of the fcitx5/libime native dependencies")

set(_fcitx5_pinyin_real FALSE)
if(FCITX5_PINYIN_ROOT AND EXISTS "${FCITX5_PINYIN_ROOT}/include/libime/pinyin/pinyinime.h")
    set(_fcitx5_pinyin_real TRUE)
endif()

if(_fcitx5_pinyin_real)
    message(STATUS "fcitx5 pinyin: building real engine from ${FCITX5_PINYIN_ROOT}")
    add_library(fcitx5pinyin SHARED fcitx5_pinyin_jni.cpp)

    target_include_directories(fcitx5pinyin PRIVATE "${FCITX5_PINYIN_ROOT}/include")

    file(GLOB _fcitx5_pinyin_libs
        "${FCITX5_PINYIN_ROOT}/lib/${ANDROID_ABI}/*.so"
        "${FCITX5_PINYIN_ROOT}/lib/${ANDROID_ABI}/*.a")
    target_link_libraries(fcitx5pinyin PRIVATE ${_fcitx5_pinyin_libs})

    target_link_options(fcitx5pinyin PRIVATE "-Wl,-z,max-page-size=16384")
else()
    message(STATUS "fcitx5 pinyin: building stub (set -DFCITX5_PINYIN_ROOT=... for the real engine)")
    add_library(fcitx5pinyin SHARED fcitx5_pinyin_stub.cpp)
endif()

target_link_libraries(fcitx5pinyin PRIVATE log)
