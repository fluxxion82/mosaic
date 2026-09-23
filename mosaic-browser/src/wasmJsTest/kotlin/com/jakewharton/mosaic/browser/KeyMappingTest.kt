package com.jakewharton.mosaic.browser

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.jakewharton.mosaic.terminal.KeyboardEvent
import kotlin.test.Test

class KeyMappingTest {
	private fun map(key: String, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) = mosaicKeyboardEvent(key, shift = shift, alt = alt, ctrl = ctrl)

	@Test fun printableFoldsShift() {
		val event = map("A", shift = true)!!
		assertThat(event.codepoint).isEqualTo('A'.code)
		assertThat(event.modifiers).isEqualTo(0)
	}

	@Test fun functionalKeysKeepShift() {
		val event = map("Tab", shift = true)!!
		assertThat(event.codepoint).isEqualTo(9)
		assertThat(event.modifiers).isEqualTo(KeyboardEvent.ModifierShift)
	}

	@Test fun arrowsUseKittyCodepoints() {
		assertThat(map("ArrowUp")?.codepoint).isEqualTo(KeyboardEvent.Up)
	}

	@Test fun functionKeys() {
		assertThat(map("F1")?.codepoint).isEqualTo(KeyboardEvent.F1)
		assertThat(map("F12")?.codepoint).isEqualTo(KeyboardEvent.F12)
		assertThat(map("F13")).isNull()
	}

	@Test fun nonAsciiCharacters() {
		assertThat(map("é")?.codepoint).isEqualTo('é'.code)
		assertThat(map("😀")?.codepoint).isEqualTo(0x1F600)
	}

	@Test fun namedModifierKeysAreIgnored() {
		assertThat(map("Shift")).isNull()
		assertThat(map("Dead")).isNull()
		assertThat(map("Unidentified")).isNull()
	}

	@Test fun ctrlAndAltModifiers() {
		assertThat(map("x", ctrl = true, alt = true)?.modifiers)
			.isEqualTo(KeyboardEvent.ModifierCtrl or KeyboardEvent.ModifierAlt)
	}
}
