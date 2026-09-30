@file:JvmName("MosaicKt")
@file:JvmMultifileClass

package com.jakewharton.mosaic

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.NonInteractivePolicy.Exit
import com.jakewharton.mosaic.terminal.Terminal
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.runBlocking

public fun runMosaicMain(
	content: @Composable () -> Unit,
) {
	runMosaicBlocking(content = content)
}

public fun runMosaicBlocking(
	onNonInteractive: NonInteractivePolicy = Exit,
	content: @Composable () -> Unit,
): Boolean {
	return runBlocking {
		runMosaic(onNonInteractive, content)
	}
}

public suspend fun runMosaic(
	onNonInteractive: NonInteractivePolicy = Exit,
	content: @Composable () -> Unit,
): Boolean = withTerminal(onNonInteractive) { terminal, output ->
	runMosaic(terminal, output, content)
}

internal suspend fun runMosaic(
	terminal: Terminal,
	output: (String) -> Unit,
	content: @Composable () -> Unit,
) {
	val rendering = if (env("MOSAIC_DEBUG_RENDERING") == "true") {
		DebugRendering(terminal.capabilities)
	} else {
		AnsiRendering(terminal.capabilities)
	}

	runMosaicComposition(terminal, rendering, output, content)
}
