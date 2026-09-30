package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import com.jakewharton.mosaic.tty.TestTerminal
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class TtyReadTimeoutTest {
	@Test fun timeoutOfAtLeastOneSecondIsHonored() {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			tty.enableRawMode()

			val start = TimeSource.Monotonic.markNow()
			val read = tty.readWithTimeout(ByteArray(1), 0, 1, 1_500)
			val elapsed = start.elapsedNow()

			assertThat(read).isEqualTo(0)
			assertThat(elapsed).isGreaterThanOrEqualTo(1_400.milliseconds)
		}
	}
}
