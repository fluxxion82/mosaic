package com.jakewharton.mosaic.html

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class AnsiHtmlTest {
	@Test fun plainTextIsEscaped() {
		assertThat(ansiToHtml("<a & \"b\">")).isEqualTo("&lt;a &amp; &quot;b&quot;&gt;")
	}

	@Test fun trueColorForegroundAndReset() {
		assertThat(ansiToHtml("\u001B[38;2;255;0;16mred\u001B[0m plain"))
			.isEqualTo("<span style=\"color:#ff0010;\">red</span> plain")
	}

	@Test fun colonSeparatedTrueColor() {
		assertThat(ansiToHtml("\u001B[48:2::0:128:255mx"))
			.isEqualTo("<span style=\"background-color:#0080ff;\">x</span>")
	}

	@Test fun paletteColorUsesCssVariable() {
		assertThat(ansiToHtml("\u001B[91mx"))
			.isEqualTo("<span style=\"color:var(--mosaic-ansi-9,#f14c4c);\">x</span>")
	}

	@Test fun color256Cube() {
		assertThat(ansiToHtml("\u001B[38;5;196mx"))
			.isEqualTo("<span style=\"color:#ff0000;\">x</span>")
	}

	@Test fun attributesCombineAndClearIndividually() {
		assertThat(ansiToHtml("\u001B[1;3;4ma\u001B[22mb"))
			.isEqualTo(
				"<span style=\"font-weight:bold;font-style:italic;text-decoration:underline;\">a</span>" +
					"<span style=\"font-style:italic;text-decoration:underline;\">b</span>",
			)
	}

	@Test fun inverseWithoutColorsUsesThemeVariables() {
		assertThat(ansiToHtml("\u001B[7mx"))
			.isEqualTo("<span style=\"color:var(--mosaic-bg,#000);background-color:var(--mosaic-fg,#ccc);\">x</span>")
	}

	@Test fun redundantSequencesDoNotSplitSpans() {
		assertThat(ansiToHtml("\u001B[1ma\u001B[1mb")).isEqualTo("<span style=\"font-weight:bold;\">ab</span>")
	}

	@Test fun nonSgrSequencesAreDropped() {
		assertThat(ansiToHtml("a\u001B[2Kb\u001B]0;title\u001B\\c\u0007d")).isEqualTo("abcd")
	}

	@Test fun truncatedSequenceIsDropped() {
		assertThat(ansiToHtml("a\u001B[38;2")).isEqualTo("a")
	}

	@Test fun osc8BecomesAnchor() {
		assertThat(ansiToHtml("a \u001B]8;;#home\u001B\\home\u001B]8;;\u001B\\ b"))
			.isEqualTo("a <a href=\"#home\">home</a> b")
	}

	@Test fun externalLinksOpenInNewTab() {
		assertThat(ansiToHtml("\u001B]8;;https://example.com/?a=1&b=2\u0007x\u001B]8;;\u0007"))
			.isEqualTo(
				"<a href=\"https://example.com/?a=1&amp;b=2\" target=\"_blank\" rel=\"noopener noreferrer\">x</a>",
			)
	}

	@Test fun stylesNestInsideAnchor() {
		assertThat(ansiToHtml("\u001B]8;;#a\u001B\\\u001B[1mx\u001B[0m\u001B]8;;\u001B\\"))
			.isEqualTo("<a href=\"#a\"><span style=\"font-weight:bold;\">x</span></a>")
	}

	@Test fun unterminatedLinkIsClosed() {
		assertThat(ansiToHtml("\u001B]8;;#a\u001B\\x")).isEqualTo("<a href=\"#a\">x</a>")
	}

	@Test fun scriptLinksAreNotRendered() {
		assertThat(ansiToHtml("\u001B]8;;javascript:alert(1)\u001B\\x\u001B]8;;\u001B\\")).isEqualTo("x")
		assertThat(safeHref("JavaScript:alert(1)")).isNull()
		assertThat(safeHref("data:text/html,x")).isNull()
		assertThat(safeHref("mailto:a@b.c")).isEqualTo("mailto:a@b.c")
		assertThat(safeHref("/path:with/colon")).isEqualTo("/path:with/colon")
		assertThat(safeHref("#frag:ment")).isEqualTo("#frag:ment")
	}

	@Test fun externalCheckIgnoresSchemeCase() {
		assertThat(ansiToHtml("\u001B]8;;HTTPS://example.com\u0007x\u001B]8;;\u0007"))
			.isEqualTo("<a href=\"HTTPS://example.com\" target=\"_blank\" rel=\"noopener noreferrer\">x</a>")
	}

	@Test fun protocolRelativeIsExternal() {
		assertThat(ansiToHtml("\u001B]8;;//example.com\u0007x\u001B]8;;\u0007"))
			.isEqualTo("<a href=\"//example.com\" target=\"_blank\" rel=\"noopener noreferrer\">x</a>")
	}

	@Test fun wideCharactersArePinnedToTwoCells() {
		val wide = "<span style=\"display:inline-block;width:2ch;overflow:hidden;vertical-align:top\">"
		assertThat(ansiToHtml("a中😀é"))
			.isEqualTo("a${wide}中</span>$wide😀</span>é")
	}

	@Test fun emojiSequencesArePinnedAsOneCell() {
		val wide = "<span style=\"display:inline-block;width:2ch;overflow:hidden;vertical-align:top\">"
		assertThat(ansiToHtml("❤️🇺🇸"))
			.isEqualTo("$wide❤️</span>$wide🇺🇸</span>")
	}

	@Test fun boxDrawingIsHiddenFromScreenReaders() {
		assertThat(ansiToHtml("│ hi │"))
			.isEqualTo("<span aria-hidden=\"true\">│</span> hi <span aria-hidden=\"true\">│</span>")
	}

	@Test fun decorativeRunsShareOneSpanAndCloseBeforeStyleChanges() {
		assertThat(ansiToHtml("┌──\u001B[1mT\u001B[0m─┐"))
			.isEqualTo(
				"<span aria-hidden=\"true\">┌──</span><span style=\"font-weight:bold;\">T</span>" +
					"<span aria-hidden=\"true\">─┐</span>",
			)
	}

	@Test fun blockElementsAreDecorative() {
		assertThat(ansiToHtml("[██░] 2 MB"))
			.isEqualTo("[<span aria-hidden=\"true\">██░</span>] 2 MB")
	}
}
