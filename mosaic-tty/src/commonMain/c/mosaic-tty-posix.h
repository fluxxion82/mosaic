#ifndef MOSAIC_TTY_POSIX_H
#define MOSAIC_TTY_POSIX_H

#include "mosaic-tty.h"

#include <signal.h>

#define MOSAIC_TTY_SHUTDOWN_SIGNAL_COUNT 4

typedef struct MosaicTtyImpl {
	int fd;
	MosaicTtyCallback *callback;
	bool sigwinch;
	struct sigaction saved_sigwinch;
	bool shutdown_signals;
	struct sigaction saved_shutdown[MOSAIC_TTY_SHUTDOWN_SIGNAL_COUNT];
	struct termios *saved;
} MosaicTtyImpl;

/** Bind ttyFd as the TTY. It is marked FD_CLOEXEC, as are the pipes created alongside it. */
MosaicTtyInitResult mosaic_tty_init_with_fd(int ttyFd);

/**
 * Install the shutdown-signal handler for signum, storing the previous disposition in saved when
 * it is not NULL. Exposed so tests can use a harmless signal.
 */
uint32_t mosaic_tty_install_shutdown_handler(int signum, struct sigaction *saved);

/** Whether the bound TTY and all of its pipe descriptors are close-on-exec. For tests. */
bool mosaic_tty_bound_descriptors_are_cloexec(void);

#endif // MOSAIC_TTY_POSIX_H
