cmake_minimum_required(VERSION 3.22.1)

Project(Pluvia)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

# --- virglrenderer (Mesa virgl GPU renderer) ---
add_subdirectory(virglrenderer)

# --- patchelf: kept as prebuilt (requires C++20 / newer NDK) ---
# add_subdirectory(patchelf)

# --- winlator (core JNI: X11 connector, GPU, audio, etc.) ---
# Note: winlator/gpu_image.c is excluded — it contains Ghidra decompiler
# artifacts.  The clean equivalent lives in extras/gpu_image.c (built as
# part of libextras.so).  gpu_helper.c provides vkGetDeviceExtensions.
add_library(winlator SHARED
            winlator/drawable.c
            winlator/gpu_helper.c
            winlator/sysvshared_memory.c
            winlator/xconnector_epoll.c
            winlator/alsa_client.c
            winlator/patchelf_wrapper.cpp)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      aaudio
                      EGL
                      GLESv2
                      GLESv3)

# --- extras (GPU helper + Vulkan + adrenotools) --- arm64 only
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_subdirectory(extras/adrenotools)

    add_library(extras SHARED
                extras/gpu_image.c
                extras/vulkan.c)

    target_link_libraries(extras
                          log
                          android
                          EGL
                          GLESv2
                          GLESv3
                          adrenotools)
endif()

# --- xconnectorpatch (X11 connector epoll native) ---
add_library(xconnectorpatch SHARED
            xconnectorpatch/xconnectorpatch.c)

target_link_libraries(xconnectorpatch log)
