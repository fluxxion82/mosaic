package com.jakewharton.mosaic.tty.terminal

import com.jakewharton.mosaic.tty.IOException
import com.jakewharton.mosaic.tty.Tty

/**
 * Write [count] bytes from [buffer] at [offset] to this [Tty], calling [Tty.write] until every
 * byte has been written. This is not serialized against other writers; use [TtyWriter] for that.
 *
 * @throws IOException if [Tty.write] fails.
 * @throws IndexOutOfBoundsException before anything is written if [offset] and [count] do not
 * identify a range in [buffer].
 * @throws IllegalStateException if a write makes no progress.
 */
internal fun Tty.writeFully(
	buffer: ByteArray,
	offset: Int = 0,
	count: Int = buffer.size - offset,
) {
	if (offset < 0 || count < 0 || offset > buffer.size || count > buffer.size - offset) {
		throw IndexOutOfBoundsException("offset=$offset, count=$count, size=${buffer.size}")
	}
	writeFully(buffer, offset, count, ::write)
}

internal inline fun writeFully(
	buffer: ByteArray,
	offset: Int,
	count: Int,
	write: (buffer: ByteArray, offset: Int, count: Int) -> Int,
) {
	var written = 0
	while (written < count) {
		val result = write(buffer, offset + written, count - written)
		check(result > 0) { "TTY write made no progress (returned $result)" }
		written += result
	}
}
