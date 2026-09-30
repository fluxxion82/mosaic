package com.jakewharton.mosaic.tty

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.useContents

internal actual fun ttyEnableShutdownSignalInterrupt(tty: CPointer<MosaicTty>?): UInt {
	return mosaic_tty_enable_shutdown_signal_interrupt(tty)
}

internal actual fun ttyShutdownSignal(tty: CPointer<MosaicTty>?): Int {
	return mosaic_tty_shutdown_signal(tty)
}

internal actual fun ttyWriteWithTimeout(tty: CPointer<MosaicTty>?, buffer: CPointer<UByteVar>, count: Int, timeoutMillis: Int): Int {
	mosaic_tty_write_with_timeout(tty, buffer, count, timeoutMillis).useContents {
		if (error == 0U) return this.count
		throwIoe(error)
	}
}

internal actual fun ttyResetImmediately(tty: CPointer<MosaicTty>?): UInt {
	return mosaic_tty_reset_immediately(tty)
}
