/*
 * Minimal native_handle_t definition from Android's system/core.
 * The full header lives in <cutils/native_handle.h> but isn't
 * available in all NDK versions.
 */
#ifndef NATIVE_HANDLE_H
#define NATIVE_HANDLE_H

#include <stdint.h>

typedef struct native_handle {
    int version;        /* sizeof(native_handle_t) */
    int numFds;         /* number of file descriptors at &data[0] */
    int numInts;        /* number of ints at &data[numFds] */
#if defined(__clang__)
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wzero-length-array"
#endif
    int data[0];        /* numFds + numInts ints */
#if defined(__clang__)
#pragma clang diagnostic pop
#endif
} native_handle_t;

#endif /* NATIVE_HANDLE_H */
