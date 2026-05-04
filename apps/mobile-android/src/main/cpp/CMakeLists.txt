cmake_minimum_required(VERSION 3.22.1)

project(pocket_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

if(NOT ANDROID)
    message(FATAL_ERROR "pocket_llama native build is Android-only.")
endif()

set(POCKETGPT_ARM64_SPECIALIZED_VARIANTS OFF)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(POCKETGPT_ARM64_SPECIALIZED_VARIANTS ON)
else()
    message(STATUS "Building pocket_llama without arm64-specialized variants for ABI=${ANDROID_ABI}")
endif()

option(POCKETGPT_ENABLE_HEXAGON "Enable experimental Hexagon backend" OFF)
option(
    POCKETGPT_FORCE_ARM_I8MM
    "Force ARM i8mm/dotprod compile flags (unsafe on older arm64 devices)"
    OFF
)

# Path from apps/mobile-android/src/main/cpp -> repo root -> vendored llama.cpp.
# Keep the Android app coupled to this seam rather than to a specific upstream,
# Prism, or PocketGPT overlay branch so vendor refreshes stay mechanical.
set(LLAMA_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_CPP_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "Missing vendored llama.cpp source at ${LLAMA_CPP_ROOT}.")
endif()

include(FetchContent)

# Build a minimal llama.cpp footprint with statically linked backends on Android.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Use statically linked ggml backends in Android app binaries.
# Dynamic backend loading can report "no backends are loaded" when shared
# backend artifacts are unavailable at runtime.
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_SHARED OFF CACHE BOOL "" FORCE)
if(POCKETGPT_ENABLE_HEXAGON AND EXISTS "${LLAMA_CPP_ROOT}/ggml/src/ggml-hexagon/CMakeLists.txt")
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
else()
    set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
endif()
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
# Target OpenCL 2.0 for compatibility with Adreno 6xx GPUs (Snapdragon 855-870)
# which only support CL 2.0. CL 3.0 APIs cause compilation constant errors on
# these devices. CL 2.0 target still works correctly on CL 3.0 capable GPUs.
set(GGML_OPENCL_TARGET_VERSION "200" CACHE STRING "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(POCKETGPT_ARM64_SPECIALIZED_VARIANTS)
    if(POCKETGPT_FORCE_ARM_I8MM)
        set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm" CACHE STRING "" FORCE)
    elseif(GGML_BACKEND_DL)
        # Build Android CPU backend variants and let ggml dispatch the best one at runtime.
        set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
        set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    else()
        # GGML_CPU_ALL_VARIANTS requires dynamic backend loading. When backends are
        # linked statically, keep a conservative baseline ARM target for compatibility.
        set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
        set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
    endif()
endif()

if(GGML_OPENCL)
    # Provide OpenCL headers + ICD loader for Android builds. This mirrors the
    # dependency model used by llama.rn source builds.
    FetchContent_Declare(
        opencl_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
        GIT_TAG v2024.10.24
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(opencl_headers)

    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_TESTS OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_HEADERS_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    FetchContent_Declare(
        opencl_icd_loader
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
        GIT_TAG v2024.10.24
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(opencl_icd_loader)

    # Feed FindOpenCL hints consumed by ggml-opencl's CMake.
    set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_INCLUDE_DIRS "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
    set(OpenCL_LIBRARIES OpenCL CACHE STRING "" FORCE)
endif()

add_subdirectory("${LLAMA_CPP_ROOT}" "${CMAKE_BINARY_DIR}/llama.cpp")

# Build libmtmd (multimodal: vision encoder, clip, image tokenization) as a
# static library.  The upstream CMakeLists is gated by LLAMA_BUILD_TOOLS which
# we disable for the minimal Android build, so we add it explicitly.
# Provide LLAMA_INSTALL_VERSION which mtmd's CMakeLists.txt requires for
# set_target_properties(VERSION ...).
if(NOT DEFINED LLAMA_INSTALL_VERSION)
    set(LLAMA_INSTALL_VERSION "0.0.0")
endif()
set(LLAMA_TOOLS_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(
    "${LLAMA_CPP_ROOT}/tools/mtmd"
    "${CMAKE_BINARY_DIR}/llama.cpp-mtmd"
)
if(TARGET mtmd)
    target_compile_options(mtmd PRIVATE -O3 -DNDEBUG -fvectorize)
endif()
# Remove the CLI executables that mtmd's CMakeLists produces — we only need
# the library, not the binaries.
foreach(_mtmd_exe llama-mtmd-cli llama-llava-cli llama-gemma3-cli llama-minicpmv-cli llama-qwen2vl-cli)
    if(TARGET ${_mtmd_exe})
        set_target_properties(${_mtmd_exe} PROPERTIES EXCLUDE_FROM_ALL TRUE)
    endif()
endforeach()

set(POCKET_LLAMA_COMPILE_FLAGS
    -O3
    -DNDEBUG
    -fvectorize
)
set(POCKET_LLAMA_LINK_FLAGS)

function(resolve_target_sources output_var target_name)
    get_target_property(_sources ${target_name} SOURCES)
    get_target_property(_source_dir ${target_name} SOURCE_DIR)
    if(NOT _sources OR _sources STREQUAL "_sources-NOTFOUND")
        set(${output_var} "" PARENT_SCOPE)
        return()
    endif()
    set(_resolved_sources)
    foreach(source_path IN LISTS _sources)
        if(IS_ABSOLUTE "${source_path}" OR "${source_path}" MATCHES "^\\$<")
            list(APPEND _resolved_sources "${source_path}")
        else()
            list(APPEND _resolved_sources "${_source_dir}/${source_path}")
        endif()
    endforeach()
    set(${output_var} "${_resolved_sources}" PARENT_SCOPE)
endfunction()

function(copy_target_property_if_present source_target property_name apply_mode destination_target)
    get_target_property(_property_values ${source_target} ${property_name})
    if(NOT _property_values OR _property_values STREQUAL "_property_values-NOTFOUND")
        return()
    endif()

    if(property_name STREQUAL "INCLUDE_DIRECTORIES")
        target_include_directories(${destination_target} ${apply_mode} ${_property_values})
    elseif(property_name STREQUAL "INTERFACE_INCLUDE_DIRECTORIES")
        target_include_directories(${destination_target} INTERFACE ${_property_values})
    elseif(property_name STREQUAL "COMPILE_DEFINITIONS")
        target_compile_definitions(${destination_target} ${apply_mode} ${_property_values})
    elseif(property_name STREQUAL "INTERFACE_COMPILE_DEFINITIONS")
        target_compile_definitions(${destination_target} INTERFACE ${_property_values})
    elseif(property_name STREQUAL "COMPILE_OPTIONS")
        target_compile_options(${destination_target} ${apply_mode} ${_property_values})
    elseif(property_name STREQUAL "LINK_OPTIONS")
        target_link_options(${destination_target} ${apply_mode} ${_property_values})
    elseif(property_name STREQUAL "COMPILE_FEATURES")
        target_compile_features(${destination_target} ${apply_mode} ${_property_values})
    endif()
endfunction()

function(clone_target_metadata source_target cloned_target)
    copy_target_property_if_present(${source_target} INCLUDE_DIRECTORIES PRIVATE ${cloned_target})
    copy_target_property_if_present(${source_target} INTERFACE_INCLUDE_DIRECTORIES INTERFACE ${cloned_target})
    copy_target_property_if_present(${source_target} COMPILE_DEFINITIONS PRIVATE ${cloned_target})
    copy_target_property_if_present(${source_target} INTERFACE_COMPILE_DEFINITIONS INTERFACE ${cloned_target})
    copy_target_property_if_present(${source_target} COMPILE_OPTIONS PRIVATE ${cloned_target})
    copy_target_property_if_present(${source_target} LINK_OPTIONS PRIVATE ${cloned_target})
    copy_target_property_if_present(${source_target} COMPILE_FEATURES PRIVATE ${cloned_target})
    set_target_properties(${cloned_target} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endfunction()

function(filter_out_arch_flags output_var)
    set(_filtered_options)
    foreach(option_value IN LISTS ARGN)
        if("${option_value}" MATCHES "^-march=")
            continue()
        endif()
        list(APPEND _filtered_options "${option_value}")
    endforeach()
    set(${output_var} "${_filtered_options}" PARENT_SCOPE)
endfunction()

function(create_cpu_specialized_stack stack_suffix cpu_march_flag)
    if(NOT TARGET ggml-cpu OR NOT TARGET ggml OR NOT TARGET llama)
        message(FATAL_ERROR "llama.cpp targets must exist before creating specialized stack ${stack_suffix}.")
    endif()

    set(cpu_target "ggml-cpu-${stack_suffix}")
    set(ggml_target "ggml-${stack_suffix}")
    set(llama_target "llama-${stack_suffix}")

    resolve_target_sources(_cpu_sources ggml-cpu)
    add_library(${cpu_target} STATIC ${_cpu_sources})
    clone_target_metadata(ggml-cpu ${cpu_target})
    get_target_property(_cpu_link_libs ggml-cpu LINK_LIBRARIES)
    if(_cpu_link_libs AND NOT _cpu_link_libs STREQUAL "_cpu_link_libs-NOTFOUND")
        target_link_libraries(${cpu_target} PRIVATE ${_cpu_link_libs})
    endif()
    get_target_property(_cpu_compile_options ggml-cpu COMPILE_OPTIONS)
    if(_cpu_compile_options AND NOT _cpu_compile_options STREQUAL "_cpu_compile_options-NOTFOUND")
        filter_out_arch_flags(_cpu_compile_options_without_arch ${_cpu_compile_options})
        set_property(TARGET ${cpu_target} PROPERTY COMPILE_OPTIONS "${_cpu_compile_options_without_arch}")
    endif()
    target_compile_options(${cpu_target} PRIVATE ${cpu_march_flag})

    resolve_target_sources(_ggml_sources ggml)
    add_library(${ggml_target} STATIC ${_ggml_sources})
    clone_target_metadata(ggml ${ggml_target})
    get_target_property(_ggml_link_libs ggml LINK_LIBRARIES)
    if(_ggml_link_libs AND NOT _ggml_link_libs STREQUAL "_ggml_link_libs-NOTFOUND")
        set(_ggml_variant_links)
        foreach(link_item IN LISTS _ggml_link_libs)
            if(link_item STREQUAL "ggml-cpu")
                list(APPEND _ggml_variant_links ${cpu_target})
            else()
                list(APPEND _ggml_variant_links ${link_item})
            endif()
        endforeach()
        target_link_libraries(${ggml_target} PUBLIC ${_ggml_variant_links})
    endif()

    resolve_target_sources(_llama_sources llama)
    add_library(${llama_target} STATIC ${_llama_sources})
    clone_target_metadata(llama ${llama_target})
    get_target_property(_llama_link_libs llama LINK_LIBRARIES)
    if(_llama_link_libs AND NOT _llama_link_libs STREQUAL "_llama_link_libs-NOTFOUND")
        set(_llama_variant_links)
        foreach(link_item IN LISTS _llama_link_libs)
            if(link_item STREQUAL "ggml")
                list(APPEND _llama_variant_links ${ggml_target})
            else()
                list(APPEND _llama_variant_links ${link_item})
            endif()
        endforeach()
        target_link_libraries(${llama_target} PUBLIC ${_llama_variant_links})
    endif()
endfunction()

function(add_pocket_llama_variant target_name march_flag linked_llama_target)
    add_library(${target_name} SHARED
        pocket_llama.cpp
        turboquant.c
    )
    target_include_directories(${target_name} PRIVATE
        "${LLAMA_CPP_ROOT}/include"
        "${LLAMA_CPP_ROOT}/ggml/include"
        "${LLAMA_CPP_ROOT}/src"
        "${LLAMA_CPP_ROOT}/tools/mtmd"
        "${LLAMA_CPP_ROOT}/vendor"
    )
    target_link_libraries(${target_name}
        ${linked_llama_target}
        mtmd
        android
        log
    )
    target_compile_options(${target_name} PRIVATE ${POCKET_LLAMA_COMPILE_FLAGS})
    target_link_options(${target_name} PRIVATE ${POCKET_LLAMA_LINK_FLAGS})
    if(NOT "${march_flag}" STREQUAL "")
        target_compile_options(${target_name} PRIVATE
            "$<$<COMPILE_LANGUAGE:C>:${march_flag}>"
            "$<$<COMPILE_LANGUAGE:CXX>:${march_flag}>"
        )
    endif()
endfunction()

if(TARGET llama)
    target_compile_options(llama PRIVATE ${POCKET_LLAMA_COMPILE_FLAGS})
    target_link_options(llama PRIVATE ${POCKET_LLAMA_LINK_FLAGS})
endif()

add_pocket_llama_variant(pocket_llama "" llama)
if(POCKETGPT_ARM64_SPECIALIZED_VARIANTS)
    create_cpu_specialized_stack("armv8_2_dotprod_i8mm" "-march=armv8.2-a+dotprod+i8mm")

    add_pocket_llama_variant(pocket_llama_v8 "-march=armv8-a" llama)
    add_pocket_llama_variant(pocket_llama_v8_2 "-march=armv8.2-a" llama)
    add_pocket_llama_variant(pocket_llama_v8_2_dotprod "-march=armv8.2-a+dotprod" llama)
    add_pocket_llama_variant(pocket_llama_v8_2_i8mm "-march=armv8.2-a+i8mm" llama)
    add_pocket_llama_variant(
        pocket_llama_v8_2_dotprod_i8mm
        "-march=armv8.2-a+dotprod+i8mm"
        llama-armv8_2_dotprod_i8mm
    )
endif()
