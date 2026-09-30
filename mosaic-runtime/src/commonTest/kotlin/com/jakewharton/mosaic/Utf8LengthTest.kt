package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class Utf8LengthTest {
	private fun length(s: String) = s.utf8Length(0, s.length)

	@Test fun wellFormedText() {
		assertThat(length("")).isEqualTo(0)
		assertThat(length("abc")).isEqualTo(3)
		assertThat(length("\u00e9")).isEqualTo(2)
		assertThat(length("\u6f22")).isEqualTo(3)
		assertThat(length("\uD83D\uDE00")).isEqualTo(4)
		assertThat(length("a\uD83D\uDE00b")).isEqualTo(6)
		assertThat("abc\u6f22".utf8Length(1, 4)).isEqualTo(5)
	}

	@Test fun unpairedSurrogatesCountAsTheReplacementCharacter() {
		// A high surrogate followed by something other than a low surrogate must not swallow it.
		assertThat(length("\uD800A")).isEqualTo(3 + 1)
		assertThat(length("\uD800\u00e9")).isEqualTo(3 + 2)
		assertThat(length("\uD800\uD800")).isEqualTo(3 + 3)
		// A high surrogate at the very end has nothing to pair with.
		assertThat(length("A\uD800")).isEqualTo(1 + 3)
		// A lone low surrogate is just as malformed.
		assertThat(length("\uDC00")).isEqualTo(3)
		assertThat(length("\uDC00\uD83D\uDE00")).isEqualTo(3 + 4)
	}
}
