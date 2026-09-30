package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.F1
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.F2
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.F3
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.F4
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.F5
import com.jakewharton.mosaic.terminal.UnknownEvent
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The Linux virtual console encodes F1 through F5 as `CSI [ A` through `CSI [ E`.
 */
class EventParserCsiLinuxConsoleKeyboardEventTest : BaseEventParserTest() {
	@Test fun f1() {
		testTerminal.write("$CSI[A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(F1))
	}

	@Test fun f2() {
		testTerminal.write("$CSI[B")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(F2))
	}

	@Test fun f3() {
		testTerminal.write("$CSI[C")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(F3))
	}

	@Test fun f4() {
		testTerminal.write("$CSI[D")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(F4))
	}

	@Test fun f5() {
		testTerminal.write("$CSI[E")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(F5))
	}

	@Test fun truncatedWaitsForMoreInput() = runBlocking {
		testTerminal.write("$CSI[")

		// The parser signals when it is about to read again, which proves it consumed the truncated
		// sequence from the first read and is waiting for the rest rather than emitting it as-is.
		val secondRead = CompletableDeferred<Unit>()
		var reads = 0
		val parser = EventParser(testTerminal.tty) {
			if (++reads == 2) secondRead.complete(Unit)
		}
		val event = async(Dispatchers.IO) { parser.next() }
		try {
			withTimeout(5.seconds) { secondRead.await() }

			testTerminal.write("A")
			assertThat(withTimeout(5.seconds) { event.await() }).isEqualTo(KeyboardEvent(F1))
		} finally {
			// Always wake the parser so a failed wait fails instead of hanging.
			testTerminal.tty.interruptRead()
		}
	}

	@Test fun otherFinalByteIsNotAFunctionKey() {
		testTerminal.write("$CSI[F")
		assertThat(parser.next()).isEqualTo(UnknownEvent("1b5b5b".hexToByteArray()))
		assertThat(parser.next()).isEqualTo(KeyboardEvent('F'.code))
	}

	@Test fun followedByOtherInput() {
		testTerminal.write("$CSI[Ab")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(F1))
		assertThat(parser.next()).isEqualTo(KeyboardEvent('b'.code))
	}
}
