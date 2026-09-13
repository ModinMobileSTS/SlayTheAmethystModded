//
// Created by maks on 18.10.2023.
//

#ifndef POJAVLAUNCHER_COMMON_H
#define POJAVLAUNCHER_COMMON_H

#include <stdatomic.h>
#include <stdint.h>

#define STATE_RENDERER_ALIVE 0
#define STATE_RENDERER_NEW_WINDOW 1
#define STATE_RENDERER_NO_SURFACE 2

typedef struct {
    _Atomic char state;
    struct ANativeWindow *nativeSurface;
    struct ANativeWindow *newNativeSurface;
    uint64_t newSurfaceGeneration;
    uint64_t activeSurfaceGeneration;
    uint64_t latestSurfaceGeneration;
} basic_render_window_t;

struct ANativeWindow* pojavAcquireBridgeWindow(void);
struct ANativeWindow* pojavAcquireBridgeWindowWithGeneration(uint64_t* generation);
void pojavReleaseBridgeWindow(struct ANativeWindow* window);
void pojavAcknowledgeBridgeWindowGeneration(uint64_t generation);

#endif //POJAVLAUNCHER_COMMON_H
