#ifndef MOSAIC_TTY_H
#define MOSAIC_TTY_H

#include "mosaic-utils.h"

#include <stdbool.h>
#include <stdint.h>

typedef struct MosaicTtyImpl MosaicTty;

typedef void MosaicTtyCallbackOnFocus(void *opaque, bool focused);
typedef void MosaicTtyCallbackOnKey(void *opaque); // TODO params
typedef void MosaicTtyCallbackOnMouse(void *opaque); // TODO params
typedef void MosaicTtyCallbackOnResize(void *opaque, int columns, int rows, int width, int height);

typedef struct MosaicTtyCallback {
	void *opaque;
	MosaicTtyCallbackOnFocus *onFocus;
	MosaicTtyCallbackOnKey *onKey;
	MosaicTtyCallbackOnMouse *onMouse;
	MosaicTtyCallbackOnResize *onResize;
} MosaicTtyCallback;

typedef struct MosaicTtyInitResult {
	MosaicTty *tty;
	uint32_t error;
	bool no_tty;
	bool already_bound;
} MosaicTtyInitResult;

typedef struct MosaicTtyTerminalSizeResult {
	int columns;
	int rows;
	int width;
	int height;
	uint32_t error;
} MosaicTtyTerminalSizeResult;

MOSAIC_EXPORT MosaicTtyInitResult mosaic_tty_init(void);

MOSAIC_EXPORT void mosaic_tty_set_callback(MosaicTty *tty, MosaicTtyCallback *callback);

MOSAIC_EXPORT MosaicIoResult mosaic_tty_read(MosaicTty *tty, uint8_t *buffer, int count);
MOSAIC_EXPORT MosaicIoResult mosaic_tty_read_with_timeout(MosaicTty *tty, uint8_t *buffer, int count, int timeoutMillis);
MOSAIC_EXPORT uint32_t mosaic_tty_interrupt_read(MosaicTty *tty);

MOSAIC_EXPORT MosaicIoResult mosaic_tty_write(MosaicTty *tty, uint8_t *buffer, int count);
// POSIX only: writes what the TTY accepts within timeoutMillis without blocking; the count may be partial or 0.
MOSAIC_EXPORT MosaicIoResult mosaic_tty_write_with_timeout(MosaicTty *tty, uint8_t *buffer, int count, int timeoutMillis);

MOSAIC_EXPORT uint32_t mosaic_tty_enable_raw_mode(MosaicTty *tty);
MOSAIC_EXPORT uint32_t mosaic_tty_enable_window_resize_events(MosaicTty *tty);
// POSIX only: SIGINT and SIGTERM interrupt a pending read instead of terminating the process.
MOSAIC_EXPORT uint32_t mosaic_tty_enable_shutdown_signal_interrupt(MosaicTty *tty);
// POSIX only: the last signal recorded by mosaic_tty_enable_shutdown_signal_interrupt, or 0.
MOSAIC_EXPORT int mosaic_tty_shutdown_signal(MosaicTty *tty);
MOSAIC_EXPORT MosaicTtyTerminalSizeResult mosaic_tty_current_terminal_size(MosaicTty *tty);

MOSAIC_EXPORT uint32_t mosaic_tty_reset(MosaicTty *tty);
// POSIX only: like mosaic_tty_reset, but applies the terminal settings without waiting for pending output.
MOSAIC_EXPORT uint32_t mosaic_tty_reset_immediately(MosaicTty *tty);
MOSAIC_EXPORT uint32_t mosaic_tty_free(MosaicTty *tty);

#endif // MOSAIC_TTY_H
