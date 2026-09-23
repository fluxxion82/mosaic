package com.jakewharton.mosaic.html

import com.jakewharton.mosaic.text.forEachTerminalCell

private const val ESC = '\u001B'
private const val BEL = '\u0007'

/**
 * Convert a single line of ANSI-styled text (as produced by `TextCanvas.appendRowTo`) into HTML.
 * Only SGR (`ESC [ … m`) sequences are interpreted. Other CSI and OSC sequences are dropped.
 * Text is HTML-escaped. Colors from the 16-color palette reference `--mosaic-ansi-N` CSS variables
 * so the page can theme them.
 */
public fun ansiToHtml(input: CharSequence): String {
	val out = StringBuilder(input.length * 2)
	val style = SgrStyle()
	var openCss: String? = null
	val text = StringBuilder()
	var linkOpen = false

	fun flush() {
		if (text.isEmpty()) return
		if (openCss == null) {
			out.append(text)
		} else {
			out.append("<span style=\"").append(openCss).append("\">").append(text).append("</span>")
		}
		text.clear()
	}

	var i = 0
	while (i < input.length) {
		val c = input[i]
		if (c == ESC && i + 1 < input.length) {
			when (input[i + 1]) {
				'[' -> {
					var end = i + 2
					while (end < input.length && input[end].code !in 0x40..0x7E) end++
					if (end >= input.length) break
					if (input[end] == 'm') {
						style.apply(input.subSequence(i + 2, end))
						val css = style.css()
						if (css != openCss) {
							flush()
							openCss = css
						}
					}
					i = end + 1
					continue
				}

				']' -> {
					var end = i + 2
					var payloadEnd = input.length
					while (end < input.length) {
						if (input[end] == BEL) {
							payloadEnd = end
							end++
							break
						}
						if (input[end] == ESC && end + 1 < input.length && input[end + 1] == '\\') {
							payloadEnd = end
							end += 2
							break
						}
						end++
					}
					val payload = input.subSequence(i + 2, payloadEnd)
					// OSC 8 hyperlink: "8;params;uri". An empty uri closes the link.
					if (payload.startsWith("8;")) {
						val uri = payload.substring(payload.indexOf(';', 2) + 1)
						flush()
						if (linkOpen) {
							out.append("</a>")
							linkOpen = false
						}
						val href = safeHref(uri)
						if (href != null) {
							out.append("<a href=\"").append(href.escapeHtml()).append('"')
							if (href.isExternal()) {
								out.append(" target=\"_blank\" rel=\"noopener noreferrer\"")
							}
							out.append('>')
							linkOpen = true
						}
					}
					i = end
					continue
				}
			}
			i++
			continue
		}
		// Text runs until the next escape sequence.
		var runEnd = i + 1
		while (runEnd < input.length && input[runEnd] != ESC) runEnd++
		val run = input.subSequence(i, runEnd)
		// Box drawing and block characters are decoration (borders, bars); hide them from screen
		// readers, which would otherwise read each one aloud.
		var decorative = false
		run.forEachTerminalCell { cellStart, cellEnd, codePoint, width ->
			val isDecorative = codePoint in 0x2500..0x259F
			if (isDecorative != decorative) {
				text.append(if (isDecorative) "<span aria-hidden=\"true\">" else "</span>")
				decorative = isDecorative
			}
			// Pin wide characters to two cells, since fonts rarely draw them exactly 2ch wide.
			if (width == 2) text.append(WideCellOpen)
			for (index in cellStart until cellEnd) {
				text.appendEscaped(run[index])
			}
			if (width == 2) text.append("</span>")
		}
		if (decorative) text.append("</span>")
		i = runEnd
		continue
	}
	flush()
	if (linkOpen) out.append("</a>")
	return out.toString()
}

private class SgrStyle {
	var foreground: String? = null
	var background: String? = null
	var bold = false
	var dim = false
	var italic = false
	var underline = false
	var inverse = false
	var strikethrough = false

	fun reset() {
		foreground = null
		background = null
		bold = false
		dim = false
		italic = false
		underline = false
		inverse = false
		strikethrough = false
	}

	fun apply(params: CharSequence) {
		if (params.isEmpty()) {
			reset()
			return
		}
		val parts = params.split(';')
		var index = 0
		while (index < parts.size) {
			val part = parts[index]
			// Colon sub-parameters, e.g. "4:3" (curly underline) or "38:2::r:g:b".
			val sub = part.split(':')
			val code = sub[0].toIntOrNull() ?: 0
			when (code) {
				0 -> reset()

				1 -> bold = true

				2 -> dim = true

				3 -> italic = true

				4 -> underline = sub.getOrNull(1) != "0"

				7 -> inverse = true

				9 -> strikethrough = true

				21 -> underline = true

				22 -> {
					bold = false
					dim = false
				}

				23 -> italic = false

				24 -> underline = false

				27 -> inverse = false

				29 -> strikethrough = false

				in 30..37 -> foreground = paletteColor(code - 30)

				39 -> foreground = null

				in 40..47 -> background = paletteColor(code - 40)

				49 -> background = null

				in 90..97 -> foreground = paletteColor(code - 90 + 8)

				in 100..107 -> background = paletteColor(code - 100 + 8)

				38, 48, 58 -> {
					val color: String?
					if (sub.size > 1) {
						color = extendedColor(sub.drop(1).filter { it.isNotEmpty() })
					} else {
						val mode = parts.getOrNull(index + 1)
						val count = when (mode) {
							"5" -> 2
							"2" -> 4
							else -> 1
						}
						color = extendedColor(parts.subList(index + 1, minOf(parts.size, index + 1 + count)))
						index += count
					}
					when (code) {
						38 -> foreground = color
						48 -> background = color
						// 58 (underline color) is ignored.
					}
				}
			}
			index++
		}
	}

	fun css(): String? {
		var fg = foreground
		var bg = background
		if (inverse) {
			fg = background ?: "var(--mosaic-bg,#000)"
			bg = foreground ?: "var(--mosaic-fg,#ccc)"
		}
		val decorations = buildList {
			if (underline) add("underline")
			if (strikethrough) add("line-through")
		}
		if (fg == null && bg == null && !bold && !dim && !italic && decorations.isEmpty()) return null
		return buildString {
			if (fg != null) append("color:").append(fg).append(';')
			if (bg != null) append("background-color:").append(bg).append(';')
			if (bold) append("font-weight:bold;")
			if (dim) append("opacity:.6;")
			if (italic) append("font-style:italic;")
			if (decorations.isNotEmpty()) append("text-decoration:").append(decorations.joinToString(" ")).append(';')
		}
	}
}

private val defaultPalette = arrayOf(
	"#000000", "#cd3131", "#0dbc79", "#e5e510", "#2472c8", "#bc3fbc", "#11a8cd", "#e5e5e5",
	"#666666", "#f14c4c", "#23d18b", "#f5f543", "#3b8eea", "#d670d6", "#29b8db", "#ffffff",
)

private fun paletteColor(index: Int) = "var(--mosaic-ansi-$index,${defaultPalette[index]})"

private fun extendedColor(args: List<String>): String? {
	return when (args.firstOrNull()) {
		"5" -> args.getOrNull(1)?.toIntOrNull()?.let(::color256)

		"2" -> {
			// Allow an optional color-space id: "2;r;g;b" or "2;id;r;g;b".
			val rgb = args.drop(1).takeLast(3).map { it.toIntOrNull() ?: return null }
			if (rgb.size != 3) null else hex(rgb[0], rgb[1], rgb[2])
		}

		else -> null
	}
}

private fun color256(n: Int): String? = when (n) {
	in 0..15 -> paletteColor(n)

	in 16..231 -> {
		val i = n - 16
		val levels = intArrayOf(0, 95, 135, 175, 215, 255)
		hex(levels[i / 36], levels[(i / 6) % 6], levels[i % 6])
	}

	in 232..255 -> (8 + (n - 232) * 10).let { hex(it, it, it) }

	else -> null
}

private fun hex(r: Int, g: Int, b: Int): String {
	fun h(v: Int) = v.coerceIn(0, 255).toString(16).padStart(2, '0')
	return "#" + h(r) + h(g) + h(b)
}

/**
 * Allow only link targets that cannot run script: http(s), mailto, and scheme-less references
 * (relative paths, fragments, and protocol-relative `//host` URLs, which use the page's scheme).
 * Returns null for anything else, including an empty uri.
 */
public fun safeHref(uri: String): String? {
	if (uri.isEmpty()) return null
	val colon = uri.indexOf(':')
	val delimiter = uri.indexOfAny(charArrayOf('/', '?', '#'))
	// A colon before any path, query, or fragment delimiter introduces a scheme.
	if (colon == -1 || (delimiter != -1 && delimiter < colon)) return uri
	return when (uri.substring(0, colon).lowercase()) {
		"http", "https", "mailto" -> uri
		else -> null
	}
}

private fun String.escapeHtml(): String = buildString(length) {
	for (c in this@escapeHtml) {
		when (c) {
			'&' -> append("&amp;")
			'<' -> append("&lt;")
			'>' -> append("&gt;")
			'"' -> append("&quot;")
			else -> append(c)
		}
	}
}

private fun String.isExternal(): Boolean {
	val lower = lowercase()
	return lower.startsWith("http://") || lower.startsWith("https://") || startsWith("//") || startsWith("\\\\")
}

private const val WideCellOpen = "<span style=\"display:inline-block;width:2ch;overflow:hidden;vertical-align:top\">"

/** Append [c] escaped for HTML text, dropping C0 controls (CR, LF, BEL, …) and DEL. */
private fun StringBuilder.appendEscaped(c: Char) {
	when (c) {
		in '\u0000'..'\u001F', '\u007F' -> {}
		'&' -> append("&amp;")
		'<' -> append("&lt;")
		'>' -> append("&gt;")
		'"' -> append("&quot;")
		else -> append(c)
	}
}
