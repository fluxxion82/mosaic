package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import com.jakewharton.mosaic.tty.TestTerminal
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** POSIX-only timeout behaviour; the Windows implementation is unchanged and untested here. */
class TtyPosixReadTimeoutTest {
	@Test fun negativeTimeoutBehavesAsNonBlocking() {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			tty.enableRawMode()

			val start = TimeSource.Monotonic.markNow()
			val read = tty.readWithTimeout(ByteArray(1), 0, 1, -1)

			assertThat(read).isEqualTo(0)
			assertThat(start.elapsedNow()).isLessThan(1_000.milliseconds)
		}
	}

	@Test fun staleInterruptOfAPreviousTtyIsDiscarded() {
		TestTerminal.bind().use { testTerminal ->
			// Nobody reads, so the notification stays queued in the process-wide pipe.
			testTerminal.tty.interruptRead()
		}
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			tty.enableRawMode()

			val start = TimeSource.Monotonic.markNow()
			val read = tty.readWithTimeout(ByteArray(1), 0, 1, 300)

			// A leftover interrupt would have returned immediately instead of waiting out the timeout.
			assertThat(read).isEqualTo(0)
			assertThat(start.elapsedNow()).isGreaterThanOrEqualTo(250.milliseconds)
		}
	}
}
