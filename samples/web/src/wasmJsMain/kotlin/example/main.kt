package example

import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.browser.rememberLocationHash
import com.jakewharton.mosaic.browser.renderMosaic
import com.jakewharton.mosaic.browser.setLocationHash
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

fun main() {
	val element = document.getElementById("terminal") as HTMLElement
	renderMosaic(element) {
		val route by rememberLocationHash()
		App(route, ::setLocationHash)
	}
}
