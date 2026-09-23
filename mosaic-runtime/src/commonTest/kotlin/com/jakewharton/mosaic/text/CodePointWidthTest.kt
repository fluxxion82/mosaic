package com.jakewharton.mosaic.text

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.test.Test

class CodePointWidthTest {
	@Test fun ascii() {
		assertThat(codePointWidth('a'.code)).isEqualTo(1)
		assertThat(codePointWidth(' '.code)).isEqualTo(1)
	}

	@Test fun eastAsianWide() {
		assertThat(codePointWidth(0x4E2D)).isEqualTo(2) // 中
		assertThat(codePointWidth(0x3042)).isEqualTo(2) // あ
		assertThat(codePointWidth(0xD55C)).isEqualTo(2) // 한
		assertThat(codePointWidth(0xFF21)).isEqualTo(2) // Fullwidth A.
		// Unassigned code point in the CJK Extension planes.
		assertThat(codePointWidth(0x2FFF0)).isEqualTo(2)
	}

	@Test fun emojiPresentation() {
		assertThat(codePointWidth(0x1F600)).isEqualTo(2)
		assertThat(codePointWidth(0x1F44D)).isEqualTo(2)
	}

	@Test fun narrowSymbols() {
		assertThat(codePointWidth(0xE9)).isEqualTo(1) // é
		assertThat(codePointWidth(0x2500)).isEqualTo(1) // ─
		assertThat(codePointWidth(0x280B)).isEqualTo(1) // ⠋
		// Heart without the emoji variation selector is text presentation.
		assertThat(codePointWidth(0x2764)).isEqualTo(1)
	}

	@Test fun zeroWidth() {
		assertThat(codePointWidth(0x0301)).isEqualTo(0) // Combining acute accent.
		assertThat(codePointWidth(0x200D)).isEqualTo(0) // Zero-width joiner.
		assertThat(codePointWidth(0xFE0F)).isEqualTo(0) // Variation selector 16.
		assertThat(codePointWidth(0x1161)).isEqualTo(0) // Hangul medial vowel.
		assertThat(codePointWidth(0x00AD)).isEqualTo(1) // Soft hyphen stays visible.
	}

	@Test fun stringWidth() {
		assertThat("ab中́😀".terminalWidth()).isEqualTo(6)
	}

	@Test fun emojiSequences() {
		// Heart + VS16 takes emoji presentation.
		assertThat("❤️".terminalWidth()).isEqualTo(2)
		// Keycap one.
		assertThat("1️⃣".terminalWidth()).isEqualTo(2)
		// Family: man ZWJ woman ZWJ girl.
		assertThat("👨‍👩‍👧".terminalWidth()).isEqualTo(2)
		// Thumbs up with a skin tone modifier.
		assertThat("👍🏽".terminalWidth()).isEqualTo(2)
		// Flag: regional indicators U and S, then two flags back to back.
		assertThat("🇺🇸".terminalWidth()).isEqualTo(2)
		assertThat("🇺🇸🇬🇧".terminalWidth()).isEqualTo(4)
	}

	@Test fun cellsGroupClusters() {
		val cells = mutableListOf<String>()
		val text = "á❤️中"
		text.forEachTerminalCell { start, end, _, width -> cells += text.substring(start, end) + ":" + width }
		assertThat(cells).containsExactly("á:1", "❤️:2", "中:2")
	}

	@Test fun leadingZeroWidthIsDropped() {
		assertThat("́a".terminalWidth()).isEqualTo(1)
	}
}
