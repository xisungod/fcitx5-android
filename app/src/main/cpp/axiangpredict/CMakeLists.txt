# SPDX-License-Identifier: LGPL-2.1-or-later
add_library(axiangpredict SHARED
        ../axiangpredict.cpp
        ../axiangpredict-text.cpp
        ../axiangpredict-jni.cpp)
target_compile_features(axiangpredict PRIVATE cxx_std_20)
target_link_libraries(axiangpredict PRIVATE LibIME::Core Fcitx5::Utils Boost::headers)
target_link_options(axiangpredict PRIVATE
        "LINKER:-z,max-page-size=16384"
        "LINKER:-z,common-page-size=16384")
