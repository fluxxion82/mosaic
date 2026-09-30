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
	renderMode: RenderMode = RenderMode.Inline,
	content: @Composable () -> Unit,
): Boolean {
	return runBlocking {
		runMosaic(onNonInteractive, renderMode, content)
	}
}

@Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
public fun runMosaicBlocking(
	onNonInteractive: NonInteractivePolicy = Exit,
	content: @Composable () -> Unit,
): Boolean = runMosaicBlocking(onNonInteractive, RenderMode.Inline, content)

public suspend fun runMosaic(
	onNonInteractive: NonInteractivePolicy = Exit,
	renderMode: RenderMode = RenderMode.Inline,
	content: @Composable () -> Unit,
): Boolean = withTerminal(onNonInteractive, renderMode == RenderMode.FullScreen) { terminal, output ->
	runMosaic(terminal, output, renderMode, content)
}

@Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
public suspend fun runMosaic(
	onNonInteractive: NonInteractivePolicy = Exit,
	content: @Composable () -> Unit,
): Boolean = runMosaic(onNonInteractive, RenderMode.Inline, content)

internal suspend fun runMosaic(
	terminal: Terminal,
	output: (String) -> Unit,
	renderMode: RenderMode,
	content: @Composable () -> Unit,
) {
	val repaint = Repaint()
	val rendering = when {
		env("MOSAIC_DEBUG_RENDERING") == "true" -> DebugRendering(terminal.capabilities)

		renderMode == RenderMode.FullScreen -> {
			FullScreenRendering(
				capabilities = terminal.capabilities,
				terminalSize = { terminal.state.size.value },
				resizeGeneration = { terminal.state.resizes.value },
				repaintGeneration = { repaint.requests.value },
			)
		}

		else -> AnsiRendering(terminal.capabilities)
	}

	runMosaicComposition(terminal, rendering, output, renderMode, repaint, content)
}
