package com.jakewharton.mosaic.tty.terminal

import platform.windows.GetCurrentThreadId

actual fun currentThreadId(): Any = GetCurrentThreadId()
