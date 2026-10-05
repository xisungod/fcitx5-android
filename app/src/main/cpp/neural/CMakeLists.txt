# A small bridge to the ORT C runtime already bundled for local speech.
add_library(axiang-neural SHARED mini-rbt-jni.cpp)
target_compile_features(axiang-neural PRIVATE cxx_std_17)
target_link_libraries(axiang-neural PRIVATE dl)
