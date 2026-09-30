package com.jakewharton.mosaic.tty.terminal

expect fun isWindows(): Boolean

/** An identity for the calling thread which is equal for calls from the same thread. */
expect fun currentThreadId(): Any
