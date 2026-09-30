package com.jakewharton.mosaic

import com.jakewharton.mosaic.terminal.Terminal
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal interface Rendering {
	/**
	 * Render [node] to a single string for display.
	 *
	 * Note: The returned [CharSequence] is only valid until the next call to this function,
	 * as implementations are free to reuse buffers across invocations.
	 */
	fun render(mosaic: Mosaic): CharSequence
}

internal class DebugRendering(
	private val capabilities: Terminal.Capabilities,
	private val systemClock: TimeSource = TimeSource.Monotonic,
) : Rendering {
	private var lastRender: TimeMark? = null

	fun StringBuilder.appendSurface(canvas: TextCanvas) {
		for (row in 0 until canvas.height) {
			canvas.appendRowTo(this, row, capabilities.ansiLevel, capabilities.kittyUnderline)
			append("\r\n")
		}
	}

	override fun render(mosaic: Mosaic): CharSequence {
		var failed = false
		val output = buildString {
			lastRender?.let { lastRender ->
				repeat(50) { append('~') }
				append(" +")
				append(lastRender.elapsedNow())
				append("\r\n")
			}
			lastRender = systemClock.markNow()

			append("NODES:\r\n")
			append(mosaic.dumpNodes().replace("\n", "\r\n"))
			append("\r\n\r\n")

			try {
				mosaic.static()?.let { static ->
					append("STATIC:\r\n")
					append(static)
					append("\r\n\r\n")
				}
			} catch (t: Throwable) {
				failed = true
				append(t.stackTraceToString().replace("\n", "\r\n"))
				append("\r\n")
			}

			append("OUTPUT:\r\n")
			try {
				appendSurface(mosaic.draw())
			} catch (t: Throwable) {
				failed = true
				append(t.stackTraceToString().replace("\n", "\r\n"))
			}
		}
		if (failed) {
			throw RuntimeException("Failed\r\n\r\n$output")
		}
		return output
	}
}

/**
 * Renders on the alternate screen: every row is positioned absolutely, so the terminal re-wrapping
 * earlier output cannot desynchronise it, and only the cells which differ from the previous frame
 * are sent. The whole screen is cleared and redrawn whenever the terminal or the frame changes
 * size, whenever the terminal reported a resize since the last complete frame (it reflows what it
 * shows even when the size ends up the same), whenever content asked for it through [Repaint], and
 * whenever that is cheaper than the difference. After a frame which sent anything, the cursor is
 * parked in the bottom-left cell.
 */
internal class FullScreenRendering(
	private val capabilities: Terminal.Capabilities,
	private val terminalSize: () -> Terminal.Size,
	/** Increases with every resize event the terminal delivered, not only with size changes. */
	private val resizeGeneration: () -> Int = { 0 },
	/** Increases with every [Repaint.request]; any change since the last frame redraws everything. */
	private val repaintGeneration: () -> Int = { 0 },
) : Rendering {
	private val stringBuilder = StringBuilder(1024)
	private val scratch = StringBuilder(1024)
	private var previous: TextSurface? = null
	private var previousColumns = -1
	private var previousRows = -1
	private var previousGeneration = -1
	private var previousRepaints = -1

	override fun render(mosaic: Mosaic): CharSequence {
		return stringBuilder.apply {
			clear()

			// Read the size once. A resize which arrives during this frame is handled by the next one,
			// which the resize itself requests, so no frame mixes two geometries.
			val size = terminalSize()
			val generation = resizeGeneration()
			// Read before drawing too: a request which lands during this frame then differs from what
			// the next frame compares against, so it cannot be lost.
			val repaints = repaintGeneration()

			check(mosaic.static() == null) {
				"Static output is not supported in full-screen mode; use RenderMode.Inline for " +
					"StaticEffect and StaticLogger."
			}

			val surface = mosaic.draw() as TextSurface
			val rows = minOf(surface.height, size.rows)
			val columns = minOf(surface.width, size.columns)

			if (capabilities.synchronizedOutput) {
				append(synchronizedOutputEnable)
			}
			val frameStart = length

			val last = previous
			if (
				last == null ||
				generation != previousGeneration ||
				repaints != previousRepaints ||
				size.columns != previousColumns ||
				size.rows != previousRows ||
				last.width != surface.width ||
				last.height != surface.height
			) {
				appendFullRedraw(surface, rows, columns)
			} else {
				appendDifference(surface, last, rows, columns)
				// A frame which changes most of the screen may cost more than clearing and redrawing it.
				if (length - frameStart > FullRedrawComparisonThreshold) {
					scratch.clear()
					scratch.appendFullRedraw(surface, rows, columns)
					// What goes down the wire is UTF-8, so compare that, not UTF-16 units.
					if (scratch.utf8Length(0, scratch.length) < utf8Length(frameStart, length)) {
						setLength(frameStart)
						append(scratch)
					}
				}
			}

			if (length > frameStart && rows > 0) {
				// Park the cursor somewhere stable rather than after the last change.
				appendCursorPosition(size.rows - 1, 0)
			}

			if (capabilities.synchronizedOutput) {
				append(synchronizedOutputDisable)
			}

			// A frame which spanned a resize describes a screen the terminal has since reflowed.
			previous = if (resizeGeneration() == generation) surface else null
			previousColumns = size.columns
			previousRows = size.rows
			previousGeneration = generation
			previousRepaints = repaints
		}
	}

	private fun StringBuilder.appendFullRedraw(surface: TextSurface, rows: Int, columns: Int) {
		append(clearScreen)
		for (row in 0 until rows) {
			val end = surface.trimmedRowEnd(row, columns)
			if (end == 0) continue
			appendCursorPosition(row, 0)
			surface.appendCells(this, row, 0, end, capabilities.ansiLevel, capabilities.kittyUnderline)
		}
	}

	private fun StringBuilder.appendDifference(surface: TextSurface, last: TextSurface, rows: Int, columns: Int) {
		for (row in 0 until rows) {
			var first = -1
			var lastChanged = -1
			for (column in 0 until columns) {
				if (!surface.cellAt(row, column).contentEquals(last.cellAt(row, column))) {
					if (first < 0) first = column
					lastChanged = column
				}
			}
			if (first < 0) continue

			// Never split a wide character: widen the span to include both of its halves.
			if (surface.cellAt(row, first).codePoint == WideContinuationCodePoint && first > 0) {
				first--
			}
			var end = lastChanged + 1
			if (end < columns && surface.cellAt(row, end).codePoint == WideContinuationCodePoint) {
				end++
			}

			// Cells which became blank at the end of the span (and everything after them is blank
			// too) are cheaper to erase than to overwrite with spaces, once there are a few of them.
			val content = surface.trimmedRowEnd(row, columns)
			appendCursorPosition(row, first)
			if (content < end && end - content > clearLine.length) {
				if (content > first) {
					surface.appendCells(this, row, first, content, capabilities.ansiLevel, capabilities.kittyUnderline)
				}
				append(clearLine)
			} else {
				surface.appendCells(this, row, first, end, capabilities.ansiLevel, capabilities.kittyUnderline)
			}
		}
	}

	private companion object {
		/** Below this many units a difference is never worth comparing against a full redraw. */
		const val FullRedrawComparisonThreshold = 256
	}
}

/**
 * The number of bytes the UTF-8 encoding of the range [from], [to] takes. An unpaired surrogate
 * counts as the three-byte replacement character (the JVM emits a one-byte `?` instead; the
 * difference does not matter for a cost estimate).
 */
internal fun CharSequence.utf8Length(from: Int, to: Int): Int {
	var bytes = 0
	var index = from
	while (index < to) {
		val code = this[index].code
		bytes += when {
			code < 0x80 -> 1

			code < 0x800 -> 2

			code in 0xD800..0xDBFF && index + 1 < to && this[index + 1].code in 0xDC00..0xDFFF -> {
				index++ // The low surrogate belongs to the same 4-byte sequence.
				4
			}

			else -> 3
		}
		index++
	}
	return bytes
}

internal class AnsiRendering(
	private val capabilities: Terminal.Capabilities,
) : Rendering {
	private val stringBuilder = StringBuilder(100)
	private var lastHeight = 0

	override fun render(mosaic: Mosaic): CharSequence {
		return stringBuilder.apply {
			clear()

			if (capabilities.synchronizedOutput) {
				append(synchronizedOutputEnable)
			}

			var staleLines = lastHeight
			if (staleLines > 0) {
				// Move to start of previous output.
				append(CSI)
				append(staleLines)
				append('F')
			}

			fun appendSurface(canvas: TextCanvas) {
				for (row in 0 until canvas.height) {
					if (staleLines-- > 0) {
						// We have previously drawn on this line. Clear first to be safe. For terminals which
						// do not support synchronized rendering, this may allow seeing a partial row render.
						append(clearLine)
					}
					canvas.appendRowTo(this, row, capabilities.ansiLevel, capabilities.kittyUnderline)
					append("\r\n")
				}
			}

			mosaic.static()?.let { static ->
				// We don't know the width or height of static strings, and we don't want to waste time
				// parsing them to get an accurate count of each to minimally clear only the lines it will
				// write. Instead, just clear everything to ensure we neither leave old cells nor attempt
				// a clear to end-of-line when the cursor is on the last column (thus clearing that cell).
				if (staleLines > 0) {
					append(clearDisplay)
					staleLines = 0
				}
				append(static)
				append("\r\n")
			}

			val surface = mosaic.draw()
			appendSurface(surface)

			// If the new output contains fewer lines than the last output, clear those old lines.
			if (staleLines > 0) {
				append(clearDisplay)
			}

			if (capabilities.synchronizedOutput) {
				append(synchronizedOutputDisable)
			}

			lastHeight = surface.height
		}
	}
}
