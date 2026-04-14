/*
 * Minimal SDL2 type stubs for evshim.c
 *
 * evshim loads SDL2 dynamically via dlopen/dlsym at runtime.
 * This header provides only the type definitions and constants
 * needed at compile time — no actual SDL2 library is linked.
 */
#ifndef SDL_STUB_H
#define SDL_STUB_H

#include <stdint.h>

/* --- SDL_version --- */
typedef struct SDL_version {
    uint8_t major;
    uint8_t minor;
    uint8_t patch;
} SDL_version;

/* --- SDL_Joystick (opaque) --- */
typedef struct SDL_Joystick SDL_Joystick;

/* --- SDL_JoystickType --- */
typedef enum {
    SDL_JOYSTICK_TYPE_UNKNOWN,
    SDL_JOYSTICK_TYPE_GAMECONTROLLER,
    SDL_JOYSTICK_TYPE_WHEEL,
    SDL_JOYSTICK_TYPE_ARCADE_STICK,
    SDL_JOYSTICK_TYPE_FLIGHT_STICK,
    SDL_JOYSTICK_TYPE_DANCE_PAD,
    SDL_JOYSTICK_TYPE_GUITAR,
    SDL_JOYSTICK_TYPE_DRUM_KIT,
    SDL_JOYSTICK_TYPE_ARCADE_PAD,
    SDL_JOYSTICK_TYPE_THROTTLE,
} SDL_JoystickType;

/* --- SDL_VirtualJoystickDesc --- */
#define SDL_VIRTUAL_JOYSTICK_DESC_VERSION 1

typedef struct SDL_VirtualJoystickDesc {
    uint16_t version;     /* SDL_VIRTUAL_JOYSTICK_DESC_VERSION */
    uint16_t type;        /* SDL_JoystickType */
    uint16_t naxes;
    uint16_t nbuttons;
    uint16_t nhats;
    uint16_t vendor_id;
    uint16_t product_id;
    uint16_t padding;
    uint32_t button_mask;
    uint32_t axis_mask;
    const char *name;
    void *userdata;
    void (*Update)(void *userdata);
    void (*SetPlayerIndex)(void *userdata, int player_index);
    int  (*Rumble)(void *userdata, uint16_t low_frequency_rumble, uint16_t high_frequency_rumble);
    int  (*RumbleTriggers)(void *userdata, uint16_t left_rumble, uint16_t right_rumble);
    int  (*SetLED)(void *userdata, uint8_t red, uint8_t green, uint8_t blue);
    int  (*SendEffect)(void *userdata, const void *data, int size);
} SDL_VirtualJoystickDesc;

/* --- SDL_Init flags --- */
#define SDL_INIT_JOYSTICK 0x00000200u

#endif /* SDL_STUB_H */
