package com.jakewharton.mosaic.tty.terminal

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.jakewharton.mosaic.tty.StandardStreams
import com.jakewharton.mosaic.tty.TestTerminal
import kotlin.test.Test

/** Every wrapper around a native read or write must reject a range outside the array. */
class TtyRangeCheckTest {
	private val bytes = ByteArray(4)
	private val invalidRanges = listOf(-1 to 1, 0 to -1, 5 to 0, 0 to 5, 3 to 2, 0 to Int.MAX_VALUE)

	private fun assertRejected(call: (offset: Int, count: Int) -> Int) {
		for ((offset, count) in invalidRanges) {
			assertFailure { call(offset, count) }.isInstanceOf<IndexOutOfBoundsException>()
		}
	}

	@Test fun ttyReadsAndWrites() {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			assertRejected { offset, count -> tty.read(bytes, offset, count) }
			assertRejected { offset, count -> tty.readWithTimeout(bytes, offset, count, 0) }
			assertRejected { offset, count -> tty.write(bytes, offset, count) }
		}
	}

	@Test fun testTerminalReadsAndWrites() {
		TestTerminal.bind().use { testTerminal ->
			assertRejected { offset, count -> testTerminal.writeTty(bytes, offset, count) }
			assertRejected { offset, count -> testTerminal.readTty(bytes, offset, count) }
			assertRejected { offset, count -> testTerminal.readTtyWithTimeout(bytes, offset, count, 0) }
			assertRejected { offset, count -> testTerminal.writeStandardInput(bytes, offset, count) }
			assertRejected { offset, count -> testTerminal.readStandardOutput(bytes, offset, count) }
			assertRejected { offset, count -> testTerminal.readStandardOutputWithTimeout(bytes, offset, count, 0) }
			assertRejected { offset, count -> testTerminal.readStandardError(bytes, offset, count) }
			assertRejected { offset, count -> testTerminal.readStandardErrorWithTimeout(bytes, offset, count, 0) }
		}
	}

	@Test fun standardStreamsReadsAndWrites() {
		StandardStreams.bind().use { streams ->
			assertRejected { offset, count -> streams.readInput(bytes, offset, count) }
			assertRejected { offset, count -> streams.readInputWithTimeout(bytes, offset, count, 0) }
			assertRejected { offset, count -> streams.writeOutput(bytes, offset, count) }
			assertRejected { offset, count -> streams.writeError(bytes, offset, count) }
			streams.interceptOtherWrites().use { intercepted ->
				assertRejected { offset, count -> intercepted.readOutput(bytes, offset, count) }
				assertRejected { offset, count -> intercepted.readOutputWithTimeout(bytes, offset, count, 0) }
				assertRejected { offset, count -> intercepted.readError(bytes, offset, count) }
				assertRejected { offset, count -> intercepted.readErrorWithTimeout(bytes, offset, count, 0) }
			}
		}
	}

	@Test fun emptyRangeAtTheEndIsValid() {
		TestTerminal.bind().use { testTerminal ->
			assertThat(testTerminal.tty.write(bytes, bytes.size, 0)).isEqualTo(0)
		}
	}
}
