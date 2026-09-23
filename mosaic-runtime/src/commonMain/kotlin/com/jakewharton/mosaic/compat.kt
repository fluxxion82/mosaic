package com.jakewharton.mosaic

import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import de.cketti.codepoints.appendCodePoint

internal fun KeyboardEvent.toKeyEventOrNull(): KeyEvent? {
	if (eventType != KeyboardEvent.EventTypePress) {
		return null
	}

	// Unmapped private-use codepoints (Kitty functional keys) and lone surrogates are ignored.
	return KeyEvent(
		key = when (val codepoint = codepoint) {
			9 -> "Tab"
			13 -> "Enter"
			27 -> "Escape"
			in 32..126 -> codepoint.toChar().toString()
			127 -> "Backspace"
			57350 -> "ArrowLeft"
			57351 -> "ArrowRight"
			57352 -> "ArrowUp"
			57353 -> "ArrowDown"
			57348 -> "Insert"
			57349 -> "Delete"
			57354 -> "PageUp"
			57355 -> "PageDown"
			57356 -> "Home"
			57357 -> "End"
			in 57364..57398 -> "F" + (codepoint - 57363)
			in 0xE000..0xF8FF, in 0xD800..0xDFFF -> return null
			in 0xA0..0x10FFFF -> buildString { appendCodePoint(codepoint) }
			else -> return null
		},
		alt = alt,
		ctrl = ctrl,
		shift = shift,
	)
}
