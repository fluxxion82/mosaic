package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import kotlin.test.Test

class CompatTest {
	@Test fun ascii() {
		assertThat(KeyboardEvent('a'.code).toKeyEventOrNull()).isEqualTo(KeyEvent("a"))
	}

	@Test fun functionalKey() {
		assertThat(KeyboardEvent(KeyboardEvent.Up).toKeyEventOrNull()).isEqualTo(KeyEvent("ArrowUp"))
	}

	@Test fun nonAsciiCharacter() {
		assertThat(KeyboardEvent('é'.code).toKeyEventOrNull()).isEqualTo(KeyEvent("é"))
	}

	@Test fun supplementaryCharacter() {
		assertThat(KeyboardEvent(0x1F600).toKeyEventOrNull()).isEqualTo(KeyEvent("😀"))
	}

	@Test fun unmappedFunctionalKeyIsIgnored() {
		assertThat(KeyboardEvent(KeyboardEvent.KpBegin).toKeyEventOrNull()).isNull()
	}

	@Test fun controlCharacterIsIgnored() {
		assertThat(KeyboardEvent(1).toKeyEventOrNull()).isNull()
		assertThat(KeyboardEvent(0x85).toKeyEventOrNull()).isNull()
	}

	@Test fun surrogateIsIgnored() {
		assertThat(KeyboardEvent(0xD800).toKeyEventOrNull()).isNull()
	}
}
