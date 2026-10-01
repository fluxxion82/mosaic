package com.jakewharton.mosaic.tty

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import platform.posix.SIGWINCH
import platform.posix.pthread_kill
import platform.posix.pthread_self
import platform.posix.usleep

@OptIn(ObsoleteWorkersApi::class)
class TtySignalTest {
	private val testTerminal = TestTerminal.bind()
	private val tty = testTerminal.tty
	private val worker = Worker.start()

	@BeforeTest fun before() {
		tty.enableRawMode()
		tty.enableWindowResizeEvents()
	}

	@AfterTest fun after() {
		worker.requestTermination().result
		testTerminal.close()
	}

	@Test fun readInterruptedByResize() {
		readInterruptedByResize { buffer -> tty.read(buffer, 0, buffer.size) }
	}

	@Test fun readWithTimeoutInterruptedByResize() {
		readInterruptedByResize { buffer -> tty.readWithTimeout(buffer, 0, buffer.size, 500) }
	}

	private fun readInterruptedByResize(read: (ByteArray) -> Int) {
		val thread = pthread_self()
		worker.execute(TransferMode.SAFE, { thread to testTerminal }) { (thread, testTerminal) ->
			usleep(100_000u)
			pthread_kill(thread, SIGWINCH)
			usleep(100_000u)
			testTerminal.writeTty(byteArrayOf(1), 0, 1)
		}

		assertThat(read(ByteArray(10))).isEqualTo(1)
	}

	@Test fun writeInterruptedByResize() {
		// Larger than the PTY buffer so that writing blocks until the other end reads.
		val data = ByteArray(256 * 1024)

		val thread = pthread_self()
		val drained = worker.execute(TransferMode.SAFE, { Triple(thread, testTerminal, data.size) }) { (thread, testTerminal, size) ->
			usleep(100_000u)
			pthread_kill(thread, SIGWINCH)
			usleep(100_000u)

			val buffer = ByteArray(size)
			var total = 0
			while (total < size) {
				val read = testTerminal.readTtyWithTimeout(buffer, total, size - total, 500)
				if (read == 0) break
				total += read
			}
			total
		}

		var offset = 0
		while (offset < data.size) {
			// One byte at a time so the write which is interrupted has not written anything yet.
			offset += tty.write(data, offset, 1)
		}
		assertThat(drained.result).isEqualTo(data.size)
	}
}
