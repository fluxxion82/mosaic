package com.jakewharton.mosaic.text

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.NodeSnapshots
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class LinkTest {
	private fun open(url: String) = "\u001B]8;;$url\u001B\\"
	private val close = "\u001B]8;;\u001B\\"

	@Test fun linkIsWrappedInOsc8() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Text(
					buildAnnotatedString {
						append("a ")
						withLink("https://example.com") { append("go") }
						append(" b")
					},
				)
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.TRUECOLOR, false))
				.isEqualTo("a ${open("https://example.com")}go$close b")
		}
	}

	@Test fun linkAtEndOfRowIsClosed() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Text(buildAnnotatedString { withLink("#home") { append("home") } })
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.TRUECOLOR, false))
				.isEqualTo("${open("#home")}home$close")
		}
	}

	@Test fun adjacentLinksSwitchWithoutClosing() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Text(
					buildAnnotatedString {
						withLink("#a") { append("a") }
						withLink("#b") { append("b") }
					},
				)
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.TRUECOLOR, false))
				.isEqualTo("${open("#a")}a${open("#b")}b$close")
		}
	}

	@Test fun noEscapesWithoutAnsi() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Text(buildAnnotatedString { withLink("#home") { append("home") } })
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo("home")
		}
	}

	@Test fun nonPrintableUrlCharactersArePercentEncoded() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Text(buildAnnotatedString { withLink("#a\u001B\u0007 é") { append("x") } })
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.TRUECOLOR, false))
				.isEqualTo("${open("#a%1B%07%20%C3%A9")}x$close")
		}
	}

	@Test fun linkMergesWithOtherStyles() {
		val merged = SpanStyle(link = "#a").merge(SpanStyle(link = null))
		assertThat(merged.link).isEqualTo("#a")
		assertThat(SpanStyle(link = "#a").merge(SpanStyle(link = "#b")).link).isEqualTo("#b")
	}
}
