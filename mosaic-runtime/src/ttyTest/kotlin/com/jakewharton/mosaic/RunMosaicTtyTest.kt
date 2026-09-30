package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import com.jakewharton.mosaic.tty.StandardStreams
import com.jakewharton.mosaic.tty.TestTerminal
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

					withTty(testTerminal.tty, alternateScreen = false) { terminal, output ->
						runMosaic(terminal, output, RenderMode.Inline) {
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

	@Test fun fullScreenFramesGoToTheAlternateScreen() = runTest {
		TestTerminal.bind().use { testTerminal ->
			testTerminal.resize(columns = 20, rows = 5, width = 0, height = 0)

			val tty = async(Dispatchers.IO) {
				readAll { buffer, offset, count ->
					testTerminal.readTty(buffer, offset, count)
				}
			}

			withTty(testTerminal.tty, alternateScreen = true) { terminal, output ->
				runMosaic(terminal, output, RenderMode.FullScreen) {
					Text("Mode ${LocalRenderMode.current}")
				}
			}

			testTerminal.interruptTtyRead()
			val output = tty.await()
			val enter = "\u001B[?1049h"
			val leave = "\u001B[?1049l"
			assertThat(output).contains("Mode FullScreen")
			assertThat(output.indexOf(enter)).isLessThan(output.indexOf("Mode FullScreen"))
			assertThat(output.indexOf("Mode FullScreen")).isLessThan(output.indexOf(leave))
			assertThat(output.indexOf(leave)).isLessThan(output.indexOf("\u001B[?25h"))
		}
	}

	@Test fun resizeBackToTheSameSizeRedrawsTheFullScreen() = runTest {
		TestTerminal.bind().use { testTerminal ->
			testTerminal.resize(columns = 20, rows = 5, width = 0, height = 0)

			val tty = async(Dispatchers.IO) {
				readAll { buffer, offset, count ->
					testTerminal.readTty(buffer, offset, count)
				}
			}
			// Shrink and grow back while the app runs, then end it with Ctrl+C.
			val driver = async(Dispatchers.IO) {
				delay(500.milliseconds)
				testTerminal.resize(columns = 19, rows = 5, width = 0, height = 0)
				delay(200.milliseconds)
				testTerminal.resize(columns = 20, rows = 5, width = 0, height = 0)
				delay(500.milliseconds)
				testTerminal.writeTty(byteArrayOf(3), 0, 1)
			}

			withTty(testTerminal.tty, alternateScreen = true) { terminal, output ->
				runMosaic(terminal, output, RenderMode.FullScreen) {
					Text("Steady")
				}
			}
			driver.await()

			testTerminal.interruptTtyRead()
			val output = tty.await()
			// The first frame and at least one full redraw after the resizes: the terminal reflowed
			// what it showed even though the size ended up the same.
			assertThat(output.occurrencesOf("\u001B[2J")).isGreaterThanOrEqualTo(2)
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
