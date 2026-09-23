package com.jakewharton.mosaic.html

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withLink
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlin.test.Test

class RenderHtmlTest {
	@Test fun rowsAndStyles() {
		val html = renderHtml(columns = 20, rows = 5) {
			Column {
				Text("Hello", textStyle = TextStyle.Bold)
				Spacer(Modifier.height(1))
				Text(buildAnnotatedString { withLink("#about") { append("about") } })
			}
		}
		assertThat(html).isEqualTo(
			"<div class=\"mosaic-static\"></div><div class=\"mosaic-live\">" +
				"<div class=\"mosaic-row\"><span style=\"font-weight:bold;\">Hello</span></div>" +
				"<div class=\"mosaic-row\"> </div>" +
				"<div class=\"mosaic-row\"><a href=\"#about\">about</a></div>" +
				"</div>",
		)
	}

	@Test fun terminalSizeIsVisibleToContent() {
		val html = renderHtml(columns = 42, rows = 7) {
			val size = LocalTerminalState.current.size
			Text("${size.columns}x${size.rows}")
		}
		assertThat(html).isEqualTo(
			"<div class=\"mosaic-static\"></div><div class=\"mosaic-live\">" +
				"<div class=\"mosaic-row\">42x7</div></div>",
		)
	}

	@Test fun effectsDoNotRun() {
		val html = renderHtml(columns = 20, rows = 5) {
			var count by remember { mutableIntStateOf(0) }
			LaunchedEffect(Unit) { count = 99 }
			Text("count=$count")
		}
		assertThat(html).isEqualTo(
			"<div class=\"mosaic-static\"></div><div class=\"mosaic-live\">" +
				"<div class=\"mosaic-row\">count=0</div></div>",
		)
	}
}
