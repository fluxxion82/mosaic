package com.jakewharton.mosaic.browser

import com.jakewharton.mosaic.terminal.KeyboardEvent
import org.w3c.dom.events.KeyboardEvent as DomKeyboardEvent

/**
 * Map a DOM `keydown` to the codepoints Mosaic understands (legacy terminal encoding for printable
 * characters, Kitty private-use codepoints for functional keys).
 *
 * Returns null, leaving the event to the browser, for keys Mosaic cannot represent, for browser/OS
 * shortcuts, and for plain text. Text arrives through the input element's `input` event instead so
 * that soft keyboards, paste, dead keys, and IME composition all work (see [textKeyboardEvents]).
 */
internal fun DomKeyboardEvent.toMosaicOrNull(captureTab: Boolean): KeyboardEvent? {
	if (metaKey || isComposing) return null
	if (key in browserKeys) return null
	// Leave copy, reload, find, print, save, zoom, etc. to the browser. An unhandled Ctrl+C would
	// also cancel the whole composition.
	if (ctrlKey && !altKey && key.length == 1 && key.lowercase() in browserCtrlKeys) return null
	// Shift+Insert pastes on Windows and Linux.
	if (shiftKey && key == "Insert") return null
	// Without capture, Tab keeps moving focus so keyboard users are not trapped (WCAG 2.1.2).
	if (key == "Tab" && !captureTab) return null

	// AltGr is reported as Ctrl+Alt on Windows and Linux. The character already reflects it.
	val altGraph = getModifierState("AltGraph")
	val alt = altKey && !altGraph
	val ctrl = ctrlKey && !altGraph
	if (isTextKey(key, alt = alt, ctrl = ctrl)) return null
	return mosaicKeyboardEvent(key = key, shift = shiftKey, alt = alt, ctrl = ctrl)
}

/** Whether [key] types a character, which is delivered by the `input` event rather than `keydown`. */
internal fun isTextKey(key: String, alt: Boolean, ctrl: Boolean): Boolean {
	if (alt || ctrl) return false
	val codepoint = key.singleCodepointOrNull() ?: return false
	return !codepoint.isControl()
}

/**
 * Convert text inserted into the input element (typing, paste, IME commit) into key presses, as a
 * terminal would receive them. Line breaks become Enter, tabs become Tab, and other control
 * characters are dropped.
 */
internal fun textKeyboardEvents(text: String): List<KeyboardEvent> {
	val events = ArrayList<KeyboardEvent>(text.length)
	var index = 0
	while (index < text.length) {
		val char = text[index]
		val codepoint = if (char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
			text.substring(index, index + 2).singleCodepointOrNull()!!.also { index++ }
		} else {
			char.code
		}
		index++
		when {
			codepoint == '\r'.code -> {
				events += KeyboardEvent(13)
				// Treat CRLF as a single Enter.
				if (index < text.length && text[index] == '\n') index++
			}

			codepoint == '\n'.code -> events += KeyboardEvent(13)

			codepoint == '\t'.code -> events += KeyboardEvent(9)

			codepoint.isControl() || codepoint in 0xD800..0xDFFF -> {}

			else -> events += KeyboardEvent(codepoint)
		}
	}
	return events
}

private fun Int.isControl() = this < 32 || this in 127..159

private val browserKeys = setOf("F5", "F11", "F12")
private const val browserCtrlKeys = "cflnprstvw=-+0123456789"

internal fun mosaicKeyboardEvent(
	key: String,
	shift: Boolean,
	alt: Boolean,
	ctrl: Boolean,
): KeyboardEvent? {
	var printable = false
	val codepoint = when (key) {
		"Tab" -> 9

		"Enter" -> 13

		"Escape" -> 27

		"Backspace" -> 127

		"ArrowLeft" -> KeyboardEvent.Left

		"ArrowRight" -> KeyboardEvent.Right

		"ArrowUp" -> KeyboardEvent.Up

		"ArrowDown" -> KeyboardEvent.Down

		"Insert" -> KeyboardEvent.Insert

		"Delete" -> KeyboardEvent.Delete

		"PageUp" -> KeyboardEvent.PageUp

		"PageDown" -> KeyboardEvent.PageDown

		"Home" -> KeyboardEvent.Home

		"End" -> KeyboardEvent.End

		else -> {
			val function = if (key.length in 2..3 && key[0] == 'F') key.substring(1).toIntOrNull() else null
			when {
				function != null && function in 1..12 -> KeyboardEvent.F1 + function - 1

				else -> {
					// Named keys ("Shift", "CapsLock", …) are words; characters are a single codepoint.
					val character = key.singleCodepointOrNull() ?: return null
					if (character.isControl()) return null
					printable = true
					character
				}
			}
		}
	}
	var modifiers = 0
	// Like a legacy terminal, the shift state is already folded into printable characters.
	if (shift && !printable) modifiers = modifiers or KeyboardEvent.ModifierShift
	if (alt) modifiers = modifiers or KeyboardEvent.ModifierAlt
	if (ctrl) modifiers = modifiers or KeyboardEvent.ModifierCtrl
	// Auto-repeat is sent as another press, as a legacy terminal would. Mosaic ignores repeats.
	return KeyboardEvent(codepoint = codepoint, modifiers = modifiers)
}

internal fun String.singleCodepointOrNull(): Int? = when {
	length == 1 -> this[0].code

	length == 2 && this[0].isHighSurrogate() && this[1].isLowSurrogate() ->
		0x10000 + ((this[0].code - 0xD800) shl 10) + (this[1].code - 0xDC00)

	else -> null
}
