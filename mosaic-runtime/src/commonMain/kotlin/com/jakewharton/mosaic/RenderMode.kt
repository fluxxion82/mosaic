package com.jakewharton.mosaic

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/** How frames reach the terminal. */
public enum class RenderMode {
	/**
	 * Draw below whatever the terminal already shows, rewriting the previous frame in place. Every
	 * frame is sent in full and ends with a line break, so it must leave the last row of the
	 * terminal free. Static output ([StaticEffect], [StaticLogger]) scrolls away above it.
	 */
	Inline,

	/**
	 * Take over the terminal on its alternate screen for the duration of the run and give it back,
	 * untouched, afterwards. Every row is addressed absolutely, so a frame may use all
	 * [TerminalState.size] rows, and only the cells which changed since the previous frame are
	 * sent. A resize redraws everything, as does a [Repaint] request from content ([LocalRepaint]).
	 * After each frame the cursor is parked in the bottom-left cell of the terminal, so that it sits
	 * somewhere stable where the terminal shows it.
	 *
	 * A terminal without an alternate buffer (the Linux console before v6.7) still works: entering
	 * clears its screen to draw on, and leaving keeps the last frame with the cursor at the bottom.
	 *
	 * Static output has no place on the alternate screen: [StaticEffect] and [StaticLogger] fail
	 * with an [IllegalStateException] in this mode.
	 */
	FullScreen,
}

/** The [RenderMode] of the running Mosaic, so content can size itself accordingly. */
public val LocalRenderMode: ProvidableCompositionLocal<RenderMode> = compositionLocalOf {
	RenderMode.Inline
}
