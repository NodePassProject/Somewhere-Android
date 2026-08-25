/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Inherited from NodePassProject/Anywhere-Android at e9a9274 (2026-04-28).
 * Copyright is held by that project's authors, who are not named here
 * because the donor repository declares no holder — 1 of its 123 Kotlin
 * files carries a header. Modifications for Somewhere (2026) are under the
 * same licence; the only one so far is the JNI symbol prefix, which had to
 * change with the package name.
 *
 * Carried rather than rewritten because this is the shell the project was
 * always going to inherit; see docs/architecture.md. The donor is under
 * active development, so the point in time is recorded here and
 * `internal/scripts/upstream-sync.sh` reports what has moved since.
 */
#ifndef CC_H
#define CC_H

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <sys/time.h>

/* --- Types --- */
typedef uint8_t     u8_t;
typedef int8_t      s8_t;
typedef uint16_t    u16_t;
typedef int16_t     s16_t;
typedef uint32_t    u32_t;
typedef int32_t     s32_t;
typedef uintptr_t   mem_ptr_t;

/* --- Byte order: ARM64/x86_64 Android is little-endian --- */
#ifndef BYTE_ORDER
#define BYTE_ORDER LITTLE_ENDIAN
#endif

/* --- Structure packing --- */
#define PACK_STRUCT_BEGIN
#define PACK_STRUCT_STRUCT __attribute__((packed))
#define PACK_STRUCT_END
#define PACK_STRUCT_FIELD(x) x

/* --- Platform diagnostics --- */
#ifdef __ANDROID__
#include <android/log.h>
#include <stdarg.h>
static inline void _lwip_platform_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static inline void _lwip_platform_log(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_DEBUG, "lwIP", fmt, args);
    va_end(args);
}
/* lwIP passes double-parenthesized args: LWIP_PLATFORM_DIAG(("fmt", args)) */
#define LWIP_PLATFORM_DIAG(x)   do { _lwip_platform_log x; } while(0)
#define LWIP_PLATFORM_ASSERT(x) do { __android_log_print(ANDROID_LOG_ERROR, "lwIP", \
                                     "Assert \"%s\" failed at line %d in %s", \
                                     x, __LINE__, __FILE__); abort(); } while(0)
#else
#define LWIP_PLATFORM_DIAG(x)   do { printf x; } while(0)
#define LWIP_PLATFORM_ASSERT(x) do { printf("Assert \"%s\" failed at line %d in %s\n", \
                                     x, __LINE__, __FILE__); abort(); } while(0)
#endif

/* --- Compiler hints --- */
#ifndef LWIP_NO_STDDEF_H
#define LWIP_NO_STDDEF_H 0
#endif

#ifndef LWIP_NO_STDINT_H
#define LWIP_NO_STDINT_H 0
#endif

#ifndef LWIP_NO_INTTYPES_H
#define LWIP_NO_INTTYPES_H 0
#endif

/* --- Random number generation --- */
#define LWIP_RAND() ((u32_t)arc4random())

#endif /* CC_H */
