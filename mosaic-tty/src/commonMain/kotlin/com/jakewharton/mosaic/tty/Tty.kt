package com.jakewharton.mosaic.tty

public expect class Tty : AutoCloseable {
	public companion object {
		/**
		 * Initialize a [Tty] instance to the interactive terminal for this application. Only a single
		 * [Tty] instance can be bound at a time. Subsequent calls will throw an exception until
		 * [Tty.close] is called. Returns `null` if no TTY is available for this process.
		 *
		 * Bind once per process and keep the instance for the process lifetime. On Linux and macOS the
		 * signal plumbing behind [enableWindowResizeEvents] and [enableShutdownSignalInterrupt] lives
		 * for the whole process: a signal handler which is still running across a [close] and a new
		 * bind may notify the new instance or nobody, and a `fork()` without `exec()` after binding
		 * leaves both processes sharing that plumbing.
		 *
		 * @throws IOException If an error occurred binding to the TTY.
		 * @throws IllegalStateException If another instance is already bound.
		 */
		public fun tryBind(): Tty?
	}

	/**
	 * Set or clear the callback used for reporting events about the terminal using platform-specific
	 * integration. The callback is only invoked during calls to [read] or [readWithTimeout], on the
	 * calling thread, and must never throw an exception. Does nothing once [close] was called.
	 */
	public fun setCallback(callback: Callback?)

	/**
	 * Read up to [count] bytes into [buffer] at [offset] from the TTY.
	 * The number of bytes read will be returned. 0 will be returned if [interruptRead] is called
	 * while waiting for data. -1 will be returned if the TTY is not interactive.
	 *
	 * @see readWithTimeout
	 * @see interruptRead
	 */
	public fun read(buffer: ByteArray, offset: Int, count: Int): Int

	/**
	 * Read up to [count] bytes into [buffer] at [offset] from the TTY.
	 * The number of bytes read will be returned. 0 will be returned if [interruptRead] is called
	 * while waiting for data, or if at least [timeoutMillis] have passed without data.
	 * -1 will be returned if the TTY is not interactive.
	 *
	 * @param timeoutMillis A value of 0 will perform a non-blocking read. Otherwise, valid values
	 * are positive and represent a maximum time (in milliseconds) to wait for data. Note: This
	 * value is not validated.
	 * @see read
	 * @see interruptRead
	 */
	public fun readWithTimeout(buffer: ByteArray, offset: Int, count: Int, timeoutMillis: Int): Int

	/** Signal blocking calls to [read] or [readWithTimeout] to wake up and return 0. */
	public fun interruptRead()

	/**
	 * Write up to [count] bytes from [buffer] at [offset] to the TTY.
	 * The number of bytes written will be returned.
	 */
	public fun write(buffer: ByteArray, offset: Int, count: Int): Int

	/**
	 * Like [write], but never blocks on the TTY: it writes whatever the TTY accepts within
	 * [timeoutMillis] and returns that count, which may be smaller than [count] or 0 when the
	 * output is stuck (for example stopped by flow control). One deadline covers the whole call,
	 * including partial writes. Meant for restoring a terminal which may be stuck. On Windows
	 * this behaves like [write].
	 */
	public fun writeWithTimeout(buffer: ByteArray, offset: Int, count: Int, timeoutMillis: Int): Int

	/**
	 * Save the current terminal settings and enter "raw" mode.
	 *
	 * Raw mode is described as "input is available character by character, echoing is disabled,
	 * and all special processing of terminal input and output characters is disabled."
	 *
	 * The saved settings can be restored by calling [close][AutoCloseable.close] on
	 * the returned instance.
	 *
	 * See [`termios(3)`](https://linux.die.net/man/3/termios) for more information.
	 *
	 * In addition to the flags required for entering "raw" mode, on POSIX-compliant platforms,
	 * this function will change the standard input stream to block indefinitely until a minimum
	 * of 1 byte is available to read. This allows the reader thread to fully be suspended rather
	 * than consuming CPU. Use [read] or [readWithTimeout] to read in a manner that can
	 * still be interrupted by [interruptRead].
	 */
	public fun enableRawMode()

	/**
	 * Use platform-specific window monitoring to call [Callback.onResize] when the OS determines
	 * the terminal window size has changed. You *must* call [setCallback] to monitor these events.
	 *
	 * Note: Before enabling this, consider querying the terminal for support of
	 * [mode 2048 in-band resize events](https://gist.github.com/rockorager/e695fb2924d36b2bcf1fff4a3704bd83)
	 * which are more reliable.
	 *
	 * On Windows this enables receiving
	 * [`WINDOW_BUFFER_SIZE_RECORD`](https://learn.microsoft.com/en-us/windows/console/window-buffer-size-record-str)
	 * records from the console. Only the row and column values of the event will be present.
	 * The width and height will always be 0.
	 *
	 * On Linux and macOS this installs a `SIGWINCH` signal handler which only wakes a pending [read]
	 * or [readWithTimeout]. That call then queries `TIOCGWINSZ` using `ioctl` and invokes the
	 * callback on its own thread. Signals which arrive while no read is pending are coalesced into a
	 * single callback at the start of the next read.
	 *
	 * Note: You can also respond to resize events which lack necessary data by sending `XTWINOPS`
	 * to query row/col counts and/or window or cell size in pixels. More details
	 * [here](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h4-Functions-using-CSI-_-ordered-by-the-final-character-lparen-s-rparen:CSI-Ps;Ps;Ps-t:Ps-=-1-4.2064).
	 */
	public fun enableWindowResizeEvents()

	/**
	 * On Linux and macOS, install handlers for `SIGINT`, `SIGTERM`, `SIGHUP` and `SIGQUIT` which record the signal and
	 * interrupt a pending [read] or [readWithTimeout] (which then returns 0) instead of terminating
	 * the process. Use [shutdownSignal] to distinguish such an interrupt from [interruptRead], and
	 * to redeliver the signal once the terminal has been restored. The previous dispositions are
	 * restored by [reset].
	 *
	 * A signal whose disposition is already `SIG_IGN` (for example `SIGHUP` under `nohup`) keeps being
	 * ignored. A core dumped by `SIGQUIT` reflects the state after the restore; a process which hangs
	 * during that restore needs a second `SIGQUIT`, which terminates it immediately.
	 *
	 * The handlers and their self-pipes are process-wide (see [tryBind]): do not `fork()` without
	 * `exec()` afterwards, and do not close this instance and bind another while a signal may still
	 * be in flight, or that signal may reach the wrong instance or be lost.
	 *
	 * Does nothing on Windows or on the JVM, where [shutdownSignal] always returns 0.
	 */
	public fun enableShutdownSignalInterrupt()

	/**
	 * The number of the last signal recorded by [enableShutdownSignalInterrupt], or 0 if none was
	 * received. The value survives [reset] so the signal can still be redelivered afterwards.
	 */
	public fun shutdownSignal(): Int

	/** @return Array of `[columns, rows, width, height]` */
	public fun currentSize(): IntArray

	/**
	 * Reset TTY state: restore the saved terminal settings, after waiting for pending output to
	 * drain, and then the previous signal dispositions.
	 */
	public fun reset()

	/**
	 * Like [reset], but applies the saved terminal settings without waiting for pending output,
	 * and discards unread input (raw-mode typeahead) which [reset] discards too. For a TTY whose
	 * output is stuck, where [reset] would never return. On Windows this behaves like [reset].
	 */
	public fun resetImmediately()

	/** Calls [reset] and then frees the resources associated with this instance. */
	override fun close()

	/** Platform-specific integration callbacks. */
	public interface Callback {
		/** Called when the window gains or loses focus. Only invoked on Windows. */
		public fun onFocus(focused: Boolean)

		/** Currently unused. */
		public fun onKey()

		/** Currently unused. */
		public fun onMouse()

		/** If [Tty.enableWindowResizeEvents] was invoked, this is called when the window is resized. */
		public fun onResize(columns: Int, rows: Int, width: Int, height: Int)
	}
}
