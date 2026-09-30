package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import com.jakewharton.mosaic.tty.TestTerminal
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TtyTerminalAlternateScreenTest {
	private val enter = "$CSI?${alternateScreenMode}h"
	private val leave = "$CSI?${alternateScreenMode}l"

	@Test fun alternateScreenIsEnteredOnceAndLeftOnceAroundTheRun() = runBlocking {
		val output = run(alternateScreen = true)

		assertThat(output.occurrencesOf(enter)).isEqualTo(1)
		assertThat(output.occurrencesOf(leave)).isEqualTo(1)
		// Hidden cursor, then the switch; the frame in between; back before the cursor reappears,
		// which is the same order the finalization hook uses on a shutdown signal.
		assertThat(output.indexOf(cursorDisable)).isLessThan(output.indexOf(enter))
		assertThat(output.indexOf(enter)).isLessThan(output.indexOf("frame"))
		assertThat(output.indexOf("frame")).isLessThan(output.indexOf(leave))
		assertThat(output.indexOf(leave)).isLessThan(output.indexOf(cursorEnable))
		// Entering clears to obtain a drawing area on terminals without an alternate buffer; leaving
		// must not wipe anything, since such a terminal keeps showing the last frame.
		assertThat(output.indexOf(clearScreen)).isLessThan(output.indexOf("frame"))
		// The switch goes out on its own, the clear right behind it.
		assertThat(output.indexOf(enter + clearScreen + cursorHome)).isGreaterThanOrEqualTo(0)
		assertThat(output.lastIndexOf(clearScreen)).isLessThan(output.indexOf("frame"))
	}

	@Test fun withoutTheOptionTheScreenIsNeverSwitched() = runBlocking {
		val output = run(alternateScreen = false)
		assertThat(output.occurrencesOf(enter)).isEqualTo(0)
		assertThat(output.occurrencesOf(leave)).isEqualTo(0)
	}

	private suspend fun run(alternateScreen: Boolean): String {
		TestTerminal.bind().use { testTerminal ->
			val drain = PtyDrain(testTerminal)
			drain.start()
			try {
				withTimeout(30.seconds) {
					testTerminal.tty.withTerminalIn(alternateScreen = alternateScreen) { _, output ->
						output("frame")
					}
				}
			} finally {
				drain.stop()
			}
			return drain.drained
		}
	}

	private fun String.occurrencesOf(needle: String): Int {
		var count = 0
		var index = indexOf(needle)
		while (index >= 0) {
			count++
			index = indexOf(needle, index + needle.length)
		}
		return count
	}
}
