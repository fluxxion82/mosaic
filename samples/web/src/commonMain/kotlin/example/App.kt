package example

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.DrawStyle
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.offset
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withLink
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private val tabs = listOf("counter", "robot", "colors", "input")

/**
 * @param route The selected tab's name, e.g. from the URL fragment. Unknown names show the first.
 * @param navigate Called with a tab name to select it.
 */
@Composable
fun App(route: String, navigate: (String) -> Unit) {
	val tab = tabs.indexOf(route).coerceAtLeast(0)
	fun select(index: Int) = navigate(tabs[(index + tabs.size) % tabs.size])
	val size = LocalTerminalState.current.size

	Column(
		modifier = Modifier.onKeyEvent {
			when (it) {
				KeyEvent("]") -> select(tab + 1)
				KeyEvent("[") -> select(tab - 1)
				KeyEvent("1") -> select(0)
				KeyEvent("2") -> select(1)
				KeyEvent("3") -> select(2)
				KeyEvent("4") -> select(3)
				else -> return@onKeyEvent false
			}
			true
		},
	) {
		Text("mosaic://web", textStyle = TextStyle.Bold, color = rgb(0x3B8EEA))
		Spacer(Modifier.height(1))
		Row {
			tabs.forEachIndexed { index, name ->
				val label = buildAnnotatedString {
					withLink("#$name") { append(" ${index + 1}:$name ") }
				}
				Text(label, textStyle = if (index == tab) TextStyle.Invert else TextStyle.Dim)
				Text(" ")
			}
		}
		Spacer(Modifier.height(1))
		when (tab) {
			0 -> Counter()
			1 -> Robot()
			2 -> Colors()
			3 -> LineEditor()
		}
		Spacer(Modifier.height(1))
		Text(
			buildAnnotatedString {
				append("click, [ ], or 1-4 to switch · terminal ${size.columns}x${size.rows} · ")
				withLink("https://github.com/fluxxion82/mosaic/tree/wasm-js") { append("source") }
			},
			textStyle = TextStyle.Dim,
		)
	}
}

@Composable
private fun Counter() {
	var count by remember { mutableIntStateOf(0) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(250.milliseconds)
			count++
		}
	}
	val spinner = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
	Text("${spinner[count % spinner.length]} The count is: $count")
}

private const val worldWidth = 30
private const val worldHeight = 8

@Composable
private fun Robot() {
	var x by remember { mutableIntStateOf(0) }
	var y by remember { mutableIntStateOf(0) }
	Column(
		modifier = Modifier.onKeyEvent {
			when (it) {
				KeyEvent("ArrowUp") -> y = (y - 1).coerceAtLeast(0)
				KeyEvent("ArrowDown") -> y = (y + 1).coerceAtMost(worldHeight - 1)
				KeyEvent("ArrowLeft") -> x = (x - 1).coerceAtLeast(0)
				KeyEvent("ArrowRight") -> x = (x + 1).coerceAtMost(worldWidth - 3)
				else -> return@onKeyEvent false
			}
			true
		},
	) {
		Text("Arrow keys move the robot. Position: $x, $y")
		Box(
			modifier = Modifier
				.drawBehind { drawRect('·', drawStyle = DrawStyle.Stroke(1)) }
				.padding(1)
				.size(worldWidth, worldHeight),
		) {
			Text("^_^", color = rgb(0x23D18B), modifier = Modifier.offset { IntOffset(x, y) })
		}
	}
}

@Composable
private fun Colors() {
	Column {
		Row {
			for (color in listOf(0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD)) {
				Text("  ", background = rgb(color))
			}
		}
		Row {
			for (i in 0 until 48) {
				Text(" ", background = Color(i * 255 / 47, 64, 255 - i * 255 / 47))
			}
		}
		Spacer(Modifier.height(1))
		Row {
			Text("bold", textStyle = TextStyle.Bold)
			Text(" ")
			Text("dim", textStyle = TextStyle.Dim)
			Text(" ")
			Text("italic", textStyle = TextStyle.Italic)
			Text(" ")
			Text("strike", textStyle = TextStyle.Strikethrough)
			Text(" ")
			Text("invert", textStyle = TextStyle.Invert)
			Text(" ")
			Text("<escaped & html>")
		}
		Spacer(Modifier.height(1))
		Box(Modifier.drawBehind { drawRect('·', drawStyle = DrawStyle.Stroke(1)) }.padding(1)) {
			Column {
				Text("中文 😀 wide")
				Text("\u2764\uFE0F\uD83C\uDDFA\uD83C\uDDF8\uD83D\uDC4D\uD83C\uDFFD seq")
				Text("abcde narrow")
				Text("cafe\u0301 combining")
			}
		}
	}
}

@Composable
private fun LineEditor() {
	var line by remember { mutableStateOf("") }
	val history = remember { mutableStateListOf<String>() }
	Column(
		modifier = Modifier.onKeyEvent {
			when {
				it == KeyEvent("Enter") -> {
					history += line
					line = ""
				}

				it == KeyEvent("Backspace") -> line = line.dropLastCodepoint()

				// Leave digits and brackets to tab switching while the line is empty.
				line.isEmpty() && (it.key in "1234[]") -> return@onKeyEvent false

				// Named keys like "F1" or "ArrowUp" are longer than a single character.
				!it.ctrl && !it.alt && it.key.isSingleCharacter() -> line += it.key

				else -> return@onKeyEvent false
			}
			true
		},
	) {
		Text("Type, paste, or use an IME. Enter submits.")
		for (entry in history.takeLast(5)) {
			Text("  $entry", textStyle = TextStyle.Dim)
		}
		Text("> $line█")
	}
}

private fun String.isSingleCharacter() = length == 1 || (length == 2 && this[0].isHighSurrogate())

private fun String.dropLastCodepoint(): String {
	if (isEmpty()) return this
	val cut = if (length >= 2 && this[lastIndex].isLowSurrogate()) 2 else 1
	return dropLast(cut)
}

private fun rgb(hex: Int) = Color(hex shr 16 and 0xFF, hex shr 8 and 0xFF, hex and 0xFF)
