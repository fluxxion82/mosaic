package com.jakewharton.mosaic.tty.terminal

import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration

internal actual class TtyLock {
	private val lock = ReentrantLock()

	actual fun lock() = lock.lock()
	actual fun tryLock(timeout: Duration): Boolean = lock.tryLock(timeout.inWholeNanoseconds, NANOSECONDS)
	actual fun unlock() = lock.unlock()
}
