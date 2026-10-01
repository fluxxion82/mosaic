#if defined(__APPLE__) || defined(__linux__)

#include "mosaic-utils-posix.h"

#include <errno.h>
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

MosaicIoResult mosaic_utils_read(
	int fd,
	int interruptFd,
	uint8_t *buffer,
	int count,
	struct timeval *timeout
) {
	MosaicIoResult result = {};

	struct timeval remaining;
	int64_t deadline = 0;
	if (timeout) {
		remaining = *timeout;
		deadline = mosaic_utils_monotonic_micros() + (int64_t) timeout->tv_sec * 1000000 + timeout->tv_usec;
	}

	fd_set fds;
	int nfds = 1 + ((fd > interruptFd) ? fd : interruptFd);
	int selected;
	while (true) {
		FD_ZERO(&fds);
		FD_SET(fd, &fds);
		FD_SET(interruptFd, &fds);

		selected = select(nfds, &fds, NULL, NULL, timeout ? &remaining : NULL);
		if (likely(selected >= 0) || errno != EINTR) {
			break;
		}

		// select() is not restarted after a signal handler runs, even with SA_RESTART.
		if (timeout) {
			int64_t left = deadline - mosaic_utils_monotonic_micros();
			if (left <= 0) {
				goto ret;
			}
			remaining.tv_sec = left / 1000000;
			remaining.tv_usec = left % 1000000;
		}
	}

	if (likely(selected >= 0)) {
		if (likely(FD_ISSET(fd, &fds) != 0)) {
			int c = read(fd, buffer, count);
			if (likely(c > 0)) {
				result.count = c;
			} else if (c == 0) {
				result.count = -1; // EOF
			} else {
				goto err;
			}
		} else if (unlikely(FD_ISSET(interruptFd, &fds) != 0)) {
			// Consume the single notification byte to clear the ready state for the next call.
			uint8_t space;
			if (unlikely(read(interruptFd, &space, 1) < 0)) {
				goto err;
			}
		}
		// Otherwise if the interrupt pipe was selected or we timed out, return a count of 0.
	} else {
		goto err;
	}

	ret:
	return result;

	err:
	result.error = errno;
	goto ret;
}

MosaicIoResult mosaic_utils_write(int writeFd, uint8_t *buffer, int count) {
	MosaicIoResult result = {};

	int written;
	do {
		written = write(writeFd, buffer, count);
	} while (unlikely(written == -1 && errno == EINTR));

	if (written != -1) {
		result.count = written;
	} else {
		result.error = errno;
	}

	return result;
}

#endif
