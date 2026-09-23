package com.jakewharton.mosaic.browser

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.Mosaic
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.w3c.dom.HTMLElement

/**
 * Run a Mosaic composition inside [element], which acts as the terminal. The element should use a
 * monospace font and `white-space: pre`. Its size (in character cells) is exposed to the
 * composition as the terminal size, and it receives keyboard input while focused.
 *
 * Any existing children of [element] (such as a no-JS fallback) are replaced.
 *
 * @param captureTab Deliver Tab to the composition instead of letting it move focus out of
 * [element]. Leave this off unless the page offers another way out, or keyboard users are trapped.
 * @param focus Focus the terminal (without scrolling) so it receives keys immediately.
 *
 * Cancel the returned [Job] to stop the composition and detach from the element.
 */
public fun renderMosaic(
	element: HTMLElement,
	captureTab: Boolean = false,
	focus: Boolean = true,
	content: @Composable () -> Unit,
): Job {
	element.textContent = ""
	val terminal = BrowserTerminal(element, captureTab)
	val renderer = DomRenderer(element)
	val job = Job()
	val errors = CoroutineExceptionHandler { _, throwable ->
		consoleError("Mosaic composition failed: ${throwable.stackTraceToString()}")
	}
	val scope = CoroutineScope(job + RafFrameClock + errors)

	val mosaic = Mosaic(
		coroutineContext = scope.coroutineContext,
		onDraw = { mosaic ->
			mosaic.static()?.let(renderer::appendStatic)
			renderer.render(mosaic.draw())
		},
		terminal = terminal,
	)
	mosaic.setContent(content)

	// Unlike a CLI, a page does not exit once its effects finish, so awaitComplete() is not used.
	// The composition lives until the returned job is cancelled.
	job.invokeOnCompletion {
		mosaic.cancel()
		terminal.close()
	}

	if (focus) {
		terminal.focus()
	}
	return job
}

private fun consoleError(message: String) {
	js("console.error(message)")
}
