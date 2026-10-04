cmake_minimum_required(VERSION 3.18)

project(fcitx5-android-plugin-rime VERSION ${VERSION_NAME})

# For reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

# prefab dependency
find_package(fcitx5 REQUIRED CONFIG)
get_target_property(FCITX5_CMAKE_MODULES fcitx5::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(ECM MODULE)
find_package(Fcitx5Core MODULE)
find_package(Fcitx5Module MODULE)

set(Rime_VERSION "1.12.0")
set(Rime_dependencies
        log # glog links to android log
        "${PREBUILT_DIR}/glog/${ANDROID_ABI}/lib/libglog.a"
        "${PREBUILT_DIR}/leveldb/${ANDROID_ABI}/lib/libleveldb.a"
        "${PREBUILT_DIR}/lua/${ANDROID_ABI}/lib/liblua_static.a"
        "${PREBUILT_DIR}/marisa/${ANDROID_ABI}/lib/libmarisa.a"
        "${PREBUILT_DIR}/opencc/${ANDROID_ABI}/lib/libopencc.a"
        "${PREBUILT_DIR}/yaml-cpp/${ANDROID_ABI}/lib/libyaml-cpp.a"
        )

add_library(Rime_static STATIC IMPORTED)
set_target_properties(Rime_static PROPERTIES
        IMPORTED_LOCATION "${PREBUILT_DIR}/librime/${ANDROID_ABI}/lib/librime.a"
        INTERFACE_INCLUDE_DIRECTORIES "${PREBUILT_DIR}/librime/${ANDROID_ABI}/include"
        INTERFACE_LINK_LIBRARIES "${Rime_dependencies}"
        )

# https://github.com/rime/librime/blob/1.9.0/src/rime_api.h#L663
# https://stackoverflow.com/questions/805555/ld-linker-question-the-whole-archive-option
# https://cmake.org/cmake/help/latest/manual/cmake-generator-expressions.7.html#link-features
set(RIME_TARGET $<LINK_LIBRARY:WHOLE_ARCHIVE,Rime_static>)
set(RIME_DATA_DIR "${CMAKE_INSTALL_DATADIR}/rime-data")
add_subdirectory(fcitx5-rime)

install(FILES
        "default.yaml"
        "rime-essay/essay.txt"
        "rime-prelude/key_bindings.yaml"
        "rime-prelude/punctuation.yaml"
        "rime-prelude/symbols.yaml"
        "rime-luna-pinyin/luna_pinyin_fluency.schema.yaml"
        "rime-luna-pinyin/luna_pinyin_simp.schema.yaml"
        "rime-luna-pinyin/luna_pinyin_tw.schema.yaml"
        "rime-luna-pinyin/luna_pinyin.dict.yaml"
        "rime-luna-pinyin/luna_pinyin.schema.yaml"
        "rime-luna-pinyin/luna_quanpin.schema.yaml"
        "rime-luna-pinyin/pinyin.yaml"
        "rime-stroke/stroke.dict.yaml"
        "rime-stroke/stroke.schema.yaml"
        DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data"
        COMPONENT prebuilt-assets
        )

# Pinned, checksum-verified data prepared by scripts/prepare-xuancai-rime.py.
set(XUANCAI_RIME_DATA "${CMAKE_CURRENT_SOURCE_DIR}/xuancai-rime-ice")
if(NOT EXISTS "${XUANCAI_RIME_DATA}/SOURCE.json")
    message(FATAL_ERROR "Run scripts/prepare-xuancai-rime.py before building the Xuancai Rime plugin")
endif()
install(FILES
        "${XUANCAI_RIME_DATA}/rime_ice.schema.yaml"
        "${XUANCAI_RIME_DATA}/rime_ice_t9.schema.yaml"
        "${XUANCAI_RIME_DATA}/rime_ice.dict.yaml"
        "${XUANCAI_RIME_DATA}/xuancai_user.schema.yaml"
        "${XUANCAI_RIME_DATA}/xuancai_user_t9.schema.yaml"
        "${XUANCAI_RIME_DATA}/xuancai_user.dict.yaml"
        "${XUANCAI_RIME_DATA}/melt_eng.schema.yaml"
        "${XUANCAI_RIME_DATA}/melt_eng.dict.yaml"
        "${XUANCAI_RIME_DATA}/radical_pinyin.schema.yaml"
        "${XUANCAI_RIME_DATA}/radical_pinyin.dict.yaml"
        "${XUANCAI_RIME_DATA}/custom_phrase.txt"
        "${XUANCAI_RIME_DATA}/symbols_v.yaml"
        "${XUANCAI_RIME_DATA}/xuancai_mobile.yaml"
        "${XUANCAI_RIME_DATA}/zh-hans-t-essay-bgw-compact.gram"
        DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data"
        COMPONENT prebuilt-assets)
install(DIRECTORY "${XUANCAI_RIME_DATA}/cn_dicts" "${XUANCAI_RIME_DATA}/en_dicts" "${XUANCAI_RIME_DATA}/lua"
        DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data"
        COMPONENT prebuilt-assets)
install(DIRECTORY "${XUANCAI_RIME_DATA}/opencc/"
        DESTINATION "${FCITX_INSTALL_DATADIR}/opencc"
        COMPONENT prebuilt-assets)
install(FILES "${XUANCAI_RIME_DATA}/LICENSE" "${XUANCAI_RIME_DATA}/README.md"
        "${XUANCAI_RIME_DATA}/SOURCE.json"
        DESTINATION "${FCITX_INSTALL_DATADIR}/licenses/rime-ice"
        COMPONENT prebuilt-assets)

install(DIRECTORY "${XUANCAI_RIME_DATA}/grammar-license/"
        DESTINATION "${FCITX_INSTALL_DATADIR}/licenses/rime-grammar"
        COMPONENT prebuilt-assets)
# Tables are generated and tested by the same pinned librime 1.12 on the build host.
if(NOT EXISTS "${XUANCAI_RIME_DATA}/build/rime_ice.table.bin")
    message(FATAL_ERROR "Precompile and verify Rime data before packaging")
endif()
foreach(T9_PREBUILT rime_ice_t9.prism.bin rime_ice_t9.schema.yaml
        xuancai_user_t9.prism.bin xuancai_user_t9.schema.yaml)
    if(NOT EXISTS "${XUANCAI_RIME_DATA}/build/${T9_PREBUILT}")
        message(FATAL_ERROR "Precompile and verify the nine-key Rime schemas before packaging")
    endif()
endforeach()
install(DIRECTORY "${XUANCAI_RIME_DATA}/build/"
        DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data/build"
        COMPONENT prebuilt-assets)

# Bundled-engine license notices must travel with the main APK as well as the
# optional standalone plugin. These are text from the pinned source revisions.
install(DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/builtin-licenses/"
        DESTINATION "${FCITX_INSTALL_DATADIR}/licenses/rime-builtin"
        COMPONENT prebuilt-assets)
install(FILES "fcitx5-rime/LICENSES/LGPL-2.1-or-later.txt"
        DESTINATION "${FCITX_INSTALL_DATADIR}/licenses/rime-builtin"
        RENAME "fcitx5-rime-LICENSE.txt" COMPONENT prebuilt-assets)
foreach(RIME_DATA_COMPONENT rime-essay rime-prelude rime-luna-pinyin rime-stroke)
    install(FILES "${RIME_DATA_COMPONENT}/LICENSE"
            DESTINATION "${FCITX_INSTALL_DATADIR}/licenses/rime-builtin"
            RENAME "${RIME_DATA_COMPONENT}-LICENSE.txt" COMPONENT prebuilt-assets)
endforeach()
