package com.jakewharton.mosaic.browser

import com.jakewharton.mosaic.TextCanvas
import com.jakewharton.mosaic.html.ansiToHtml
import com.jakewharton.mosaic.terminal.AnsiLevel
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Renders [TextCanvas] frames into [container] as one `<div>` per row. Rows are diffed against the
 * previous frame by their ANSI encoding so only changed rows touch the DOM. Static output is
 * appended above the live area, like terminal scrollback.
 */
internal class DomRenderer(container: HTMLElement) {
	private val staticArea = div("mosaic-static")
	private val liveArea = div("mosaic-live")
	private val rows = ArrayList<HTMLElement>()
	private val previous = ArrayList<String>()
	private val scratch = StringBuilder()

	init {
		container.appendChild(staticArea)
		container.appendChild(liveArea)
	}

	fun appendStatic(output: String) {
		// Mosaic.static() joins lines with CRLF and strips the final one.
		for (line in output.split("\r\n")) {
			staticArea.appendChild(div("mosaic-row").also { it.setRow(line) })
		}
	}

	fun render(canvas: TextCanvas) {
		for (row in 0 until canvas.height) {
			scratch.clear()
			canvas.appendRowTo(scratch, row, AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
			val ansi = scratch.toString()
			if (row == rows.size) {
				rows += div("mosaic-row").also(liveArea::appendChild)
				previous += "\u0000"
			}
			if (previous[row] != ansi) {
				rows[row].setRow(ansi)
				previous[row] = ansi
			}
		}
		while (rows.size > canvas.height) {
			rows.removeAt(rows.lastIndex).remove()
			previous.removeAt(previous.lastIndex)
		}
	}

	private fun HTMLElement.setRow(ansi: String) {
		// An empty row would collapse to zero height.
		innerHTML = ansiToHtml(ansi).ifEmpty { " " }
	}

	private fun div(className: String): HTMLElement {
		return (document.createElement("div") as HTMLElement).also { it.className = className }
	}
}
