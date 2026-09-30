package com.jakewharton.mosaic

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.terminal.Terminal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Asks for the next frame to be drawn in full, as after a resize, instead of only the cells which
 * changed since the previous one. This is how content repaints a screen the terminal no longer
 * shows correctly (a `Ctrl+L` binding, say). Layout is not touched.
 *
 * Only [RenderMode.FullScreen] keeps a previous frame to diff against. In [RenderMode.Inline] every
 * frame already goes out in full, so a request there does nothing, not even draw a frame. The same
 * holds outside a Mosaic run, where [LocalRepaint] hands out a [Repaint] nobody listens to.
 *
 * Requests coalesce: however many arrive before the next frame, it is drawn in full once. One made
 * while a frame is being drawn is kept for the frame after it.
 */
@Stable
public class Repaint internal constructor() {
	/**
	 * How many full redraws were requested so far. Like [Terminal.State.resizes] this is a count the
	 * renderer reads at the start of a frame and the composition watches for changes, rather than an
	 * event which could be lost, so a request always reaches the frame after it.
	 */
	internal val requests: MutableStateFlow<Int> = MutableStateFlow(0)

	/** Draw the next frame in full. */
	public fun request() {
		requests.update { it + 1 }
	}
}

/** The [Repaint] of the running Mosaic. */
public val LocalRepaint: ProvidableCompositionLocal<Repaint> = staticCompositionLocalOf {
	Repaint()
}
