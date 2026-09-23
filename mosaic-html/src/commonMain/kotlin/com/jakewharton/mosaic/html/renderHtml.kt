package com.jakewharton.mosaic.html

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.Mosaic
import com.jakewharton.mosaic.TextCanvas
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.Event
import com.jakewharton.mosaic.terminal.Terminal
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compose [content] once in a [columns] × [rows] terminal and return the frame as HTML, using the
 * same markup as the browser renderer: static output in `<div class="mosaic-static">` followed by
 * `<div class="mosaic-live">`, with one `<div class="mosaic-row">` per row.
 *
 * Effects do not run, so this captures the initial state. Use it to prerender a page so that its
 * text is present before (or without) JavaScript.
 */
public fun renderHtml(
	columns: Int,
	rows: Int,
	theme: Terminal.Theme = Terminal.Theme.Unknown,
	content: @Composable () -> Unit,
): String {
	val job = Job()
	var canvas: TextCanvas? = null
	var static: String? = null
	val mosaic = Mosaic(
		coroutineContext = job + BroadcastFrameClock(),
		onDraw = { mosaic ->
			mosaic.static()?.let { static = if (static == null) it else static + "\r\n" + it }
			canvas = mosaic.draw()
		},
		terminal = StaticTerminal(Terminal.Size(columns, rows), theme),
	)
	try {
		// Composition, layout, and the first draw happen synchronously.
		mosaic.setContent(content)
	} finally {
		mosaic.cancel()
		job.cancel()
	}

	return buildString {
		append("<div class=\"mosaic-static\">")
		static?.split("\r\n")?.forEach(::appendRow)
		append("</div><div class=\"mosaic-live\">")
		canvas?.let { appendRows(it) }
		append("</div>")
	}
}

/** Append each row of [canvas] as a `<div class="mosaic-row">`. */
public fun StringBuilder.appendRows(canvas: TextCanvas) {
	val ansi = StringBuilder()
	for (row in 0 until canvas.height) {
		ansi.clear()
		canvas.appendRowTo(ansi, row, AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
		appendRow(ansi)
	}
}

private fun StringBuilder.appendRow(ansi: CharSequence) {
	append("<div class=\"mosaic-row\">")
	// An empty row would collapse to zero height.
	append(ansiToHtml(ansi).ifEmpty { " " })
	append("</div>")
}

private class StaticTerminal(
	size: Terminal.Size,
	theme: Terminal.Theme,
) : Terminal,
	Terminal.State,
	Terminal.Capabilities {
	override val name: String? get() = null
	override val interactive: Boolean get() = false
	override val state: Terminal.State get() = this
	override val capabilities: Terminal.Capabilities get() = this
	override val events: ReceiveChannel<Event> = Channel<Event>().apply { close() }

	override val focused: StateFlow<Boolean> = MutableStateFlow(true)
	override val theme: StateFlow<Terminal.Theme> = MutableStateFlow(theme)
	override val size: StateFlow<Terminal.Size> = MutableStateFlow(size)

	override val ansiLevel: AnsiLevel get() = AnsiLevel.TRUECOLOR
	override val cursorVisibility: Boolean get() = false
	override val focusEvents: Boolean get() = false
	override val inBandResizeEvents: Boolean get() = false
	override val kittyGraphics: Boolean get() = false
	override val kittyKeyboard: Boolean get() = false
	override val kittyNotifications: Boolean get() = false
	override val kittyPointerShape: Boolean get() = false
	override val kittyTextSizingScale: Boolean get() = false
	override val kittyTextSizingWidth: Boolean get() = false
	override val kittyUnderline: Boolean get() = false
	override val synchronizedOutput: Boolean get() = false
	override val themeEvents: Boolean get() = false

	override fun close() {}
}
