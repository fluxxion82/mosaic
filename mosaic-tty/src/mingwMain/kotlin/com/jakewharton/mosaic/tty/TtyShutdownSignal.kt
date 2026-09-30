package com.jakewharton.mosaic.tty

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.useContents

// Windows has no SIGINT/SIGTERM delivery to hook into; console control events are not handled.
internal actual fun ttyEnableShutdownSignalInterrupt(tty: CPointer<MosaicTty>?): UInt = 0U

internal actual fun ttyShutdownSignal(tty: CPointer<MosaicTty>?): Int = 0

internal actual fun ttyWriteWithTimeout(tty: CPointer<MosaicTty>?, buffer: CPointer<UByteVar>, count: Int, timeoutMillis: Int): Int {
	mosaic_tty_write(tty, buffer, count).useContents {
		if (error == 0U) return this.count
		throwIoe(error)
	}
}

internal actual fun ttyResetImmediately(tty: CPointer<MosaicTty>?): UInt = mosaic_tty_reset(tty)
