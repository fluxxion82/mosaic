package com.jakewharton.mosaic.browser

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class TextInputTest {
	private fun codepoints(text: String) = textKeyboardEvents(text).map { it.codepoint }

	@Test fun plainCharactersAreTextKeys() {
		assertThat(isTextKey("a", alt = false, ctrl = false)).isTrue()
		assertThat(isTextKey(" ", alt = false, ctrl = false)).isTrue()
		assertThat(isTextKey("é", alt = false, ctrl = false)).isTrue()
		assertThat(isTextKey("😀", alt = false, ctrl = false)).isTrue()
	}

	@Test fun modifiedCharactersAndNamedKeysAreNotTextKeys() {
		assertThat(isTextKey("a", alt = false, ctrl = true)).isFalse()
		assertThat(isTextKey("a", alt = true, ctrl = false)).isFalse()
		assertThat(isTextKey("Enter", alt = false, ctrl = false)).isFalse()
		assertThat(isTextKey("Dead", alt = false, ctrl = false)).isFalse()
	}

	@Test fun textBecomesCodepoints() {
		assertThat(codepoints("hé😀")).containsExactly('h'.code, 'é'.code, 0x1F600)
	}

	@Test fun lineBreaksBecomeSingleEnter() {
		assertThat(codepoints("a\r\nb\nc\rd")).containsExactly('a'.code, 13, 'b'.code, 13, 'c'.code, 13, 'd'.code)
	}

	@Test fun tabIsKeptAndOtherControlsDropped() {
		assertThat(codepoints("a\tb\u0007\u0085c")).containsExactly('a'.code, 9, 'b'.code, 'c'.code)
	}

	@Test fun loneSurrogateIsDropped() {
		assertThat(codepoints("\uD800")).isEmpty()
	}
}
