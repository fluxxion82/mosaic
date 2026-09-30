package com.jakewharton.mosaic.tty.terminal

import com.jakewharton.mosaic.tty.Tty
import kotlinx.coroutines.CoroutineScope

internal expect suspend fun <R> Tty.withTerminalFinalizationHook(
	hook: () -> Unit,
	block: suspend CoroutineScope.() -> R,
): R
