#if defined(__APPLE__) || defined(__linux__)

#include "mosaic-tty-posix.h"
#include "mosaic-utils-posix.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

static _Atomic(MosaicTty *) globalTty;

/**
 * Everything a signal handler touches lives for the whole process and is never freed or closed.
 * A handler which was preempted while running can therefore resume after the TTY it was notifying
 * has been reset, closed and even replaced, and still only write into a pipe which is open and
 * whose bytes the next bound TTY drains before use. It also leaves no window in which a handler
 * could write into a closed pipe (SIGPIPE) or a reused descriptor.
 *
 * Two limits follow from this design and are documented on the Kotlin API:
 * - The state is created once per process (pthread_once) and inherited across fork(). A child
 *   which does not exec() shares the pipes, so a notification written by a handler in either
 *   process may be consumed by either process.
 * - A handler suspended across an unbind and a rebind delivers its notification, or its claim of
 *   the shutdown signal, to whichever TTY is bound when it resumes, or to nobody if the rebind
 *   drained it in between. Bind the TTY once per process and keep it bound.
 */
typedef struct MosaicTtySignalState {
	int interrupt_fd_reader;
	int interrupt_fd_writer;
	int resize_fd_reader;
	int resize_fd_writer;
	uint32_t error;
	// Claimed by the first shutdown-signal handler with a lock-free compare-and-exchange, so that
	// concurrent handlers on other threads cannot both believe they were first.
	_Atomic int shutdown_signal;
} MosaicTtySignalState;

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "the shutdown signal must be claimable from a signal handler");

static MosaicTtySignalState signalState = { -1, -1, -1, -1, 0, 0 };
static pthread_once_t signalStateOnce = PTHREAD_ONCE_INIT;

/** Keep the descriptor out of child processes; macOS has neither pipe2 nor O_CLOEXEC for pipe(). */
static uint32_t mosaic_tty_set_cloexec(int fd) {
	int flags = fcntl(fd, F_GETFD);
	if (unlikely(flags == -1 || fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == -1)) {
		return errno;
	}
	return 0;
}

static uint32_t mosaic_tty_make_nonblocking(int fd) {
	int flags = fcntl(fd, F_GETFL);
	if (unlikely(flags == -1 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1)) {
		return errno;
	}
	return 0;
}

/** Both ends of every signal pipe are close-on-exec and non-blocking: handlers must never block. */
static uint32_t mosaic_tty_create_signal_pipe(int fds[2]) {
	if (unlikely(pipe(fds) != 0)) {
		return errno;
	}
	uint32_t error;
	if (unlikely((error = mosaic_tty_set_cloexec(fds[0])) != 0)
		|| unlikely((error = mosaic_tty_set_cloexec(fds[1])) != 0)
		|| unlikely((error = mosaic_tty_make_nonblocking(fds[0])) != 0)
		|| unlikely((error = mosaic_tty_make_nonblocking(fds[1])) != 0)) {
		close(fds[0]);
		close(fds[1]);
		return error;
	}
	return 0;
}

static void mosaic_tty_create_signal_state(void) {
	int interruptPipe[2];
	uint32_t error = mosaic_tty_create_signal_pipe(interruptPipe);
	if (unlikely(error != 0)) {
		signalState.error = error;
		return;
	}
	int resizePipe[2];
	error = mosaic_tty_create_signal_pipe(resizePipe);
	if (unlikely(error != 0)) {
		close(interruptPipe[0]);
		close(interruptPipe[1]);
		signalState.error = error;
		return;
	}
	signalState.interrupt_fd_reader = interruptPipe[0];
	signalState.interrupt_fd_writer = interruptPipe[1];
	signalState.resize_fd_reader = resizePipe[0];
	signalState.resize_fd_writer = resizePipe[1];
}

/** Discard notifications left over from a previous TTY (the reading end is non-blocking). */
static void mosaic_tty_drain_pipe(int fd) {
	uint8_t stale[64];
	while (read(fd, stale, sizeof(stale)) > 0) {
	}
}

/** Bind fd, which is marked close-on-exec, as the TTY. */
MosaicTtyInitResult mosaic_tty_init_with_fd(int fd) {
	MosaicTtyInitResult result = {};

	pthread_once(&signalStateOnce, mosaic_tty_create_signal_state);
	if (unlikely(signalState.error != 0)) {
		result.error = signalState.error;
		goto ret;
	}

	MosaicTtyImpl *tty = calloc(1, sizeof(MosaicTtyImpl));
	if (unlikely(tty == NULL)) {
		// result.tty is set to 0 which will trigger OOM.
		goto ret;
	}

	result.error = mosaic_tty_set_cloexec(fd);
	if (unlikely(result.error != 0)) {
		goto err;
	}

	tty->fd = fd;
	result.tty = tty;

	MosaicTty *expected = NULL;
	if (likely(tty) && !atomic_compare_exchange_strong(&globalTty, &expected, tty)) {
		// We initialized an instance but there already was a global instance.
		result.tty = NULL;
		result.error = mosaic_tty_free(tty);
		result.already_bound = true;
		goto ret;
	}

	// Start from a clean slate: no pending notifications and no recorded signal from a previous TTY.
	mosaic_tty_drain_pipe(signalState.interrupt_fd_reader);
	mosaic_tty_drain_pipe(signalState.resize_fd_reader);
	atomic_store(&signalState.shutdown_signal, 0);

	ret:
	return result;

	err:
	free(tty);
	goto ret;
}

MosaicTtyInitResult mosaic_tty_init(void) {
	int fd = open("/dev/tty", O_RDWR | O_CLOEXEC);
	if (likely(fd != -1)) {
		return mosaic_tty_init_with_fd(fd);
	}

	MosaicTtyInitResult result = {};
	result.error = errno;
	result.no_tty = true;
	return result;
}

void mosaic_tty_set_callback(MosaicTty *tty, MosaicTtyCallback *callback) {
	tty->callback = callback;
}

/** Runs on the reading thread, in ordinary (non-signal) context, after a SIGWINCH notification. */
static void mosaic_tty_dispatch_resize(void *opaque) {
	MosaicTty *tty = opaque;
	struct winsize size;
	if (likely(ioctl(tty->fd, TIOCGWINSZ, &size) != -1)) {
		MosaicTtyCallback *callback = tty->callback;
		if (likely(callback)) {
			callback->onResize(callback->opaque, size.ws_col, size.ws_row, size.ws_xpixel, size.ws_ypixel);
		} else {
			// TODO Send warning somewhere? Maybe once we get debug logs working.
		}
	} else {
		// TODO Send errno somewhere? Maybe once we get debug logs working.
	}
}

static MosaicIoResult mosaic_tty_read_internal(
	MosaicTty *tty,
	uint8_t *buffer,
	int count,
	struct timeval *timeout
) {
	return mosaic_utils_read_with_event(
		tty->fd,
		signalState.interrupt_fd_reader,
		signalState.resize_fd_reader,
		mosaic_tty_dispatch_resize,
		tty,
		buffer,
		count,
		timeout
	);
}

MosaicIoResult mosaic_tty_read(MosaicTty *tty, uint8_t *buffer, int count) {
	return mosaic_tty_read_internal(tty, buffer, count, NULL);
}

MosaicIoResult mosaic_tty_read_with_timeout(
	MosaicTty *tty,
	uint8_t *buffer,
	int count,
	int timeoutMillis
) {
	struct timeval timeout = mosaic_utils_timeval_from_millis(timeoutMillis);
	return mosaic_tty_read_internal(tty, buffer, count, &timeout);
}

uint32_t mosaic_tty_interrupt_read(MosaicTty *tty UNUSED) {
	uint8_t space = ' ';
	MosaicIoResult result = mosaic_utils_write(signalState.interrupt_fd_writer, &space, 1);
	if (result.error == EAGAIN || result.error == EWOULDBLOCK) {
		return 0; // The pipe is full, so an interrupt is already pending.
	}
	return result.error;
}

MosaicIoResult mosaic_tty_write(MosaicTty *tty, uint8_t *buffer, int count) {
	return mosaic_utils_write(tty->fd, buffer, count);
}

MosaicIoResult mosaic_tty_write_with_timeout(MosaicTty *tty, uint8_t *buffer, int count, int timeoutMillis) {
	struct timeval timeout = mosaic_utils_timeval_from_millis(timeoutMillis);
	return mosaic_utils_write_with_timeout(tty->fd, buffer, count, &timeout);
}

/**
 * Signal handlers may only call async-signal-safe functions, so they merely write a byte into a
 * non-blocking pipe and preserve errno. The reading thread drains the pipe and does the real work.
 * A full pipe already represents a pending notification, so a failed write is ignored.
 */
static void mosaic_tty_notify(int writerFd) {
	int savedErrno = errno;
	uint8_t event = 0;
	ssize_t ignored = write(writerFd, &event, 1);
	(void) ignored;
	errno = savedErrno;
}

static void mosaic_tty_sigwinch_handler(int value UNUSED) {
	mosaic_tty_notify(signalState.resize_fd_writer);
}

static void mosaic_tty_shutdown_signal_handler(int signum) {
	int expected = 0;
	if (!atomic_compare_exchange_strong(&signalState.shutdown_signal, &expected, signum)) {
		// Not the first: the earlier signal is still being handled, e.g. because the restore waits
		// behind a frame stuck in flow control. Give up on restoring the TTY and terminate the way
		// this signal would have. Both calls are async-signal-safe.
		signal(signum, SIG_DFL);
		raise(signum);
		return;
	}
	mosaic_tty_notify(signalState.interrupt_fd_writer);
}

// Every signal which terminates the process by default and which the environment sends to ask
// for that: an interactive interrupt or quit, a kill, and a hangup of the controlling terminal.
static const int mosaic_tty_shutdown_signums[MOSAIC_TTY_SHUTDOWN_SIGNAL_COUNT] = {
	SIGINT, SIGTERM, SIGHUP, SIGQUIT,
};

uint32_t mosaic_tty_install_shutdown_handler(int signum, struct sigaction *saved) {
	// By convention an inherited SIG_IGN (nohup, a background job) is kept rather than overridden.
	struct sigaction current;
	if (unlikely(sigaction(signum, NULL, &current) != 0)) {
		return errno;
	}
	if (current.sa_handler == SIG_IGN) {
		if (saved) *saved = current;
		return 0;
	}

	struct sigaction action;
	action.sa_handler = mosaic_tty_shutdown_signal_handler;
	// Hold the other shutdown signals while one is handled, so they queue up instead of nesting.
	sigemptyset(&action.sa_mask);
	for (int i = 0; i < MOSAIC_TTY_SHUTDOWN_SIGNAL_COUNT; i++) {
		sigaddset(&action.sa_mask, mosaic_tty_shutdown_signums[i]);
	}
	action.sa_flags = SA_RESTART;
	if (unlikely(sigaction(signum, &action, saved) != 0)) {
		return errno;
	}
	return 0;
}

uint32_t mosaic_tty_enable_raw_mode(MosaicTty *tty) {
	uint32_t result = 0;

	if (unlikely(tty->saved)) {
		goto ret; // Already enabled!
	}

	struct termios *saved = calloc(1, sizeof(struct termios));
	if (unlikely(saved == NULL)) {
		result = ENOMEM;
		goto ret;
	}

	if (unlikely(tcgetattr(tty->fd, saved) != 0)) {
		result = errno;
		goto err;
	}

	struct termios current = (*saved);

	// Flags as defined by "Raw mode" section of https://linux.die.net/man/3/termios
	current.c_iflag &= ~(BRKINT | ICRNL | IGNBRK | IGNCR | INLCR | ISTRIP | IXON | PARMRK);
	current.c_oflag &= ~(OPOST);
	// Setting ECHONL should be useless here, but it is what is documented for cfmakeraw.
	current.c_lflag &= ~(ECHO | ECHONL | ICANON | IEXTEN | ISIG);
	current.c_cflag &= ~(CSIZE | PARENB);
	current.c_cflag |= (CS8);

	current.c_cc[VMIN] = 1;
	current.c_cc[VTIME] = 0;

	if (unlikely(tcsetattr(tty->fd, TCSAFLUSH, &current) != 0)) {
		result = errno;
		// Try to restore the saved config.
		tcsetattr(tty->fd, TCSAFLUSH, saved);
		goto err;
	}

	tty->saved = saved;

	ret:
	return result;

	err:
	free(saved);
	goto ret;
}

uint32_t mosaic_tty_enable_window_resize_events(MosaicTty *tty) {
	if (tty->sigwinch) {
		return 0; // Already installed.
	}

	struct sigaction action;
	action.sa_handler = mosaic_tty_sigwinch_handler;
	sigemptyset(&action.sa_mask);
	// The reading thread learns about the resize through the pipe, so nothing relies on the signal
	// interrupting a system call. Restart them so unrelated blocking calls do not fail with EINTR.
	// (select() is still interrupted regardless; mosaic_utils_read retries with its remaining timeout.)
	action.sa_flags = SA_RESTART;

	if (likely(sigaction(SIGWINCH, &action, &tty->saved_sigwinch) == 0)) {
		tty->sigwinch = true;
		return 0;
	}
	return errno;
}

uint32_t mosaic_tty_enable_shutdown_signal_interrupt(MosaicTty *tty) {
	if (tty->shutdown_signals) {
		return 0; // Already installed.
	}

	for (int i = 0; i < MOSAIC_TTY_SHUTDOWN_SIGNAL_COUNT; i++) {
		uint32_t error = mosaic_tty_install_shutdown_handler(mosaic_tty_shutdown_signums[i], &tty->saved_shutdown[i]);
		if (unlikely(error != 0)) {
			for (int j = 0; j < i; j++) {
				sigaction(mosaic_tty_shutdown_signums[j], &tty->saved_shutdown[j], NULL);
			}
			return error;
		}
	}

	tty->shutdown_signals = true;
	return 0;
}

bool mosaic_tty_bound_descriptors_are_cloexec(void) {
	MosaicTty *tty = atomic_load(&globalTty);
	if (tty == NULL) {
		return false;
	}
	const int fds[] = {
		tty->fd,
		signalState.interrupt_fd_reader,
		signalState.interrupt_fd_writer,
		signalState.resize_fd_reader,
		signalState.resize_fd_writer,
	};
	for (size_t i = 0; i < sizeof(fds) / sizeof(fds[0]); i++) {
		int flags = fcntl(fds[i], F_GETFD);
		if (flags == -1 || (flags & FD_CLOEXEC) == 0) {
			return false;
		}
	}
	return true;
}

int mosaic_tty_shutdown_signal(MosaicTty *tty UNUSED) {
	return atomic_load(&signalState.shutdown_signal);
}

MosaicTtyTerminalSizeResult mosaic_tty_current_terminal_size(MosaicTty *tty) {
	MosaicTtyTerminalSizeResult result = {};

	struct winsize size;
	if (ioctl(tty->fd, TIOCGWINSZ, &size) != -1) {
		result.columns = size.ws_col;
		result.rows = size.ws_row;
		result.width = size.ws_xpixel;
		result.height = size.ws_ypixel;
	} else {
		result.error = errno;
	}

	return result;
}

static uint32_t mosaic_tty_reset_with(MosaicTty *tty, int termiosAction) {
	uint32_t result = 0;

	// Terminal settings first: a shutdown signal arriving now is still recorded rather than acted
	// on, so the process cannot exit with the terminal left raw. Only then put back whatever signal
	// dispositions were in place before, such as an inherited SIG_IGN. The recorded signal number is
	// deliberately kept so the caller can redeliver it once everything is restored.
	if (tty->saved) {
		if (unlikely(tcsetattr(tty->fd, termiosAction, tty->saved) != 0)) {
			result = errno;
		}
		if (termiosAction != TCSAFLUSH) {
			// TCSAFLUSH would have discarded it: raw-mode typeahead must not reach whatever runs next.
			// Only the input is flushed; the output may be stuck and is deliberately left alone.
			if (unlikely(tcflush(tty->fd, TCIFLUSH) != 0 && result == 0)) {
				result = errno;
			}
		}
		free(tty->saved);
		tty->saved = NULL;
	}

	if (tty->sigwinch) {
		if (unlikely(sigaction(SIGWINCH, &tty->saved_sigwinch, NULL) != 0 && result == 0)) {
			result = errno;
		}
		tty->sigwinch = false;
	}
	if (tty->shutdown_signals) {
		for (int i = 0; i < MOSAIC_TTY_SHUTDOWN_SIGNAL_COUNT; i++) {
			if (unlikely(sigaction(mosaic_tty_shutdown_signums[i], &tty->saved_shutdown[i], NULL) != 0 && result == 0)) {
				result = errno;
			}
		}
		tty->shutdown_signals = false;
	}

	return result;
}

uint32_t mosaic_tty_reset(MosaicTty *tty) {
	// Wait for pending output, so the restore sequences reach the terminal under the old settings,
	// and drop unread input, so raw-mode bytes typed ahead do not reach whatever runs next.
	return mosaic_tty_reset_with(tty, TCSAFLUSH);
}

uint32_t mosaic_tty_reset_immediately(MosaicTty *tty) {
	// For a TTY whose output is stuck (flow control, a dead PTY): waiting would never end.
	return mosaic_tty_reset_with(tty, TCSANOW);
}

uint32_t mosaic_tty_free(MosaicTty *tty) {
	uint32_t result = 0;

	uint32_t resetResult = mosaic_tty_reset(tty);
	if (resetResult != 0) {
		result = resetResult;
	}

	// Only clear the global if it is this instance: a failed second bind frees its own instance, not
	// the bound one. The signal pipes are process-wide and stay open; see MosaicTtySignalState.
	MosaicTty *expected = tty;
	atomic_compare_exchange_strong(&globalTty, &expected, NULL);

	if (unlikely(close(tty->fd) != 0 && result == 0)) {
		result = errno;
	}

	free(tty);
	return result;
}

#endif
