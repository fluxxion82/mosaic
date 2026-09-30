package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.jakewharton.mosaic.tty.TestTerminal
import com.jakewharton.mosaic.tty.Tty
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Resize signals must not invoke the callback from signal context. They only wake a pending
 * read, which then invokes the callback on its own thread.
 */
@OptIn(ExperimentalAtomicApi::class)
class TtyResizeDispatchTest {
	@Test fun resizeCallbackRunsOnReadingThreadAndCoalesces() = runBlocking {
		if (isWindows()) return@runBlocking

		TestTerminal.bind().use { testTerminal ->
			withTimeout(30.seconds) {
				try {
					resizeCallbackRunsOnReadingThread(testTerminal)
				} finally {
					// Wake the reader before this scope waits for it, so a failure fails, not hangs.
					testTerminal.tty.interruptRead()
				}
			}
		}
	}

	private suspend fun CoroutineScope.resizeCallbackRunsOnReadingThread(testTerminal: TestTerminal) {
		val tty = testTerminal.tty
		tty.enableRawMode()
		tty.enableWindowResizeEvents()

		val callbacks = AtomicInt(0)
		val callbackThread = AtomicReference<Any?>(null)
		val callbackSize = AtomicReference<Pair<Int, Int>?>(null)
		tty.setCallback(object : Tty.Callback {
			override fun onFocus(focused: Boolean) = Unit
			override fun onKey() = Unit
			override fun onMouse() = Unit
			override fun onResize(columns: Int, rows: Int, width: Int, height: Int) {
				callbackThread.store(currentThreadId())
				callbackSize.store(columns to rows)
				callbacks.incrementAndFetch()
			}
		})

		// Two resizes while nothing is reading: nothing may run from the signal handler.
		testTerminal.resize(81, 24, 0, 0)
		testTerminal.resize(82, 25, 0, 0)
		assertThat(callbacks.load()).isEqualTo(0)

		val readerThread = CompletableDeferred<Any>()
		val read = async(Dispatchers.IO) {
			readerThread.complete(currentThreadId())
			tty.read(ByteArray(1), 0, 1)
		}

		// The queued notifications coalesce into a single callback carrying the final size.
		awaitCondition("first resize callback") { callbacks.load() == 1 }
		assertThat(callbackSize.load()).isEqualTo(82 to 25)
		assertThat(callbackThread.load()).isEqualTo(readerThread.await())
		assertThat(read.isActive).isTrue()

		// A resize while the read is blocked is delivered promptly, again by the reader.
		testTerminal.resize(100, 50, 0, 0)
		awaitCondition("second resize callback") { callbacks.load() == 2 }
		assertThat(callbackSize.load()).isEqualTo(100 to 50)
		assertThat(callbackThread.load()).isEqualTo(readerThread.await())

		testTerminal.write("x")
		assertThat(read.await()).isEqualTo(1)
		assertThat(callbacks.load()).isEqualTo(2)
	}
}
