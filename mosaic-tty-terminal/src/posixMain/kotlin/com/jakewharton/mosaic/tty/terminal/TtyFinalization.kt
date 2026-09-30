package com.jakewharton.mosaic.tty.terminal

import com.jakewharton.mosaic.tty.Tty
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import platform.posix.SIG_DFL
import platform.posix.SIG_ERR
import platform.posix.raise
import platform.posix.signal

/**
 * Run [block] and then [hook], also when a shutdown signal arrives, after which the signal is
 * redelivered to terminate the process the way it would have.
 *
 * Unlike a signal handler which runs the hook itself, the TTY's handlers merely interrupt the
 * reading thread, which cancels [block]. The hook therefore always runs in ordinary context where
 * it may write, take locks, and wait for a frame in flight.
 */
internal actual suspend fun <R> Tty.withTerminalFinalizationHook(
	hook: () -> Unit,
	block: suspend CoroutineScope.() -> R,
): R {
	enableShutdownSignalInterrupt()
	return try {
		coroutineScope(block)
	} finally {
		try {
			hook()
		} finally {
			val signum = shutdownSignal()
			if (signum != 0) {
				// The hook reset the TTY. Redeliver to this process only (not the process group, which
				// would also kill a parent shell) with the default disposition; raise() does not return
				// before a terminating signal has been delivered to the calling thread.
				if (signal(signum, SIG_DFL) != SIG_ERR) {
					raise(signum)
				}
				// Unable to redeliver, or the signal did not terminate us: exit instead.
				exitProcess(1)
			}
		}
	}
}
