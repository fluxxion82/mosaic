package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import com.jakewharton.mosaic.tty.TestTerminal
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class TtyTerminalShutdownTest {
	@Test fun closeWaitsForFrameInFlightBeforeRestoring() = runBlocking {
		if (isWindows()) return@runBlocking

		TestTerminal.bind().use { testTerminal ->
			val drain = PtyDrain(testTerminal)
			// The frame below blocks until drained. If an assertion fails or a cancellation arrives
			// before the drain is started on purpose, the scope would wait for the blocked writer
			// forever, so schedule a rescue drain now, from a scope of its own.
			drain.startAfter(10.seconds)
			try {
				withTimeout(30.seconds) {
					// Nobody answers the capability queries, so bootstrap times out and the cursor is
					// hidden unconditionally. Its restore sequence is then the last thing written on close.
					testTerminal.tty.withTerminalIn { terminal, output ->
						// Larger than the PTY buffer, so the frame blocks until it is drained.
						val frame = "x".repeat(256 * 1024)
						val write = async(Dispatchers.IO) { output(frame) }
						delay(200.milliseconds)
						assertThat(write.isActive).isTrue()

						val close = async(Dispatchers.IO) { terminal.close() }
						delay(200.milliseconds)
						assertThat(close.isActive).isTrue() // Waiting for the frame, not restoring over it.

						drain.start()
						write.await()
						close.await()
					}
				}
			} finally {
				drain.stop()
			}

			val output = drain.drained
			val frameStart = output.indexOf('x')
			val frameEnd = output.lastIndexOf('x') + 1
			assertThat(output.substring(frameStart, frameEnd)).isEqualTo("x".repeat(256 * 1024))
			assertThat(output.substring(frameEnd)).isEqualTo(cursorEnable)
		}
	}

	@Test fun withTerminalInReturnsOnlyAfterItsCoroutinesFinished() = runBlocking {
		TestTerminal.bind().use { testTerminal ->
			val drain = PtyDrain(testTerminal)
			drain.start()
			val readerPaused = CompletableDeferred<Unit>()
			val resumeReader = CompletableDeferred<Unit>()
			// Resumes the reader once the restore was drained, or after a deadline regardless, from a
			// scope of its own: a held reader must be released even when this test fails, or nothing
			// which waits for it could ever finish.
			val rescue = CoroutineScope(Dispatchers.Default + SupervisorJob())
			rescue.launch {
				withTimeoutOrNull(10.seconds) {
					while (!drain.drained.contains(cursorEnable)) delay(5.milliseconds)
				}
				resumeReader.complete(Unit)
			}
			try {
				withTimeout(30.seconds) {
					// Hold the reader between its second and third read, i.e. while the terminal closes.
					var reads = 0
					val beforeRead = {
						if (++reads == 2) {
							readerPaused.complete(Unit)
							runBlocking { resumeReader.await() }
						}
					}
					testTerminal.tty.withTerminalIn(DefaultShutdownTimeout, false, beforeRead) { _, _ ->
						testTerminal.write("x") // One event, after which the reader pauses before reading on.
						readerPaused.await()
					}
					// The caller may close the TTY right after this returns, so the reader (which was held
					// back until the restore) and the finalizer must both have finished by now.
					assertThat(coroutineContext.job.children.toList()).isEmpty()
				}
			} finally {
				resumeReader.complete(Unit)
				rescue.cancel()
				drain.stop()
			}
		}
	}

	@Test fun emergencyRestoreIsBoundedOnAStuckPty() = runBlocking {
		if (isWindows()) return@runBlocking

		TestTerminal.bind().use { testTerminal ->
			val drain = PtyDrain(testTerminal)
			// Nobody drains, so a frame larger than the PTY buffer sticks like output on a console
			// stopped with Ctrl+S. Should the emergency path block on it anyway, this rescue unblocks
			// it late enough for the timing assertion to fail instead of the test hanging.
			drain.startAfter(15.seconds)
			try {
				withTimeout(60.seconds) {
					var frame: Deferred<Unit>? = null
					var closing: TimeMark? = null
					testTerminal.tty.withTerminalIn(300.milliseconds, false) { _, output ->
						frame = async(Dispatchers.IO) { output("x".repeat(256 * 1024)) }
						delay(200.milliseconds)
						assertThat(frame!!.isActive).isTrue()
						closing = TimeSource.Monotonic.markNow()
					}
					// Waited 300 ms for the frame, then restored over it without waiting for the PTY.
					assertThat(closing!!.elapsedNow()).isLessThan(5.seconds)
					assertThat(frame!!.isActive).isTrue() // Still stuck: nothing drained the PTY.

					// The terminal settings were restored regardless: input is echoed again, which raw
					// mode had disabled. The echo queues behind the frame, so drain first.
					drain.start()
					frame!!.await()
					testTerminal.write("z")
					awaitCondition("echo of typed input") { drain.drained.contains('z') }
				}
			} finally {
				drain.stop()
			}
		}
	}

	@Test fun closeDuringTheEscapeDisambiguationReadStopsTheReader() = runBlocking {
		if (isWindows()) return@runBlocking

		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			val drain = PtyDrain(testTerminal)
			drain.start()
			val aboutToReadAgain = CompletableDeferred<Unit>()
			val proceed = CompletableDeferred<Unit>()
			val rescue = CoroutineScope(Dispatchers.Default + SupervisorJob())
			// Let the parser continue into its timed read once the close has been initiated (its
			// restore was drained), or after a deadline regardless.
			rescue.launch {
				withTimeoutOrNull(10.seconds) {
					while (!drain.drained.contains(cursorEnable)) delay(5.milliseconds)
				}
				proceed.complete(Unit)
			}
			// A reader which wrongly read on after the close would block forever; free it late enough
			// for the timing assertion to fail instead of the test hanging.
			rescue.launch {
				delay(5.seconds)
				tty.interruptRead()
			}
			try {
				withTimeout(30.seconds) {
					var reads = 0
					val beforeRead = {
						// The second read is the short timed read which follows a bare Escape.
						if (++reads == 2) {
							aboutToReadAgain.complete(Unit)
							runBlocking { proceed.await() }
						}
					}
					var closing: TimeMark? = null
					tty.withTerminalIn(DefaultShutdownTimeout, false, beforeRead) { _, _ ->
						testTerminal.write("\u001b")
						aboutToReadAgain.await()
						closing = TimeSource.Monotonic.markNow()
					}
					// The close's interrupt is consumed by the timed read as a timeout. The reader must
					// still stop instead of blocking in another read while this waits for it.
					assertThat(closing!!.elapsedNow()).isLessThan(3.seconds)
				}
			} finally {
				proceed.complete(Unit)
				rescue.cancel()
				drain.stop()
			}
		}
	}

	@Test fun terminalClosesWhenItsReaderIsInterrupted() = runBlocking {
		TestTerminal.bind().use { testTerminal ->
			val drain = PtyDrain(testTerminal)
			drain.start()
			try {
				withTimeout(30.seconds) {
					testTerminal.tty.asTerminalIn(this).use {
						// Nobody reads events any more, e.g. after an unexpected interrupt. The terminal
						// must not linger half-open: it restores the TTY (here: the hidden cursor).
						testTerminal.tty.interruptRead()
						awaitCondition("cursor restored") { drain.drained.contains(cursorEnable) }
					}
				}
			} finally {
				withContext(NonCancellable) {
					// Also wakes a reader which a failed wait left blocked, so the scope can end.
					testTerminal.tty.interruptRead()
				}
				drain.stop()
			}
		}
	}
}
