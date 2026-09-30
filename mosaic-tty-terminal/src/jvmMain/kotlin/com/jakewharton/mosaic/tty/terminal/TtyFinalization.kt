package com.jakewharton.mosaic.tty.terminal

import com.jakewharton.finalization.withFinalizationHook
import com.jakewharton.mosaic.tty.Tty
import kotlinx.coroutines.CoroutineScope

internal actual suspend fun <R> Tty.withTerminalFinalizationHook(
	hook: () -> Unit,
	block: suspend CoroutineScope.() -> R,
): R = withFinalizationHook(hook, block)
