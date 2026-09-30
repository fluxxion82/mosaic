package com.jakewharton.mosaic

import androidx.compose.runtime.Composable
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestTerminal
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FullScreenRenderingTest {
	private var size = Terminal.Size(columns = 10, rows = 3)
	private var resizes = 0
	private var repaints = 0
	private val capabilities = TestTerminal.Capabilities(synchronizedOutput = false)
	private val rendering = FullScreenRendering(capabilities, { size }, { resizes }, { repaints })

	/** Where the cursor is parked after a frame which sent anything. */
	private val park get() = "$CSI${size.rows}H"

	private fun render(surface: TextSurface): String = rendering.render(FakeMosaic(surface)).toString()

	@Test fun firstFrameClearsAndDrawsEveryRowAbsolutely() {
		val output = render(surface(10, 3, "ab", "", "cd"))
		assertThat(output).isEqualTo("${clearScreen}${CSI}1Hab${CSI}3Hcd$park")
	}

	@Test fun unchangedFrameEmitsNothing() {
		render(surface(10, 3, "ab", "", "cd"))
		assertThat(render(surface(10, 3, "ab", "", "cd"))).isEqualTo("")
	}

	@Test fun synchronizedOutputWrapsEachFrame() {
		val rendering = FullScreenRendering(TestTerminal.Capabilities(synchronizedOutput = true), { size })
		val first = rendering.render(FakeMosaic(surface(10, 3, "ab"))).toString()
		assertThat(first).isEqualTo("$synchronizedOutputEnable$clearScreen${CSI}1Hab$park$synchronizedOutputDisable")
		val unchanged = rendering.render(FakeMosaic(surface(10, 3, "ab"))).toString()
		assertThat(unchanged).isEqualTo("$synchronizedOutputEnable$synchronizedOutputDisable")
	}

	@Test fun oneCellChangeEmitsOnlyThatCell() {
		render(surface(10, 3, "hello", "world", "!"))
		assertThat(render(surface(10, 3, "hello", "worxd", "!"))).isEqualTo("${CSI}2;4Hx$park")
	}

	@Test fun changesOnTwoRowsEmitExactlyThoseRows() {
		size = Terminal.Size(columns = 10, rows = 20)
		val rows = Array(20) { "row ${it + 1}" }
		render(surface(10, 20, *rows))
		rows[2] = "ROW 3"
		rows[16] = "row 17!"
		assertThat(render(surface(10, 20, *rows))).isEqualTo("${CSI}3HROW${CSI}17;7H!$park")
	}

	@Test fun cursorIsParkedBottomLeftAfterEveryFrameWhichSentSomething() {
		size = Terminal.Size(columns = 10, rows = 7)
		render(surface(10, 3, "ab", "cd", "ef"))
		val output = render(surface(10, 3, "ab", "cd", "eF"))
		assertThat(output).isEqualTo("${CSI}3;2HF${CSI}7H")
	}

	@Test fun terminalResizeClearsAndRedrawsEverything() {
		render(surface(10, 3, "ab", "cd", "ef"))
		size = Terminal.Size(columns = 12, rows = 3)
		assertThat(render(surface(10, 3, "ab", "cd", "ef")))
			.isEqualTo("${clearScreen}${CSI}1Hab${CSI}2Hcd${CSI}3Hef$park")
	}

	@Test fun resizeEventBackToTheSameSizeStillRedrawsEverything() {
		render(surface(10, 3, "ab", "cd", "ef"))
		// The terminal shrank and grew back between frames: same size, same cells, but it reflowed.
		resizes += 2
		assertThat(render(surface(10, 3, "ab", "cd", "ef")))
			.isEqualTo("${clearScreen}${CSI}1Hab${CSI}2Hcd${CSI}3Hef$park")
		// And only once.
		assertThat(render(surface(10, 3, "ab", "cd", "ef"))).isEqualTo("")
	}

	@Test fun frameWhichSpansAResizeIsNotTrusted() {
		var generation = 0
		val rendering = FullScreenRendering(capabilities, { size }, resizeGeneration = { generation })
		rendering.render(FakeMosaic(surface(10, 3, "ab", "cd", "ef")))
		// The resize lands while this frame is being produced: the frame goes out for the old
		// geometry, but the next one must not diff against it.
		val spanning = FakeMosaic(surface(10, 3, "ab", "cd", "eF")) { generation++ }
		assertThat(rendering.render(spanning).toString()).isEqualTo("${CSI}3;2HF$park")
		assertThat(rendering.render(FakeMosaic(surface(10, 3, "ab", "cd", "eF"))).toString())
			.isEqualTo("${clearScreen}${CSI}1Hab${CSI}2Hcd${CSI}3HeF$park")
	}

	@Test fun repaintRequestClearsAndRedrawsAnUnchangedFrame() {
		render(surface(10, 3, "ab", "cd", "ef"))
		repaints++
		assertThat(render(surface(10, 3, "ab", "cd", "ef")))
			.isEqualTo("${clearScreen}${CSI}1Hab${CSI}2Hcd${CSI}3Hef$park")
		// Once served, the request is spent.
		assertThat(render(surface(10, 3, "ab", "cd", "ef"))).isEqualTo("")
	}

	@Test fun repaintRequestsBetweenFramesCoalesceIntoOneRedraw() {
		render(surface(10, 3, "ab", "cd", "ef"))
		repaints += 2
		assertThat(render(surface(10, 3, "ab", "cd", "ef")))
			.isEqualTo("${clearScreen}${CSI}1Hab${CSI}2Hcd${CSI}3Hef$park")
		assertThat(render(surface(10, 3, "ab", "cd", "ef"))).isEqualTo("")
	}

	@Test fun repaintRequestedWhileAFrameIsDrawnRedrawsTheNextOne() {
		render(surface(10, 3, "ab", "cd", "ef"))
		// The request lands after this frame read the count: it goes out as a difference and must not
		// swallow the request.
		val spanning = FakeMosaic(surface(10, 3, "ab", "cd", "eF")) { repaints++ }
		assertThat(rendering.render(spanning).toString()).isEqualTo("${CSI}3;2HF$park")
		assertThat(render(surface(10, 3, "ab", "cd", "eF")))
			.isEqualTo("${clearScreen}${CSI}1Hab${CSI}2Hcd${CSI}3HeF$park")
		assertThat(render(surface(10, 3, "ab", "cd", "eF"))).isEqualTo("")
	}

	@Test fun frameSizeChangeClearsAndRedrawsEverything() {
		render(surface(10, 3, "ab", "cd", "ef"))
		assertThat(render(surface(9, 3, "ab", "cd", "ef")))
			.isEqualTo("${clearScreen}${CSI}1Hab${CSI}2Hcd${CSI}3Hef$park")
	}

	@Test fun rowsAndColumnsBeyondTheTerminalAreNotSent() {
		size = Terminal.Size(columns = 4, rows = 2)
		assertThat(render(surface(10, 3, "abcdef", "gh", "ij")))
			.isEqualTo("${clearScreen}${CSI}1Habcd${CSI}2Hgh$park")
	}

	@Test fun blankTailIsErasedRatherThanOverwritten() {
		size = Terminal.Size(columns = 20, rows = 1)
		render(surface(20, 1, "hello world"))
		assertThat(render(surface(20, 1, "hello"))).isEqualTo("${CSI}1;7H$clearLine$park")
		render(surface(20, 1, "hello world again"))
		// The changed span keeps its leading content and erases the rest.
		assertThat(render(surface(20, 1, "hello worm"))).isEqualTo("${CSI}1;10Hm$clearLine$park")
	}

	@Test fun fewBlankCellsAreStillOverwritten() {
		size = Terminal.Size(columns = 20, rows = 1)
		render(surface(20, 1, "abcd"))
		// Two spaces cost less than an erase sequence.
		assertThat(render(surface(20, 1, "ab"))).isEqualTo("${CSI}1;3H  $park")
	}

	@Test fun clearingMostOfTheScreenUsesAFullRedraw() {
		size = Terminal.Size(columns = 120, rows = 40)
		render(surface(120, 40, *Array(40) { "x".repeat(120) }))
		assertThat(render(surface(120, 40, "ok"))).isEqualTo("${clearScreen}${CSI}1Hok$park")
	}

	@Test fun partiallyRewrittenRowCarriesItsStyles() {
		val red = "${CSI}38;2;255;0;0;1m"
		val reset = "${CSI}0m"
		val first = surface(10, 1, "abcdef") { cell ->
			cell.foreground = Color.Red
			cell.textStyle = TextStyle.Bold
		}
		render(first)
		val second = surface(10, 1, "abcDef") { cell ->
			cell.foreground = Color.Red
			cell.textStyle = TextStyle.Bold
		}
		// The span starts mid-row, so the first cell must set the colour and boldness itself.
		assertThat(render(second)).isEqualTo("${CSI}1;4H${red}D$reset$park")
	}

	@Test fun wideCharacterReplacedByAnotherIsRewrittenWhole() {
		render(surface(10, 1, "a漢b"))
		// Only the first half differs (the second half is a continuation in both), yet both halves
		// must be sent or the terminal would show a half character.
		assertThat(render(surface(10, 1, "a字b"))).isEqualTo("${CSI}1;2H字$park")
	}

	@Test fun wideCharacterCutByTheTerminalEdgeBecomesASpace() {
		size = Terminal.Size(columns = 2, rows = 1)
		assertThat(render(surface(10, 1, "a漢"))).isEqualTo("${clearScreen}${CSI}1Ha $park")
	}

	@Test fun staticOutputIsAnError() {
		val failure = assertFailsWith<IllegalStateException> {
			rendering.render(FakeMosaic(surface(10, 3, "ab"), static = "static"))
		}
		assertThat(failure.message!!).contains("full-screen")
	}

	@Test fun bytesPerFrameAtChatSize() {
		val columns = 120
		val rows = 40
		size = Terminal.Size(columns = columns, rows = rows)
		val inline = AnsiRendering(TestTerminal.Capabilities(synchronizedOutput = true))
		val fullScreen = FullScreenRendering(TestTerminal.Capabilities(synchronizedOutput = true), { size })

		fun chat(first: Int, lines: Int = rows - 2): Array<String> = Array(rows) { row ->
			when {
				row == rows - 2 -> "#general 12 peers 3 unread"
				row == rows - 1 -> "> hi"
				row < lines -> "12:${(first + row) % 60} <alice> message number ${first + row} with some ordinary chat text in it"
				else -> ""
			}
		}
		fun frame(lines: Array<String>) = surface(columns, rows, *lines) { cell -> cell.foreground = Color.Green }
		fun bytes(rendering: Rendering, lines: Array<String>): Int {
			return rendering.render(FakeMosaic(frame(lines))).toString().encodeToByteArray().size
		}

		val results = mutableListOf<String>()
		fun measure(name: String, inlineLines: Array<String>, fullLines: Array<String> = inlineLines): Pair<Int, Int> {
			val pair = bytes(inline, inlineLines) to bytes(fullScreen, fullLines)
			results += "$name inline=${pair.first} fullScreen=${pair.second}"
			return pair
		}

		val initial = chat(first = 0)
		measure("first frame", initial)

		val keystroke = initial.copyOf().also { it[rows - 1] = "> hi!" }
		val (inlineKeystroke, fullKeystroke) = measure("keystroke", keystroke)

		val newLine = chat(first = 1).also { it[rows - 1] = "> hi!" }
		val (inlineNewLine, fullNewLine) = measure("new chat line", newLine)

		val dense = newLine.map { " $it" }.toTypedArray()
		val (inlineDense, fullDense) = measure("dense update (every cell moves)", dense)

		val filtered = chat(first = 1, lines = 3).also { it[rows - 1] = "> hi!" }
		val (inlineFiltered, fullFiltered) = measure("filter list down to 3 rows", filtered)

		val cleared = Array(rows) { "" }.also { it[rows - 1] = "> hi!" }
		val (inlineCleared, fullCleared) = measure("clear the screen", cleared)

		// Three rows of wide characters survive while the rest clears: the difference is cheaper
		// than a redraw in bytes, although not in UTF-16 units.
		val wideWithChat = Array(rows) { row -> if (row < 3) "漢".repeat(60) else newLine[row] }
		bytes(fullScreen, wideWithChat)
		val wideKept = Array(rows) { row -> if (row < 3) "漢".repeat(60) else "" }
		val fullWideKept = bytes(fullScreen, wideKept)
		val redrawOfWideKept = bytes(FullScreenRendering(TestTerminal.Capabilities(synchronizedOutput = true), { size }), wideKept)
		results += "keep 3 rows of 60 wide characters, clear 37 fullScreen=$fullWideKept (redraw would be $redrawOfWideKept)"

		println("bytes per frame at ${columns}x$rows: " + results.joinToString("; "))
		// Synchronized-output markers, one cursor position, the cell's attributes, a character, a
		// reset, and the parked cursor.
		assertThat(fullKeystroke).isLessThan(72)
		assertThat(fullKeystroke).isLessThan(inlineKeystroke)
		assertThat(fullNewLine).isLessThanOrEqualTo(inlineNewLine)
		assertThat(fullDense).isLessThanOrEqualTo(inlineDense + 16)
		assertThat(fullFiltered).isLessThan(inlineFiltered)
		assertThat(fullCleared).isLessThan(inlineCleared)
		assertThat(fullWideKept).isLessThan(redrawOfWideKept)
	}

	private fun surface(width: Int, height: Int, vararg rows: String, style: (TextPixel) -> Unit = {}): TextSurface {
		val surface = TextSurface(width, height)
		for ((row, text) in rows.withIndex()) {
			var column = 0
			for (codePoint in text.codePoints()) {
				val cell = surface.cellAt(row, column++)
				cell.codePoint = codePoint
				style(cell)
				if (codePoint > 0x2E7F) { // Every wide character used in these tests is CJK.
					val continuation = surface.cellAt(row, column++)
					continuation.codePoint = WideContinuationCodePoint
					style(continuation)
				}
			}
		}
		return surface
	}

	private fun String.codePoints(): List<Int> {
		val result = mutableListOf<Int>()
		var index = 0
		while (index < length) {
			val codePoint = codePointAt(index)
			result += codePoint
			index += if (codePoint > 0xFFFF) 2 else 1
		}
		return result
	}

	private fun String.codePointAt(index: Int): Int {
		val high = this[index]
		if (high.isHighSurrogate() && index + 1 < length) {
			val low = this[index + 1]
			if (low.isLowSurrogate()) {
				return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
			}
		}
		return high.code
	}

	private class FakeMosaic(
		private val canvas: TextCanvas,
		private var static: String? = null,
		private val onDraw: () -> Unit = {},
	) : Mosaic {
		override fun setContent(content: @Composable () -> Unit) = error("unused")
		override fun draw(): TextCanvas {
			onDraw()
			return canvas
		}
		override fun static(): String? = static.also { static = null }
		override fun dumpNodes(): String = ""
		override suspend fun awaitComplete() = Unit
		override fun cancel() = Unit
	}
}
