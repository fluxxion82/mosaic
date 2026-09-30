package com.jakewharton.mosaic.tty

/** Whether the native library is the Windows one, which lacks the POSIX-only entry points. */
internal val isWindowsHost: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)
