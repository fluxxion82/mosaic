package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.tty.TestTerminal
import com.jakewharton.mosaic.tty.Tty
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import platform.posix.pthread_kill
import platform.posix.pthread_t
import platform.posix.usleep

/** State shared with a worker thread which blocks in a TTY call. */
@OptIn(ExperimentalAtomicApi::class)
internal class Blocked(val tty: Tty, val data: ByteArray) {
	val thread = AtomicReference<Any?>(null)
	val progress = AtomicInt(0)
	val failure = AtomicReference<Throwable?>(null)
	val done = AtomicInt(0)
}

/** Poll [condition] until it holds, failing after five seconds. */
internal fun await(description: String, condition: () -> Boolean) {
	val start = TimeSource.Monotonic.markNow()
	while (!condition()) {
		check(start.elapsedNow() < 5.seconds) { "Timed out waiting for $description" }
		usleep(1_000u)
	}
}

/** Wait until the worker has made progress and then stopped, meaning it is blocked. */
@OptIn(ExperimentalAtomicApi::class)
internal fun awaitNoProgress(state: Blocked) {
	val start = TimeSource.Monotonic.markNow()
	var last = -1
	var stableFor = 0
	while (stableFor < 20) {
		check(start.elapsedNow() < 5.seconds) { "Timed out waiting for the worker to block" }
		usleep(10_000u)
		val current = state.progress.load()
		if (current > 0 && current == last) {
			stableFor++
		} else {
			stableFor = 0
		}
		last = current
	}
}

/** Send [signum] to the worker thread recorded in [state]. */
@OptIn(ExperimentalAtomicApi::class)
internal fun signalThread(state: Blocked, signum: Int) {
	@Suppress("UNCHECKED_CAST")
	val thread = state.thread.load() as pthread_t
	assertThat(pthread_kill(thread, signum)).isEqualTo(0)
}

/** Drain [count] bytes of TTY output, giving up after five seconds or on a worker failure. */
@OptIn(ExperimentalAtomicApi::class)
internal fun readAll(testTerminal: TestTerminal, count: Int, failure: AtomicReference<Throwable?>): Int {
	val start = TimeSource.Monotonic.markNow()
	val read = ByteArray(count)
	var total = 0
	while (total < read.size && failure.load() == null) {
		check(start.elapsedNow() < 5.seconds) { "Timed out draining TTY output" }
		total += testTerminal.readTtyWithTimeout(read, total, read.size - total, 100)
	}
	return total
}

/**
 * Unblock whatever [state]'s job is stuck in and terminate [this] worker. Never waits for a job
 * which did not finish: a failed test must fail, not hang.
 */
@OptIn(ExperimentalAtomicApi::class, ObsoleteWorkersApi::class)
internal fun Worker.finish(testTerminal: TestTerminal, state: Blocked) {
	if (state.done.load() == 0) {
		state.tty.interruptRead()
		// A writer blocked on a full PTY only continues once its output is drained.
		val start = TimeSource.Monotonic.markNow()
		val buffer = ByteArray(64 * 1024)
		while (state.done.load() == 0 && start.elapsedNow() < 5.seconds) {
			testTerminal.readTtyWithTimeout(buffer, 0, buffer.size, 100)
		}
	}
	if (state.done.load() != 0) {
		requestTermination().result
	} else {
		requestTermination(processScheduledJobs = false)
	}
}
