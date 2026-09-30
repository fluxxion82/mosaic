package com.jakewharton.mosaic.tty

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar

/** Returns an errno value, or 0 on success. Only POSIX targets have an implementation. */
internal expect fun ttyEnableShutdownSignalInterrupt(tty: CPointer<MosaicTty>?): UInt

internal expect fun ttyShutdownSignal(tty: CPointer<MosaicTty>?): Int

/** Returns the count written, or 0 on timeout. Windows falls back to a blocking write. */
internal expect fun ttyWriteWithTimeout(tty: CPointer<MosaicTty>?, buffer: CPointer<UByteVar>, count: Int, timeoutMillis: Int): Int

/** Windows falls back to the draining reset. */
internal expect fun ttyResetImmediately(tty: CPointer<MosaicTty>?): UInt
