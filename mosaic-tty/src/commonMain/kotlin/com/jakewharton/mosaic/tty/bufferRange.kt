package com.jakewharton.mosaic.tty

internal fun ByteArray.checkRange(offset: Int, count: Int) {
	if (offset < 0 || count < 0 || offset > size || count > size - offset) {
		throw IndexOutOfBoundsException("offset=$offset, count=$count, size=$size")
	}
}
