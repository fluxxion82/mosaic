@file:JvmName("Prerender")

package example

import com.jakewharton.mosaic.html.renderHtml
import java.io.File

/**
 * Copy the index.html template (first argument) to the output (second argument), filling the empty
 * `<div id="terminal"></div>` with the app's first frame so the page has content before the wasm
 * module loads, and without JavaScript at all.
 */
fun main(args: Array<String>) {
	val (template, output) = args.map(::File)
	val html = renderHtml(columns = 100, rows = 30) {
		App(route = "", navigate = {})
	}
	val source = template.readText()
	val placeholder = Regex("""(<div id="terminal"[^>]*>)(</div>)""").find(source)
		?: error("No empty <div id=\"terminal\"></div> in $template")
	val (open, close) = placeholder.destructured
	output.writeText(source.replaceRange(placeholder.range, open + html + close))
}
