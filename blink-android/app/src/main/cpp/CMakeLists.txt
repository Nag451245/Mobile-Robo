cmake_minimum_required(VERSION 3.22.1)
project(sweety CXX C)

# Needle 2 (Cactus Compute, Apache-2.0). The CI build downloads libneedle.a and needle.h into app/needle/<abi>/.
set(NEEDLE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../needle/${ANDROID_ABI})

add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES IMPORTED_LOCATION ${NEEDLE_DIR}/libneedle.a)

add_library(sweety SHARED sweety_jni.cpp)
target_include_directories(sweety PRIVATE ${NEEDLE_DIR})
target_link_libraries(sweety needle log m dl atomic)
target_link_options(sweety PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
