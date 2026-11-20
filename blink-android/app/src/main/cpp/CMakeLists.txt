cmake_minimum_required(VERSION 3.22.1)
project(sweety CXX C)

# Cactus 4 runtime (Cactus Compute, Apache-2.0): runs Needle 3 and Whistle.
# The CI build downloads libcactus.a and cactus.h into app/cactus/<abi>/.
set(CACTUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../cactus/${ANDROID_ABI})

# Cactus ships for arm64 phones only. Test builds for the x86_64 emulator skip it (Sweety then uses her built-in rules).
if(NOT EXISTS ${CACTUS_DIR}/libcactus.a)
    message(WARNING "No Cactus runtime for ${ANDROID_ABI}: building without Needle 3 and Whistle")
    add_library(sweety SHARED stub.c)
    return()
endif()

add_library(cactus STATIC IMPORTED)
set_target_properties(cactus PROPERTIES IMPORTED_LOCATION ${CACTUS_DIR}/libcactus.a)

add_library(sweety SHARED sweety_jni.cpp)
target_include_directories(sweety PRIVATE ${CACTUS_DIR})
target_link_libraries(sweety cactus log m dl atomic)
target_link_options(sweety PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
