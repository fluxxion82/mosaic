package com.jakewharton.mosaic.tty.terminal

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.jakewharton.mosaic.tty.TestTerminal
import kotlin.test.Test

class TtyWriteFullyTest {
	@Test fun writesEverythingToTty() {
		TestTerminal.bind().use { testTerminal ->
			val bytes = "héllo wörld".encodeToByteArray()
			testTerminal.tty.writeFully(bytes)

			val read = ByteArray(bytes.size)
			var total = 0
			while (total < read.size) {
				val count = testTerminal.readTtyWithTimeout(read, total, read.size - total, 100)
				if (count <= 0) break
				total += count
			}
			assertThat(read.decodeToString(0, total)).isEqualTo("héllo wörld")
		}
	}

	@Test fun shortWritesAreContinued() {
		val bytes = "hello".encodeToByteArray()
		val received = StringBuilder()
		writeFully(bytes, 1, 3) { buffer, offset, _ ->
			received.append(buffer[offset].toInt().toChar())
			1
		}
		assertThat(received.toString()).isEqualTo("ell")
	}

	@Test fun writeWithoutProgressFails() {
		val bytes = "hello".encodeToByteArray()
		assertFailure {
			writeFully(bytes, 0, bytes.size) { _, _, _ -> 0 }
		}.isInstanceOf<IllegalStateException>()
	}

	@Test fun invalidRangesFailBeforeWriting() {
		TestTerminal.bind().use { testTerminal ->
			val bytes = byteArrayOf('a'.code.toByte())
			listOf(
				-1 to 0,
				0 to -1,
				2 to 0,
				0 to 2,
				0 to Int.MAX_VALUE,
				Int.MAX_VALUE to 0,
			).forEach { (offset, count) ->
				assertFailure {
					testTerminal.tty.writeFully(bytes, offset, count)
				}.isInstanceOf<IndexOutOfBoundsException>()
			}
		}
	}

	@Test fun emptyRangeDoesNotWrite() {
		TestTerminal.bind().use { testTerminal ->
			testTerminal.tty.writeFully(byteArrayOf('a'.code.toByte()), 1, 0)
			val read = testTerminal.readTtyWithTimeout(ByteArray(1), 0, 1, 1)
			assertThat(read).isEqualTo(0)
		}
	}
}
