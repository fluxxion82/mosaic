package com.jakewharton.mosaic.ui

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.NodeSnapshots
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.runMosaicTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class TextOverflowTest {
	@Test fun textPastTheCanvasEdgeIsClipped() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Column(modifier = Modifier.width(6).padding(left = 2, right = 2)) {
					Text("abcdefgh")
				}
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo("  abcd")
		}
	}

	@Test fun wideCharacterAtTheCanvasEdgeIsDroppedWithoutCrashing() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Column(modifier = Modifier.width(5).padding(left = 2)) {
					Text("中文字")
				}
			}
			assertThat(awaitSnapshot().draw().render(AnsiLevel.NONE, false)).isEqualTo("  中")
		}
	}
}
