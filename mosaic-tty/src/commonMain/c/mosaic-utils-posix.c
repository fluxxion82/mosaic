#if defined(__APPLE__) || defined(__linux__)

#include "mosaic-utils-posix.h"

#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <sys/select.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static int64_t mosaic_utils_monotonic_micros(void) {
	struct timespec now;
	clock_gettime(CLOCK_MONOTONIC, &now);
	return (int64_t) now.tv_sec * 1000000 + now.tv_nsec / 1000;
}

struct timeval mosaic_utils_timeval_from_millis(int millis) {
	// tv_usec must stay below one second or select() fails with EINVAL.
	if (millis < 0) millis = 0;
	struct timeval timeout;
	timeout.tv_sec = millis / 1000;
	timeout.tv_usec = (millis % 1000) * 1000;
	return timeout;
}

static MosaicIoResult mosaic_utils_read_internal(
	int fd,
	int interruptFd,
	int eventFd,
	MosaicUtilsEventCallback *eventCallback,
	void *eventOpaque,
	uint8_t *buffer,
	int count,
	struct timeval *timeout
) {
	MosaicIoResult result = {};

	// A signal (such as SIGWINCH on resize) interrupts select() with EINTR regardless of SA_RESTART.
	// Retry with whatever remains of the timeout.
	int64_t deadline = 0;
	struct timeval remaining;
	if (timeout) {
		deadline = mosaic_utils_monotonic_micros() + (int64_t) timeout->tv_sec * 1000000 + timeout->tv_usec;
		remaining = *timeout;
	}

	int maxFd = (fd > interruptFd) ? fd : interruptFd;
	if (eventFd > maxFd) maxFd = eventFd;
	int nfds = 1 + maxFd;

	fd_set fds;
	while (true) {
		FD_ZERO(&fds);
		FD_SET(fd, &fds);
		FD_SET(interruptFd, &fds);
		if (eventFd >= 0) FD_SET(eventFd, &fds);

		int selected = select(nfds, &fds, NULL, NULL, timeout ? &remaining : NULL);
		if (unlikely(selected < 0)) {
			if (errno != EINTR) goto err;
			// Interrupted by a signal. Fall through to wait again with the remaining timeout.
		} else {
			if (eventFd >= 0 && FD_ISSET(eventFd, &fds) != 0) {
				// Drain every queued notification so that a burst of signals produces a single callback.
				uint8_t events[64];
				int c;
				do {
					c = read(eventFd, events, sizeof(events));
				} while (c > 0 || unlikely(c == -1 && errno == EINTR));
				if (unlikely(c == -1 && errno != EAGAIN && errno != EWOULDBLOCK)) goto err;
				eventCallback(eventOpaque);
			}
			if (FD_ISSET(fd, &fds) != 0) {
				int c;
				do {
					c = read(fd, buffer, count);
				} while (unlikely(c == -1 && errno == EINTR));
				if (likely(c > 0)) {
					result.count = c;
					break;
				}
				if (c == 0) {
					result.count = -1; // EOF
					break;
				}
				if (errno != EAGAIN && errno != EWOULDBLOCK) goto err;
				// The descriptor is momentarily non-blocking (see mosaic_utils_write_with_timeout) and the
				// data was taken by someone else. Wait again.
			}
			if (FD_ISSET(interruptFd, &fds) != 0) {
				// Consume the single notification byte to clear the ready state for the next call.
				uint8_t space;
				int c;
				do {
					c = read(interruptFd, &space, 1);
				} while (unlikely(c == -1 && errno == EINTR));
				// A non-blocking pipe may have been drained by someone else in the meantime.
				if (unlikely(c < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) goto err;
				break; // Interrupted, return a count of 0.
			}
			if (selected == 0) break; // Timed out, return a count of 0.
			// Only the event pipe was readable. Wait again for data.
		}

		if (timeout) {
			int64_t left = deadline - mosaic_utils_monotonic_micros();
			if (left <= 0) break; // Timed out while handling the signal or event.
			remaining.tv_sec = left / 1000000;
			remaining.tv_usec = left % 1000000;
		}
	}

	ret:
	return result;

	err:
	result.error = errno;
	goto ret;
}

MosaicIoResult mosaic_utils_read(
	int fd,
	int interruptFd,
	uint8_t *buffer,
	int count,
	struct timeval *timeout
) {
	return mosaic_utils_read_internal(fd, interruptFd, -1, NULL, NULL, buffer, count, timeout);
}

MosaicIoResult mosaic_utils_read_with_event(
	int fd,
	int interruptFd,
	int eventFd,
	MosaicUtilsEventCallback *eventCallback,
	void *eventOpaque,
	uint8_t *buffer,
	int count,
	struct timeval *timeout
) {
	return mosaic_utils_read_internal(
		fd,
		interruptFd,
		eventFd,
		eventCallback,
		eventOpaque,
		buffer,
		count,
		timeout
	);
}

// Counts the retries below, so a test can tell that a signal really interrupted a write.
static _Atomic int writeEintrRetries;

int mosaic_utils_write_eintr_retries(void) {
	return atomic_load(&writeEintrRetries);
}

MosaicIoResult mosaic_utils_write(int writeFd, uint8_t *buffer, int count) {
	MosaicIoResult result = {};

	int written;
	while (true) {
		written = write(writeFd, buffer, count);
		if (likely(written != -1)) break;
		if (errno == EINTR) {
			// A signal arrived before any byte was written. Nothing was written, so retry.
			atomic_fetch_add(&writeEintrRetries, 1);
			continue;
		}
		if (errno == EAGAIN || errno == EWOULDBLOCK) {
			// Another thread temporarily made the descriptor non-blocking (a bounded emergency write
			// during shutdown). Wait for room the way a blocking write would have.
			fd_set fds;
			FD_ZERO(&fds);
			FD_SET(writeFd, &fds);
			if (select(writeFd + 1, NULL, &fds, NULL, NULL) == -1 && errno != EINTR) break;
			continue;
		}
		break;
	}

	if (written != -1) {
		result.count = written;
	} else {
		result.error = errno;
	}

	return result;
}

/** Wait until fd is writable, or until the deadline. Returns 1 when writable, 0 otherwise. */
static int mosaic_utils_await_writable(int fd, int64_t deadline) {
	fd_set fds;
	while (true) {
		int64_t left = deadline - mosaic_utils_monotonic_micros();
		if (left <= 0) return 0;
		struct timeval remaining;
		remaining.tv_sec = left / 1000000;
		remaining.tv_usec = left % 1000000;
		FD_ZERO(&fds);
		FD_SET(fd, &fds);
		int selected = select(fd + 1, NULL, &fds, NULL, &remaining);
		if (selected > 0) return 1;
		if (selected == 0) return 0;
		if (errno != EINTR) return 0;
	}
}

MosaicIoResult mosaic_utils_write_with_timeout(int writeFd, uint8_t *buffer, int count, struct timeval *timeout) {
	MosaicIoResult result = {};
	int64_t deadline = mosaic_utils_monotonic_micros() + (int64_t) timeout->tv_sec * 1000000 + timeout->tv_usec;

	// Only a non-blocking write is bounded: once select() reports room, a blocking write of more
	// than that room would still block until the rest drains. So toggle O_NONBLOCK for the
	// duration of this call; the plain read and write paths cope with EAGAIN in the meantime.
	int flags = fcntl(writeFd, F_GETFL);
	if (unlikely(flags == -1)) {
		result.error = errno;
		return result;
	}
	bool toggled = (flags & O_NONBLOCK) == 0;
	if (toggled && unlikely(fcntl(writeFd, F_SETFL, flags | O_NONBLOCK) == -1)) {
		result.error = errno;
		return result;
	}

	// One deadline for the whole buffer: partial writes and retries never extend it, and even
	// steady partial progress (a slowly draining destination) stops once the deadline has passed.
	int written = 0;
	while (written < count) {
		ssize_t n = write(writeFd, buffer + written, count - written);
		if (n > 0) {
			written += n;
			if (deadline - mosaic_utils_monotonic_micros() <= 0) break;
			continue;
		}
		if (n == 0) break;
		if (errno == EINTR) {
			if (deadline - mosaic_utils_monotonic_micros() <= 0) break;
			continue;
		}
		if (errno != EAGAIN && errno != EWOULDBLOCK) {
			result.error = errno;
			break;
		}
		if (!mosaic_utils_await_writable(writeFd, deadline)) break; // Timed out with what was written.
	}

	if (toggled) {
		int savedErrno = errno;
		fcntl(writeFd, F_SETFL, flags);
		errno = savedErrno;
	}
	if (result.error == 0) {
		result.count = written;
	}
	return result;
}

#endif
