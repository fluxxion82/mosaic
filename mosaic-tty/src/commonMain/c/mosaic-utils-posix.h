#ifndef MOSAIC_UTILS_POSIX_H
#define MOSAIC_UTILS_POSIX_H

#include "mosaic-utils.h"

#include <sys/time.h>

MosaicIoResult mosaic_utils_read(
	int fd,
	int interruptFd,
	uint8_t *buffer,
	int count,
	struct timeval *timeout
);

typedef void MosaicUtilsEventCallback(void *opaque);

/**
 * Like mosaic_utils_read, but also waits on eventFd, a non-blocking pipe into which signal
 * handlers write notification bytes. Whenever it becomes readable it is drained and eventCallback
 * is invoked once, on the calling thread, before waiting again with the remaining timeout.
 */
MosaicIoResult mosaic_utils_read_with_event(
	int fd,
	int interruptFd,
	int eventFd,
	MosaicUtilsEventCallback *eventCallback,
	void *eventOpaque,
	uint8_t *buffer,
	int count,
	struct timeval *timeout
);

MosaicIoResult mosaic_utils_write(
	int writeFd,
	uint8_t *buffer,
	int count
);

/**
 * Write as much of buffer as the descriptor accepts within timeout, without ever blocking on it,
 * and return that count (possibly 0). One deadline covers partial writes and retries.
 */
MosaicIoResult mosaic_utils_write_with_timeout(
	int writeFd,
	uint8_t *buffer,
	int count,
	struct timeval *timeout
);

struct timeval mosaic_utils_timeval_from_millis(int millis);

/** How often a write has been retried after EINTR so far in this process. For tests. */
int mosaic_utils_write_eintr_retries(void);

#endif // MOSAIC_UTILS_POSIX_H
