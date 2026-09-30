package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import com.jakewharton.mosaic.tty.TestTerminal
import com.jakewharton.mosaic.tty.mosaic_test_install_sigusr1_handler_without_restart
import com.jakewharton.mosaic.tty.mosaic_test_reset_sigusr1_handler
import com.jakewharton.mosaic.tty.mosaic_test_write_eintr_retries
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import platform.posix.SIGUSR1
import platform.posix.pthread_self

/**
 * The TTY's own signal handlers use SA_RESTART, so a blocked write is restarted by the kernel and
 * never observes EINTR. Any other handler in the process may omit that flag, so the write loop
 * retries on EINTR as well. This installs such a handler to prove the loop.
 */
@OptIn(ExperimentalAtomicApi::class, ObsoleteWorkersApi::class)
class TtyWriteEintrTest {
	@Test fun writeRetriesAfterEintr() {
		assertThat(mosaic_test_install_sigusr1_handler_without_restart()).isEqualTo(0U)
		try {
			TestTerminal.bind().use { testTerminal ->
				// Larger than the PTY buffer, so the writer blocks until we read.
				val state = Blocked(testTerminal.tty, ByteArray(256 * 1024) { 'x'.code.toByte() })
				val worker = Worker.start()
				try {
					val future = worker.execute(TransferMode.SAFE, { state }) { state ->
						state.thread.store(pthread_self())
						try {
							var offset = 0
							while (offset < state.data.size) {
								// Single bytes, so the blocked call has written nothing when the signal arrives.
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
					val retriesBefore = mosaic_test_write_eintr_retries()
					signalThread(state, SIGUSR1)

					val total = readAll(testTerminal, state.data.size, state.failure)

					await("writer completion") { state.done.load() != 0 }
					future.result

					assertThat(state.failure.load()).isNull()
					assertThat(total).isEqualTo(state.data.size)
					// The signal really interrupted the blocked write, and the loop really retried it.
					assertThat(mosaic_test_write_eintr_retries()).isGreaterThan(retriesBefore)
				} finally {
					worker.finish(testTerminal, state)
				}
			}
		} finally {
			assertThat(mosaic_test_reset_sigusr1_handler()).isEqualTo(0U)
		}
	}
}
