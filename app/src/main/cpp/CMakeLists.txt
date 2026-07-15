cmake_minimum_required(VERSION 3.22)
project(medvoice_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# AGP builds native code with -O0 for debug variants; unoptimized ggml is
# 10-50x slower, which makes whisper unusable. Force optimization always.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper_build)

add_library(medvoice_whisper SHARED whisper_jni.cpp)

target_include_directories(medvoice_whisper PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

find_library(log-lib log)
target_link_libraries(medvoice_whisper whisper ${log-lib})

# 16 KB page-size compatibility (required on newer Android devices)
target_link_options(medvoice_whisper PRIVATE "-Wl,-z,max-page-size=16384")
