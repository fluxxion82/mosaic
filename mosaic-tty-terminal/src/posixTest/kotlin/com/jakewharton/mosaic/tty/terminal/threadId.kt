package com.jakewharton.mosaic.tty.terminal

import platform.posix.pthread_self

@Suppress("USELESS_CAST") // Its type is a pointer on macOS and an integer on Linux.
actual fun currentThreadId(): Any = pthread_self() as Any
