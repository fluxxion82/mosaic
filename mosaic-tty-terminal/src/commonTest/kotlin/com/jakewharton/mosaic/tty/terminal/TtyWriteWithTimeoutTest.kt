package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import com.jakewharton.mosaic.tty.TestTerminal
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TtyWriteWithTimeoutTest {
	@Test fun writesEverythingWhenThereIsRoom() {
		TestTerminal.bind().use { testTerminal ->
			val bytes = "hello".encodeToByteArray()
			assertThat(testTerminal.tty.writeWithTimeout(bytes, 0, bytes.size, 1_000)).isEqualTo(bytes.size)
			val read = ByteArray(bytes.size)
			assertThat(testTerminal.readTtyWithTimeout(read, 0, read.size, 1_000)).isEqualTo(bytes.size)
			assertThat(read.decodeToString()).isEqualTo("hello")
		}
	}

	@Test fun givesUpAtTheDeadlineOnAPartiallyFullPty() = runBlocking {
		if (isWindows()) return@runBlocking

		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			val drain = PtyDrain(testTerminal)
			// A blocking write here would only end once the PTY is drained; this rescue makes such a
			// regression fail on the timing assertion instead of hanging.
			drain.startAfter(10.seconds)
			try {
				// Fill the PTY byte by byte until it accepts nothing more within the deadline.
				val one = byteArrayOf('x'.code.toByte())
				var filled = 0
				while (tty.writeWithTimeout(one, 0, 1, 100) == 1) {
					filled++
					check(filled < 4 * 1024 * 1024) { "The PTY never filled up" }
				}
				// Free a little room, with nobody competing for it.
				val room = ByteArray(4096)
				val freed = testTerminal.readTtyWithTimeout(room, 0, room.size, 1_000)
				check(freed > 0)

				// A write larger than that room must return what fit by the deadline, not block until
				// the rest can be written.
				val big = ByteArray(64 * 1024) { 'y'.code.toByte() }
				val start = TimeSource.Monotonic.markNow()
				val written = tty.writeWithTimeout(big, 0, big.size, 200)
				val elapsed = start.elapsedNow()
				assertThat(written).isLessThan(big.size)
				assertThat(elapsed).isLessThan(2.seconds)
			} finally {
				drain.stop()
			}
		}
	}

	@Test fun stopsAtTheDeadlineDespiteSteadyPartialProgress() = runBlocking {
		if (isWindows()) return@runBlocking

		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			val drain = PtyDrain(testTerminal)
			// A write which only stopped once the buffer was done would take many seconds at the slow
			// drain's pace; this rescue finishes it so a regression fails on timing instead of hanging.
			drain.startAfter(10.seconds)
			val slowDrain = CoroutineScope(Dispatchers.IO + SupervisorJob())
			try {
				val one = byteArrayOf('x'.code.toByte())
				var filled = 0
				while (tty.writeWithTimeout(one, 0, 1, 100) == 1) {
					filled++
					check(filled < 4 * 1024 * 1024) { "The PTY never filled up" }
				}

				// Drain slowly but steadily, so room keeps appearing a little at a time.
				slowDrain.launch {
					val chunk = ByteArray(1024)
					while (isActive) {
						testTerminal.readTtyWithTimeout(chunk, 0, chunk.size, 100)
						delay(20.milliseconds)
					}
				}

				// Much more than the slow drain frees before the deadline. The call must give up at
				// the deadline with what fit by then, not follow the drain until everything is written.
				val big = ByteArray(512 * 1024) { 'y'.code.toByte() }
				val start = TimeSource.Monotonic.markNow()
				val written = tty.writeWithTimeout(big, 0, big.size, 500)
				val elapsed = start.elapsedNow()
				assertThat(written).isGreaterThan(0)
				assertThat(written).isLessThan(big.size)
				assertThat(elapsed).isGreaterThanOrEqualTo(400.milliseconds)
				assertThat(elapsed).isLessThan(3.seconds)
			} finally {
				slowDrain.cancel()
				drain.stop()
			}
		}
	}
}
