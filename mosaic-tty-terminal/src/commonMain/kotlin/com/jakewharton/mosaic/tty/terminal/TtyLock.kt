package com.jakewharton.mosaic.tty.terminal

import kotlin.time.Duration

/** A reentrant mutual-exclusion lock which blocks the calling thread. */
internal expect class TtyLock() {
	fun lock()

	/** Acquire the lock, giving up after [timeout]. Returns whether it was acquired. */
	fun tryLock(timeout: Duration): Boolean

	fun unlock()
}

internal inline fun <T> TtyLock.withLock(block: () -> T): T {
	lock()
	try {
		return block()
	} finally {
		unlock()
	}
}
