package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.jakewharton.mosaic.tty.TestTerminal
import com.jakewharton.mosaic.tty.Tty
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import platform.posix.SIGWINCH
import platform.posix.pthread_self
import platform.posix.usleep

/**
 * On the Linux console a resize signal can arrive while the TTY is blocked in read or write.
 * Neither call may fail with EINTR, and a timed read must keep its original deadline.
 */
@OptIn(ExperimentalAtomicApi::class, ObsoleteWorkersApi::class)
class TtySignalInterruptionTest {
	@Test fun writeBlockedOnFullTty() {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			tty.enableRawMode()
			tty.enableWindowResizeEvents()

			// Larger than the PTY buffer, so the writer blocks until we read.
			val state = Blocked(tty, ByteArray(256 * 1024) { 'x'.code.toByte() })
			val worker = Worker.start()
			try {
				val future = worker.execute(TransferMode.SAFE, { state }) { state ->
					state.thread.store(pthread_self())
					try {
						var offset = 0
						while (offset < state.data.size) {
							// Single bytes, so the blocked call has written nothing when the signal arrives.
							// A write which already transferred some bytes reports a short count instead of EINTR.
							offset += state.tty.write(state.data, offset, 1)
							state.progress.store(offset)
						}
					} catch (t: Throwable) {
						state.failure.store(t)
					} finally {
						state.done.store(1)
					}
				}

				awaitNoProgress(state)
				assertThat(state.progress.load() < state.data.size).isEqualTo(true)
				signalThread(state, SIGWINCH)

				val total = readAll(testTerminal, state.data.size, state.failure)

				await("writer completion") { state.done.load() != 0 }
				future.result

				assertThat(state.failure.load()).isNull()
				assertThat(total).isEqualTo(state.data.size)
			} finally {
				worker.finish(testTerminal, state)
			}
		}
	}

	@Test fun readBlockedWithoutTimeout() {
		readBlocked { tty, buffer -> tty.read(buffer, 0, buffer.size) }
	}

	@Test fun readBlockedWithTimeout() {
		readBlocked { tty, buffer -> tty.readWithTimeout(buffer, 0, buffer.size, 999) }
	}

	@Test fun signalsDoNotExtendReadTimeout() {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			tty.enableRawMode()
			tty.enableWindowResizeEvents()

			val state = Blocked(tty, ByteArray(1))
			val worker = Worker.start()
			try {
				val future = worker.execute(TransferMode.SAFE, { state }) { state ->
					state.thread.store(pthread_self())
					try {
						state.progress.store(state.tty.readWithTimeout(state.data, 0, 1, 400))
					} catch (t: Throwable) {
						state.failure.store(t)
					} finally {
						state.done.store(1)
					}
				}

				await("reader thread") { state.thread.load() != null }
				val start = TimeSource.Monotonic.markNow()
				// Keep interrupting the wait for longer than the timeout. If each retry restarted the
				// full timeout, the read would only return once we stop.
				while (state.done.load() == 0) {
					check(start.elapsedNow() < 2.seconds) { "Timed read did not return while being signaled" }
					signalThread(state, SIGWINCH)
					usleep(50_000u)
				}
				val elapsed = start.elapsedNow()
				future.result

				assertThat(state.failure.load()).isNull()
				assertThat(state.progress.load()).isEqualTo(0)
				assertThat(elapsed).isBetween(350.milliseconds, 1.seconds)
			} finally {
				worker.finish(testTerminal, state)
			}
		}
	}

	private fun readBlocked(read: (Tty, ByteArray) -> Int) {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			tty.enableRawMode()
			tty.enableWindowResizeEvents()

			val state = Blocked(tty, ByteArray(16))
			val worker = Worker.start()
			try {
				val future = worker.execute(TransferMode.SAFE, { state to read }) { (state, read) ->
					state.thread.store(pthread_self())
					try {
						state.progress.store(read(state.tty, state.data))
					} catch (t: Throwable) {
						state.failure.store(t)
					} finally {
						state.done.store(1)
					}
				}

				await("reader thread") { state.thread.load() != null }
				signalThread(state, SIGWINCH)
				testTerminal.write("a")

				await("reader completion") { state.done.load() != 0 }
				future.result

				assertThat(state.failure.load()).isNull()
				assertThat(state.progress.load()).isEqualTo(1)
			} finally {
				worker.finish(testTerminal, state)
			}
		}
	}
}
