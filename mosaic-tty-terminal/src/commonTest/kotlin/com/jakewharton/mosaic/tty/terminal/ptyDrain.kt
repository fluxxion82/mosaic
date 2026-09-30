package com.jakewharton.mosaic.tty.terminal

import com.jakewharton.mosaic.tty.TestTerminal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads everything the TTY writes into [testTerminal] on a thread of its own scope, so that it
 * keeps working after the test's scope was cancelled and can always unblock a writer stuck on a
 * full PTY. [stop] runs non-cancellably and interrupts the read, so cleanup never hangs.
 */
class PtyDrain(private val testTerminal: TestTerminal) {
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val output = StringBuilder()
	private var job: Job? = null

	/** What was drained so far. Only complete once [stop] returned. */
	val drained: String get() = output.toString()

	fun start() {
		if (job != null) return
		job = scope.launch {
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val count = testTerminal.readTty(buffer, 0, buffer.size)
				if (count <= 0) break
				output.append(buffer.decodeToString(0, count))
			}
		}
	}

	/** Start draining after [delay] unless [stop] ran first; a rescue for tests which expect no stall. */
	fun startAfter(delay: Duration) {
		scope.launch {
			delay(delay)
			start()
		}
	}

	suspend fun stop() = withContext(NonCancellable) {
		start()
		// Give a drain which was only just started a moment to unblock a stuck writer first.
		delay(100)
		testTerminal.interruptTtyRead()
		withTimeoutOrNull(5.seconds) { job?.join() }
		scope.cancel()
	}
}
