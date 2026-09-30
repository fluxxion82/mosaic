package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.jakewharton.mosaic.tty.TestTerminal
import com.jakewharton.mosaic.tty.mosaic_test_install_shutdown_handler
import com.jakewharton.mosaic.tty.mosaic_test_signal_disposition
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import platform.posix.SIGHUP
import platform.posix.SIGINT
import platform.posix.SIGQUIT
import platform.posix.SIGTERM
import platform.posix.SIGWINCH
import platform.posix.SIG_DFL
import platform.posix.SIG_IGN
import platform.posix.pthread_self
import platform.posix.raise
import platform.posix.signal

/**
 * Shutdown signals must not terminate the process or run anything in signal context. They
 * only interrupt the reading thread, which learns which signal arrived and handles it itself.
 */
@OptIn(ExperimentalAtomicApi::class, ObsoleteWorkersApi::class)
class TtyShutdownSignalTest {
	@Test fun sigtermInterruptsBlockedReadAndIsRecorded() = shutdownSignalInterruptsBlockedRead(SIGTERM)

	@Test fun sigintInterruptsBlockedReadAndIsRecorded() = shutdownSignalInterruptsBlockedRead(SIGINT)

	@Test fun sighupInterruptsBlockedReadAndIsRecorded() = shutdownSignalInterruptsBlockedRead(SIGHUP)

	@Test fun sigquitInterruptsBlockedReadAndIsRecorded() = shutdownSignalInterruptsBlockedRead(SIGQUIT)

	private fun shutdownSignalInterruptsBlockedRead(signum: Int) {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			tty.enableRawMode()
			tty.enableShutdownSignalInterrupt()
			assertThat(tty.shutdownSignal()).isEqualTo(0)

			val state = Blocked(tty, ByteArray(1))
			val worker = Worker.start()
			try {
				val future = worker.execute(TransferMode.SAFE, { state }) { state ->
					state.thread.store(pthread_self())
					try {
						state.progress.store(state.tty.read(state.data, 0, 1))
					} catch (t: Throwable) {
						state.failure.store(t)
					} finally {
						state.done.store(1)
					}
				}

				await("reader thread") { state.thread.load() != null }
				signalThread(state, signum)

				await("reader completion") { state.done.load() != 0 }
				future.result

				assertThat(state.failure.load()).isNull()
				assertThat(state.progress.load()).isEqualTo(0)
				assertThat(tty.shutdownSignal()).isEqualTo(signum)

				// The number survives reset so the terminal can redeliver the signal afterwards.
				tty.reset()
				assertThat(tty.shutdownSignal()).isEqualTo(signum)
			} finally {
				worker.finish(testTerminal, state)
			}
		}
	}

	@Test fun inheritedIgnoreIsKeptAndResetRestoresThePreviousDispositions() {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			try {
				// An inherited SIG_IGN (nohup, a background job) keeps being ignored and must survive
				// the reset, not be replaced by the default disposition.
				signal(SIGINT, SIG_IGN)
				signal(SIGWINCH, SIG_IGN)
				tty.enableShutdownSignalInterrupt()
				tty.enableWindowResizeEvents()
				assertThat(mosaic_test_signal_disposition(SIGINT)).isEqualTo(1)
				assertThat(mosaic_test_signal_disposition(SIGTERM)).isEqualTo(2)
				assertThat(mosaic_test_signal_disposition(SIGWINCH)).isEqualTo(2)

				tty.reset()
				assertThat(mosaic_test_signal_disposition(SIGINT)).isEqualTo(1)
				assertThat(mosaic_test_signal_disposition(SIGWINCH)).isEqualTo(1)
				assertThat(mosaic_test_signal_disposition(SIGTERM)).isEqualTo(0)
			} finally {
				signal(SIGINT, SIG_DFL)
				signal(SIGWINCH, SIG_DFL)
			}
		}
	}

	@Test fun repeatedShutdownSignalRestoresDefaultDispositionAndReRaises() {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			// SIGWINCH is ignored by default, so the re-raise with the default disposition is
			// survivable here, unlike with SIGTERM. The handler itself is the same.
			assertThat(mosaic_test_install_shutdown_handler(SIGWINCH)).isEqualTo(0U)
			try {
				assertThat(raise(SIGWINCH)).isEqualTo(0)
				assertThat(tty.shutdownSignal()).isEqualTo(SIGWINCH)
				assertThat(mosaic_test_signal_disposition(SIGWINCH)).isEqualTo(2)

				// While the first signal is still being handled, a second one must not be swallowed:
				// the handler restores the default disposition and re-raises.
				assertThat(raise(SIGWINCH)).isEqualTo(0)
				assertThat(mosaic_test_signal_disposition(SIGWINCH)).isEqualTo(0)
			} finally {
				signal(SIGWINCH, SIG_DFL)
			}
		}
	}
}
