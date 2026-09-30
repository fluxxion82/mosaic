package com.jakewharton.mosaic.tty.terminal

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Serializes complete writes to a TTY so frames and control sequences never interleave, and orders
 * shutdown after any write which is already in flight.
 *
 * Every call runs on the caller's thread and blocks only while another thread's write completes.
 * The lock is reentrant, so a shutdown initiated by a thread which is itself inside a write still
 * proceeds instead of deadlocking. Nothing here may be called from a signal handler: the TTY turns
 * signals into ordinary calls on its reading thread first.
 */
internal val DefaultShutdownTimeout = 5.seconds

internal class TtyWriter(
	/** How long [shutdown] waits for a write in flight before restoring the TTY over it. */
	private val shutdownTimeout: Duration = DefaultShutdownTimeout,
	private val write: (buffer: ByteArray, offset: Int, count: Int) -> Int,
) {
	private val lock = TtyLock()

	@Volatile
	private var closing = false

	/** Set once [shutdown] stopped waiting for a write in flight; that write must not continue. */
	@Volatile
	private var abandoned = false

	/** Whether [shutdown] has been requested, after which [write] drops everything. */
	val isClosing: Boolean get() = closing

	/**
	 * Whether some write did not go out completely: it failed, or [shutdown] abandoned it. The TTY
	 * may then be in the middle of whatever that write was doing, such as synchronized output.
	 */
	@Volatile
	var hadIncompleteWrite = false
		private set

	/**
	 * Write all of [buffer] as one transaction. Returns false, without waiting for the lock, once
	 * [shutdown] has been requested: the TTY is being restored and nothing may follow that.
	 *
	 * [onWritten] runs once the whole buffer went out, still inside the transaction, so bookkeeping
	 * about what the TTY has received cannot be overtaken by a [shutdown]: either both the write and
	 * the bookkeeping happened before the shutdown, or neither did.
	 */
	fun write(buffer: ByteArray, onWritten: () -> Unit = {}): Boolean {
		if (closing) return false
		lock.withLock {
			if (closing) return false
			var written = 0
			try {
				while (written < buffer.size) {
					// Once shutdown restored the TTY over this write, the rest of it must not follow.
					if (abandoned) {
						hadIncompleteWrite = true
						return false
					}
					val result = write(buffer, written, buffer.size - written)
					check(result > 0) { "TTY write made no progress (returned $result)" }
					written += result
				}
			} catch (t: Throwable) {
				hadIncompleteWrite = true
				throw t
			}
			// The last chunk may have gone out only after shutdown gave up waiting and restored the
			// TTY: then the bookkeeping must not run late, as if the write had preceded the restore.
			if (abandoned) {
				hadIncompleteWrite = true
				return false
			}
			onWritten()
		}
		return true
	}

	/**
	 * Stop accepting writes, wait for a write in flight to complete, and then run [block] while
	 * still holding the lock. The block writes the restore sequences directly and resets the TTY.
	 *
	 * A write which does not complete within [shutdownTimeout] (the TTY stopped by flow control, a
	 * dead PTY) is not waited for any longer: restoring the TTY late beats never restoring it. The
	 * block is then told it is an `emergency`, so it must not block on that TTY either.
	 */
	fun shutdown(block: (emergency: Boolean) -> Unit) {
		closing = true
		if (lock.tryLock(shutdownTimeout)) {
			try {
				block(false)
			} finally {
				lock.unlock()
			}
		} else {
			abandoned = true
			block(true)
		}
	}
}
