package com.jakewharton.mosaic.tty.terminal

import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.ReentrantLock
import platform.posix.usleep

internal actual class TtyLock {
	private val lock = ReentrantLock()

	actual fun lock() = lock.lock()

	actual fun tryLock(timeout: Duration): Boolean {
		// atomicfu has no timed acquire, so poll. This only runs on the rare, bounded shutdown path.
		val deadline = TimeSource.Monotonic.markNow() + timeout
		while (!lock.tryLock()) {
			if (deadline.hasPassedNow()) return false
			usleep(1_000u)
		}
		return true
	}

	actual fun unlock() = lock.unlock()
}
