package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.tty.StandardStreams
import com.jakewharton.mosaic.tty.TestTerminal
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class RunMosaicTtyTest {
	@Test fun framesAreWrittenToTheTtyAndNotToStandardOutput() = runTest {
		StandardStreams.bind().use { streams ->
			streams.interceptOtherWrites().use { intercepted ->
				TestTerminal.bind().use { testTerminal ->
					testTerminal.resize(columns = 20, rows = 5, width = 0, height = 0)

					// Drain the TTY concurrently. Otherwise, restoring its settings waits for the output
					// to be consumed and never returns.
					val tty = async(Dispatchers.IO) {
						readAll { buffer, offset, count ->
							testTerminal.readTty(buffer, offset, count)
						}
					}

					withTty(testTerminal.tty) { terminal, output ->
						runMosaic(terminal, output) {
							Text("Hello")
						}
					}

					testTerminal.interruptTtyRead()
					assertThat(tty.await()).contains("Hello")
				}

				val stdout = readAll { buffer, offset, count ->
					intercepted.readOutputWithTimeout(buffer, offset, count, 100)
				}
				assertThat(stdout).isEqualTo("")
			}
		}
	}

	/** Read until [read] returns 0 (interrupted or timed out) or -1 (EOF). */
	private fun readAll(read: (buffer: ByteArray, offset: Int, count: Int) -> Int): String {
		val bytes = ByteArray(64 * 1024)
		var size = 0
		while (size < bytes.size) {
			val count = read(bytes, size, bytes.size - size)
			if (count <= 0) break
			size += count
		}
		return bytes.decodeToString(0, size)
	}
}
