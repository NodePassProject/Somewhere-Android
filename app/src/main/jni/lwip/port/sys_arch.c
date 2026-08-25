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
#include "lwip/sys.h"
#include <time.h>

u32_t sys_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (u32_t)(ts.tv_sec * 1000ULL + ts.tv_nsec / 1000000ULL);
}
