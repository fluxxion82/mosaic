package com.jakewharton.mosaic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestTerminal
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class FullScreenCompositionTest {
	@Test fun resizeCountedByTheTerminalRedrawsEvenWhenNoEventArrives() = runTest {
		var state: TestTerminal.State? = null
		val rendering = FullScreenRendering(
			capabilities = TestTerminal.Capabilities(synchronizedOutput = false),
			terminalSize = { Terminal.Size(columns = 20, rows = 5) },
			resizeGeneration = { state!!.resizes.value },
		)
		runMosaicTest(RenderingSnapshots(rendering)) {
			state = this.state
			setContent { Text("Steady") }
			assertThat(awaitSnapshot()).startsWith(clearScreen)

			// The terminal counted a resize back to the same size. Its ResizeEvent may have been
			// dropped from the event queue; the count alone must produce a full redraw.
			this.state.resizes.value++
			val redraw = awaitSnapshot()
			assertThat(redraw).startsWith(clearScreen)
			assertThat(redraw).contains("Steady")
		}
	}

	@Test fun repaintRequestedByContentRedrawsTheNextFrameInFull() = runTest {
		val frames = mutableListOf<String>()
		runFullScreen(frames::add) { repaint ->
			LaunchedEffect(Unit) {
				withFrameNanos {}
				// Nothing changed on screen, yet the next frame must be drawn from scratch.
				repaint.request()
				withFrameNanos {}
			}
		}
		assertThat(frames).hasSize(2)
		assertThat(frames[1]).startsWith(clearScreen)
		assertThat(frames[1]).contains("Steady")
	}

	@Test fun repaintRequestsCoalesceIntoOneRedraw() = runTest {
		val frames = mutableListOf<String>()
		runFullScreen(frames::add) { repaint ->
			LaunchedEffect(Unit) {
				withFrameNanos {}
				repaint.request()
				repaint.request()
				withFrameNanos {}
				withFrameNanos {}
			}
		}
		assertThat(frames).hasSize(2)
		assertThat(frames[1].occurrencesOf(clearScreen)).isEqualTo(1)
	}

	@Test fun repaintRequestedWhileAFrameIsDrawnForcesTheNextOne() = runTest {
		val frames = mutableListOf<String>()
		var repaint: Repaint? = null
		runFullScreen(
			output = { frame ->
				frames += frame
				// The first frame is on its way out, its count already read: the request must survive
				// into the frame after it.
				if (frames.size == 1) repaint!!.request()
			},
		) { current ->
			repaint = current
			LaunchedEffect(Unit) {
				withFrameNanos {}
				withFrameNanos {}
			}
		}
		assertThat(frames).hasSize(2)
		assertThat(frames[1]).startsWith(clearScreen)
		assertThat(frames[1]).contains("Steady")
	}

	@Test fun repaintRequestDoesNothingInline() = runTest {
		val terminal = TestTerminal()
		val repaint = Repaint()
		val frames = mutableListOf<String>()
		runMosaicComposition(
			terminal = terminal,
			rendering = AnsiRendering(terminal.capabilities),
			output = frames::add,
			renderMode = RenderMode.Inline,
			repaint = repaint,
		) {
			Text("Steady")
			val current = LocalRepaint.current
			LaunchedEffect(Unit) {
				withFrameNanos {}
				current.request()
				withFrameNanos {}
				withFrameNanos {}
			}
		}
		// Counted, since the same handle is provided, but no frame was drawn for it.
		assertThat(repaint.requests.value).isEqualTo(1)
		assertThat(frames).hasSize(1)
	}

	/** Runs [content] with "Steady" on a full screen, handing it the provided [Repaint]. */
	private suspend fun runFullScreen(
		output: (String) -> Unit,
		content: @Composable (Repaint) -> Unit,
	) {
		val terminal = TestTerminal(capabilities = TestTerminal.Capabilities(synchronizedOutput = false))
		val repaint = Repaint()
		val rendering = FullScreenRendering(
			capabilities = terminal.capabilities,
			terminalSize = { Terminal.Size(columns = 20, rows = 5) },
			repaintGeneration = { repaint.requests.value },
		)
		runMosaicComposition(
			terminal = terminal,
			rendering = rendering,
			output = output,
			renderMode = RenderMode.FullScreen,
			repaint = repaint,
		) {
			Text("Steady")
			content(LocalRepaint.current)
		}
	}

	private fun String.occurrencesOf(needle: String): Int {
		var count = 0
		var index = indexOf(needle)
		while (index >= 0) {
			count++
			index = indexOf(needle, index + needle.length)
		}
		return count
	}

	@Test fun unchangedContentWithoutAResizeSendsNothing() = runTest {
		val rendering = FullScreenRendering(
			capabilities = TestTerminal.Capabilities(synchronizedOutput = false),
			terminalSize = { Terminal.Size(columns = 20, rows = 5) },
		)
		runMosaicTest(RenderingSnapshots(rendering)) {
			setContent { Text("Steady") }
			assertThat(awaitSnapshot()).contains("Steady")
			assertThat(rendering.render(this).toString()).doesNotContain(clearScreen)
		}
	}
}
