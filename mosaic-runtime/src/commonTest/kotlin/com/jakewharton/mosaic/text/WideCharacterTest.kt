package com.jakewharton.mosaic.text

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.NodeSnapshots
import com.jakewharton.mosaic.layout.DrawStyle
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.offset
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Filler
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class WideCharacterTest {
	@Test fun wideCharactersTakeTwoCells() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Box(Modifier.drawBehind { drawRect('*', drawStyle = DrawStyle.Stroke(1)) }.padding(1)) {
					Text("中文")
				}
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo(
				"""
				|******
				|*中文*
				|******
				""".trimMargin(),
			)
		}
	}

	@Test fun siblingsAlignAfterWideText() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Column {
					Row {
						Text("😀x")
						Text("|")
					}
					Row {
						Text("abc")
						Text("|")
					}
				}
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo(
				"""
				|😀x|
				|abc|
				""".trimMargin(),
			)
		}
	}

	@Test fun combiningMarksAttachToPreviousCharacter() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Row {
					Text("é")
					Text("|")
				}
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo("é|")
		}
	}

	@Test fun styleCoversBothCells() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Text("中", background = Color.Red)
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.TRUECOLOR, false))
				.isEqualTo("\u001B[48;2;255;0;0m中\u001B[0m")
		}
	}

	@Test fun narrowOverLeadCellClearsContinuation() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Box {
					Text("中文")
					Text("x")
				}
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo("x 文")
		}
	}

	@Test fun narrowOverContinuationClearsLead() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Box {
					Text("中文")
					Text("x", modifier = Modifier.offset(1, 0))
				}
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo(" x文")
		}
	}

	@Test fun fillOverWideText() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Box {
					Text("a中b")
					Filler('-', modifier = Modifier.size(2, 1).offset(2, 0))
				}
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo("a --")
		}
	}

	@Test fun emojiSequencesAlign() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Column {
					Row {
						Text("❤️🇺🇸")
						Text("|")
					}
					Row {
						Text("abcd")
						Text("|")
					}
				}
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo(
				"❤️🇺🇸|\nabcd|",
			)
		}
	}

	@Test fun trailingCombiningMarkIsKept() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Text("a ́")
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo("a ́")
		}
	}
}
