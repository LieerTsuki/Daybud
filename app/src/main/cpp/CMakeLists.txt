cmake_minimum_required(VERSION 3.22.1)
project(diary_llama LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Tensor kernels need optimization even in a debuggable APK. AGP otherwise leaves
# Debug at Clang's default -O0, making runtime/device measurements misleading.
# Keep symbols and all ABI/CPU restrictions while matching Release kernel speed.
add_compile_options("$<$<CONFIG:Debug>:-O3>")
# Bound Ninja compile memory even when Gradle supplies a large global -j value.
set_property(GLOBAL PROPERTY JOB_POOLS diary_compile=2 diary_link=1)
set(CMAKE_JOB_POOL_COMPILE diary_compile)
set(CMAKE_JOB_POOL_LINK diary_link)

option(DIARY_LOCAL_HOST_TESTS "Build only model-independent native contract tests" OFF)
if(DIARY_LOCAL_HOST_TESTS)
    enable_testing()
    add_executable(runtime-contract-test tests/runtime_contract_test.cpp)
    add_test(NAME runtime-contract-test COMMAND runtime-contract-test)
    return()
endif()

if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "x86")
    # Keep the universal APK installable on 32-bit devices; inference is explicit unsupported.
    add_library(diary_llama SHARED unsupported_abi.cpp)
    target_link_options(diary_llama PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
    return()
endif()

include(FetchContent)
# A verified local archive permits completely offline, reproducible configuration.
set(DIARY_LLAMA_ARCHIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.tools/llama-b5501.tar.gz" CACHE FILEPATH "Pinned llama.cpp source archive")
if(EXISTS "${DIARY_LLAMA_ARCHIVE}")
    set(DIARY_LLAMA_URL "${DIARY_LLAMA_ARCHIVE}")
else()
    set(DIARY_LLAMA_URL "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/cdf94a18023c92f41808ec874ba577d914674717")
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
foreach(flag LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_CURL
        GGML_NATIVE GGML_OPENMP GGML_LLAMAFILE GGML_BACKEND_DL GGML_CPU_ALL_VARIANTS
        GGML_VULKAN GGML_CUDA GGML_METAL GGML_BLAS GGML_RPC GGML_CPU_KLEIDIAI
        GGML_SSE42 GGML_AVX GGML_AVX2 GGML_AVX_VNNI GGML_AVX512 GGML_AVX512_VBMI
        GGML_AVX512_VNNI GGML_AVX512_BF16 GGML_FMA GGML_F16C GGML_BMI2)
    set(${flag} OFF CACHE BOOL "" FORCE)
endforeach()
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "Baseline ARM64; no optional instruction requirement" FORCE)
endif()
FetchContent_Declare(llama_pinned URL "${DIARY_LLAMA_URL}"
    URL_HASH SHA256=142fbf66ca4945bedb74aa0fce555f09f1be73becd064e3b30a53cee92b1b194)
FetchContent_MakeAvailable(llama_pinned)

add_library(diary_llama SHARED native_llama.cpp)
target_link_libraries(diary_llama PRIVATE common llama android log)
target_compile_options(diary_llama PRIVATE -Wall -Wextra -Werror=return-type)
target_link_options(diary_llama PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
