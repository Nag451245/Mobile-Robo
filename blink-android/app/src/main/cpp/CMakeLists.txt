cmake_minimum_required(VERSION 3.22.1)
project(sweety CXX C)

# Cactus 4 runtime (Cactus Compute, Apache-2.0): runs Needle 3 and Whistle.
# The CI build downloads libcactus.a and cactus.h into app/cactus/<abi>/.
set(CACTUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../cactus/${ANDROID_ABI})

add_library(cactus STATIC IMPORTED)
set_target_properties(cactus PROPERTIES IMPORTED_LOCATION ${CACTUS_DIR}/libcactus.a)

add_library(sweety SHARED sweety_jni.cpp)
target_include_directories(sweety PRIVATE ${CACTUS_DIR})
target_link_libraries(sweety cactus log m dl atomic)
target_link_options(sweety PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
