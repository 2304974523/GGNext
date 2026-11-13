cmake_minimum_required(VERSION 3.18)
project(MemDiagnosticEngine VERSION 2.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(MEMENGINE_BUILD_TESTS "Build local automated stress test & 3-way benchmark suite" ON)
option(MEMENGINE_BUILD_JNI "Build Android JNI shared library target" ON)

# Architecture & SIMD Detection (ARM64 NEON, ARMv7 NEON, x86_64 Scalar/SSE/AVX)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
    message(STATUS "[MemEngine] Target Architecture: ARM64 (AArch64 NEON SIMD enabled)")
    add_compile_options(-O3 -Wall -Wextra -fno-strict-aliasing)
    # Android 15+ 16KB page size ELF segment alignment compliance
    add_link_options(-Wl,-z,max-page-size=16384)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "arm|armv7|armeabi-v7a")
    message(STATUS "[MemEngine] Target Architecture: ARMv7 (NEON SIMD enabled)")
    add_compile_options(-O3 -Wall -Wextra -mfpu=neon -mfloat-abi=softfp -fno-strict-aliasing)
    add_link_options(-Wl,-z,max-page-size=16384)
else()
    message(STATUS "[MemEngine] Target Architecture: ${CMAKE_SYSTEM_PROCESSOR} (x86_64 / Host Scalar + Auto-Vectorized path)")
    add_compile_options(-O3 -Wall -Wextra -fno-strict-aliasing -pthread)
endif()

find_package(Threads REQUIRED)

set(MEMENGINE_CORE_SOURCES
    src/maps_parser.cpp
    src/process_enum.cpp
    src/proc_io.cpp
    src/mmap_store.cpp
    src/simd_kernel.cpp
    src/search_engine.cpp
)

# Static core library for unit tests & benchmarks
add_library(mem_engine_core STATIC ${MEMENGINE_CORE_SOURCES})
target_include_directories(mem_engine_core PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
)
target_link_libraries(mem_engine_core PUBLIC Threads::Threads)

# Shared JNI library for Android NDK integration
if(MEMENGINE_BUILD_JNI)
    add_library(mem_engine_jni SHARED
        ${MEMENGINE_CORE_SOURCES}
        jni/mem_engine_jni.cpp
    )
    target_include_directories(mem_engine_jni PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    )
    target_link_libraries(mem_engine_jni PUBLIC Threads::Threads)
    if(ANDROID)
        find_library(log-lib log)
        target_link_libraries(mem_engine_jni PUBLIC ${log-lib})
    endif()
endif()

# Local controlled stress test & 3-way comparative benchmark executable
if(MEMENGINE_BUILD_TESTS)
    add_executable(mem_engine_benchmark tests/benchmark_suite.cpp)
    target_link_libraries(mem_engine_benchmark PRIVATE mem_engine_core Threads::Threads)
endif()
