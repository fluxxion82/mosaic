package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isIn
import assertk.assertions.isTrue
import com.jakewharton.mosaic.tty.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TtyWriterTest {
	@Test fun shutdownWaitsForWriteInFlightAndDropsLaterWrites() = runBlocking {
		withTimeout(30.seconds) {
			val output = StringBuilder()
			val firstByteWritten = CompletableDeferred<Unit>()
			val resumeFrame = CompletableDeferred<Unit>()
			try {
				val writer = TtyWriter { buffer, offset, _ ->
					output.append(buffer[offset].toInt().toChar())
					if (output.length == 1) {
						// Pause between two short writes so that shutdown is requested mid-frame.
						firstByteWritten.complete(Unit)
						runBlocking { resumeFrame.await() }
					}
					1 // Short write.
				}

				val frame = async(Dispatchers.IO) { writer.write("frame".encodeToByteArray()) }
				firstByteWritten.await()

				var emergency: Boolean? = null
				val shutdown = async(Dispatchers.IO) {
					writer.shutdown {
						output.append("|restore")
						emergency = it
					}
				}
				awaitCondition("shutdown requested") { writer.isClosing }

				// Dropped immediately, without waiting behind the paused frame.
				val later = async(Dispatchers.IO) { writer.write("later".encodeToByteArray()) }
				assertThat(later.await()).isFalse()
				assertThat(output.toString()).isEqualTo("f") // The restore has not jumped the queue.

				resumeFrame.complete(Unit)
				assertThat(frame.await()).isTrue()
				shutdown.await()
				assertThat(output.toString()).isEqualTo("frame|restore")
				assertThat(emergency).isEqualTo(false)

				assertThat(writer.write("after".encodeToByteArray())).isFalse()
				assertThat(output.toString()).isEqualTo("frame|restore")
			} finally {
				// Release the paused write before this scope waits for it, so a failure fails, not hangs.
				resumeFrame.complete(Unit)
			}
		}
	}

	@Test fun concurrentWritesDoNotInterleave() = runBlocking {
		withTimeout(30.seconds) {
			val output = StringBuilder()
			val writer = TtyWriter { buffer, offset, _ ->
				output.append(buffer[offset].toInt().toChar())
				runBlocking { delay(1.milliseconds) } // Give another writer the chance to interleave.
				1 // Short write.
			}

			val a = async(Dispatchers.IO) { writer.write("aaaa".encodeToByteArray()) }
			val b = async(Dispatchers.IO) { writer.write("bbbb".encodeToByteArray()) }
			assertThat(a.await()).isTrue()
			assertThat(b.await()).isTrue()
			assertThat(output.toString()).isIn("aaaabbbb", "bbbbaaaa")
		}
	}

	@Test fun shutdownGivesUpWaitingForAStuckWrite() = runBlocking {
		withTimeout(30.seconds) {
			val output = StringBuilder()
			val firstByteWritten = CompletableDeferred<Unit>()
			val releaseWrite = CompletableDeferred<Unit>()
			try {
				val writer = TtyWriter(shutdownTimeout = 200.milliseconds) { buffer, offset, _ ->
					output.append(buffer[offset].toInt().toChar())
					if (output.length == 1) {
						// Stuck, as when the console is stopped by flow control or the PTY is dead.
						firstByteWritten.complete(Unit)
						runBlocking { releaseWrite.await() }
					}
					1
				}

				val frame = async(Dispatchers.IO) { writer.write("frame".encodeToByteArray()) }
				firstByteWritten.await()

				// Restores anyway after the timeout, over the stuck frame, rather than never.
				var emergency: Boolean? = null
				val shutdown = async(Dispatchers.IO) {
					writer.shutdown {
						output.append("|restore")
						emergency = it
					}
				}
				shutdown.await()
				assertThat(output.toString()).isEqualTo("f|restore")
				assertThat(emergency).isEqualTo(true)

				// Once the TTY drains, the abandoned frame must not resume after the restore.
				releaseWrite.complete(Unit)
				assertThat(frame.await()).isFalse()
				assertThat(output.toString()).isEqualTo("f|restore")
			} finally {
				releaseWrite.complete(Unit)
			}
		}
	}

	@Test fun onWrittenRunsOnlyForAWriteWhichWentOutCompletely() = runBlocking {
		withTimeout(30.seconds) {
			val output = StringBuilder()
			val writer = TtyWriter { buffer, offset, _ ->
				output.append(buffer[offset].toInt().toChar())
				1
			}
			var written = 0
			assertThat(writer.write("ab".encodeToByteArray()) { written++ }).isTrue()
			assertThat(written).isEqualTo(1)

			writer.shutdown { output.append("|restore") }
			assertThat(writer.write("cd".encodeToByteArray()) { written++ }).isFalse()
			assertThat(written).isEqualTo(1) // Dropped, so the bookkeeping must not claim it went out.
			assertThat(output.toString()).isEqualTo("ab|restore")
		}
	}

	@Test fun onWrittenDoesNotRunForAnAbandonedWrite() = runBlocking {
		withTimeout(30.seconds) {
			val output = StringBuilder()
			val firstByteWritten = CompletableDeferred<Unit>()
			val releaseWrite = CompletableDeferred<Unit>()
			try {
				val writer = TtyWriter(shutdownTimeout = 200.milliseconds) { buffer, offset, _ ->
					output.append(buffer[offset].toInt().toChar())
					if (output.length == 1) {
						firstByteWritten.complete(Unit)
						runBlocking { releaseWrite.await() }
					}
					1
				}
				var written = 0
				val frame = async(Dispatchers.IO) { writer.write("frame".encodeToByteArray()) { written++ } }
				firstByteWritten.await()
				async(Dispatchers.IO) { writer.shutdown { output.append("|restore") } }.await()
				releaseWrite.complete(Unit)
				assertThat(frame.await()).isFalse()
				assertThat(written).isEqualTo(0)
			} finally {
				releaseWrite.complete(Unit)
			}
		}
	}

	@Test fun onWrittenDoesNotRunWhenTheLastChunkGoesOutAfterAbandonment() = runBlocking {
		withTimeout(30.seconds) {
			val output = StringBuilder()
			val entered = CompletableDeferred<Unit>()
			val releaseWrite = CompletableDeferred<Unit>()
			try {
				// Accepts the whole buffer in one call, but only returns once released.
				val writer = TtyWriter(shutdownTimeout = 200.milliseconds) { buffer, offset, count ->
					output.append(buffer.decodeToString(offset, offset + count))
					entered.complete(Unit)
					runBlocking { releaseWrite.await() }
					count
				}
				var written = 0
				val frame = async(Dispatchers.IO) { writer.write("switch".encodeToByteArray()) { written++ } }
				entered.await()
				async(Dispatchers.IO) { writer.shutdown { output.append("|restore") } }.await()
				releaseWrite.complete(Unit)
				// The bytes did go out, but only after the restore: too late for the bookkeeping.
				assertThat(frame.await()).isFalse()
				assertThat(written).isEqualTo(0)
				assertThat(writer.hadIncompleteWrite).isTrue()
			} finally {
				releaseWrite.complete(Unit)
			}
		}
	}

	@Test fun aFailingWriteIsRecordedAsIncomplete() {
		val writer = TtyWriter { _, _, _ -> throw IOException("EIO") }
		assertThat(writer.hadIncompleteWrite).isFalse()
		assertFailsWith<IOException> { writer.write("frame".encodeToByteArray()) }
		assertThat(writer.hadIncompleteWrite).isTrue()
	}

	@Test fun shutdownFromWithinAWriteDoesNotDeadlock() {
		val output = StringBuilder()
		lateinit var writer: TtyWriter
		writer = TtyWriter { buffer, offset, _ ->
			output.append(buffer[offset].toInt().toChar())
			if (output.length == 1) {
				writer.shutdown { output.append("|restore") }
			}
			1
		}

		assertThat(writer.write("ab".encodeToByteArray())).isTrue()
		assertThat(output.toString()).isEqualTo("a|restoreb")
	}
}
