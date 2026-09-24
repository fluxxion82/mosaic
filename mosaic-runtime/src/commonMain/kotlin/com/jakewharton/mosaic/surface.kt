package com.jakewharton.mosaic

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.TextStyle.Companion.Bold
import com.jakewharton.mosaic.ui.TextStyle.Companion.Dim
import com.jakewharton.mosaic.ui.TextStyle.Companion.Invert
import com.jakewharton.mosaic.ui.TextStyle.Companion.Italic
import com.jakewharton.mosaic.ui.TextStyle.Companion.Strikethrough
import com.jakewharton.mosaic.ui.UnderlineStyle
import com.jakewharton.mosaic.ui.isEmptyTextStyle
import com.jakewharton.mosaic.ui.isNotEmptyTextStyle
import com.jakewharton.mosaic.ui.isSpecifiedColor
import com.jakewharton.mosaic.ui.isSpecifiedUnderlineStyle
import com.jakewharton.mosaic.ui.isUnspecifiedColor
import com.jakewharton.mosaic.ui.isUnspecifiedUnderlineStyle
import de.cketti.codepoints.appendCodePoint

private val blankPixel = TextPixel(SpaceCharCodePoint)

public interface TextCanvas {
	public val height: Int
	public val width: Int

	// TODO Hey! These don't go here...
	public fun render(ansiLevel: AnsiLevel, supportsKittyUnderlines: Boolean): String
	public fun appendRowTo(appendable: Appendable, row: Int, ansiLevel: AnsiLevel, supportsKittyUnderlines: Boolean)
}

internal class TextSurface(
	override val width: Int,
	override val height: Int,
) : TextCanvas {
	var translationX = 0
	var translationY = 0

	private val cells = Array(width * height) { TextPixel(SpaceCharCodePoint) }

	operator fun get(row: Int, column: Int): TextPixel {
		val x = translationX + column
		val y = row + translationY
		check(x in 0 until width)
		check(y in 0 until height)
		return cells[y * width + x]
	}

	/** Whether the cell at [row], [column] (relative to the current translation) is on the canvas. */
	fun contains(row: Int, column: Int): Boolean {
		return translationX + column in 0 until width && row + translationY in 0 until height
	}

	/**
	 * Prepare the cell at [row], [column] to receive a new code point by breaking up any wide
	 * character it belongs to, so no half of one is left behind.
	 */
	fun detachWideCharacter(row: Int, column: Int) {
		val x = translationX + column
		val y = row + translationY
		if (x !in 0 until width || y !in 0 until height) return
		val index = y * width + x
		if (cells[index].codePoint == WideContinuationCodePoint && x > 0) {
			cells[index - 1].codePoint = SpaceCharCodePoint
		}
		if (x + 1 < width && cells[index + 1].codePoint == WideContinuationCodePoint) {
			cells[index + 1].codePoint = SpaceCharCodePoint
		}
	}

	override fun appendRowTo(appendable: Appendable, row: Int, ansiLevel: AnsiLevel, supportsKittyUnderlines: Boolean) {
		// Reused heap allocation for building ANSI attributes inside the loop.
		val attributes = mutableListOf<String>()

		val rowStart = row * width
		var rowStop = rowStart + width

		while (rowStop > rowStart) {
			val lastIndex = rowStop - 1
			val pixel = cells[lastIndex]
			if (pixel.isEmpty()) {
				rowStop = lastIndex
			} else {
				break
			}
		}

		var lastPixel = blankPixel
		for (columnIndex in rowStart until rowStop) {
			val pixel = cells[columnIndex]

			if (ansiLevel != AnsiLevel.NONE) {
				if (pixel.link != lastPixel.link) {
					appendable.appendHyperlink(pixel.link)
				}
				if (pixel.foreground != lastPixel.foreground) {
					attributes.addColor(
						pixel.foreground,
						ansiLevel,
						ansiFgColorSelector,
						ansiFgColorReset,
						ansiFgColorOffset,
					)
				}
				if (pixel.background != lastPixel.background) {
					attributes.addColor(
						pixel.background,
						ansiLevel,
						ansiBgColorSelector,
						ansiBgColorReset,
						ansiBgColorOffset,
					)
				}

				fun maybeToggleStyle(style: TextStyle, on: String, off: String) {
					if (style in pixel.textStyle) {
						if (style !in lastPixel.textStyle) {
							attributes += on
						}
					} else if (style in lastPixel.textStyle) {
						attributes += off
					}
				}
				if (pixel.textStyle != lastPixel.textStyle) {
					maybeToggleStyle(Bold, "1", "22")
					maybeToggleStyle(Dim, "2", "22")
					maybeToggleStyle(Italic, "3", "23")
					maybeToggleStyle(Invert, "7", "27")
					maybeToggleStyle(Strikethrough, "9", "29")
				}
				if (pixel.underlineStyle != lastPixel.underlineStyle) {
					attributes += when (pixel.underlineStyle) {
						UnderlineStyle.Unspecified, UnderlineStyle.None -> "24"
						UnderlineStyle.Double if (supportsKittyUnderlines) -> "4:2"
						UnderlineStyle.Curly if (supportsKittyUnderlines) -> "4:3"
						UnderlineStyle.Dotted if (supportsKittyUnderlines) -> "4:4"
						UnderlineStyle.Dashed if (supportsKittyUnderlines) -> "4:5"
						else -> "4"
					}
				}
				if (pixel.underlineColor != lastPixel.underlineColor) {
					attributes.addColor(
						pixel.underlineColor,
						ansiLevel,
						ansiUnderlineColorSelector,
						ansiUnderlineColorReset,
						ansiUnderlineColorOffset,
					)
				}
				if (attributes.isNotEmpty()) {
					appendable.append(CSI)
					attributes.forEachIndexed { index, element ->
						if (index > 0) {
							appendable.append(ansiSeparator)
						}
						appendable.append(element)
					}
					appendable.append(ansiClosingCharacter)
					attributes.clear() // This list is reused!
				}
			}

			if (pixel.codePoint != WideContinuationCodePoint) {
				appendable.appendCodePoint(pixel.codePoint)
				pixel.combining?.let(appendable::append)
			} else if (columnIndex == rowStart || lastPixel.codePoint == WideContinuationCodePoint) {
				// A continuation without its wide character would shift the rest of the row left.
				appendable.append(' ')
			}
			lastPixel = pixel
		}

		if (ansiLevel != AnsiLevel.NONE && lastPixel.link != null) {
			appendable.appendHyperlink(null)
		}
		if (
			ansiLevel != AnsiLevel.NONE &&
			(
				lastPixel.background.isSpecifiedColor ||
					lastPixel.foreground.isSpecifiedColor ||
					lastPixel.textStyle.isNotEmptyTextStyle ||
					lastPixel.underlineColor.isSpecifiedColor ||
					lastPixel.underlineStyle.isSpecifiedUnderlineStyle
				)
		) {
			appendable.append(ansiReset)
			appendable.append(ansiClosingCharacter)
		}
	}

	/** Open an OSC 8 hyperlink to [url], or close the current one when null. */
	private fun Appendable.appendHyperlink(url: String?) {
		append("\u001B]8;;")
		if (url != null) {
			// OSC 8 URIs are limited to printable ASCII. Percent-encode everything else as UTF-8,
			// which also keeps control characters from terminating the sequence early.
			for (byte in url.encodeToByteArray()) {
				val code = byte.toInt() and 0xFF
				if (code in 0x21..0x7E) {
					append(code.toChar())
				} else {
					append('%')
					append(HexDigits[code shr 4])
					append(HexDigits[code and 0xF])
				}
			}
		}
		append("\u001B\\")
	}

	private fun MutableList<String>.addColor(
		color: Color,
		ansiLevel: AnsiLevel,
		select: Int,
		reset: Int,
		offset: Int,
	) {
		if (color.isUnspecifiedColor) {
			add(reset.toString())
			return
		}
		when (ansiLevel) {
			AnsiLevel.NONE -> add(reset.toString())

			AnsiLevel.ANSI16 -> {
				val ansi16Code = color.toAnsi16Code()
				if (ansi16Code == ansiFgColorReset || ansi16Code == ansiBgColorReset) {
					add(reset.toString())
				} else {
					add((ansi16Code + offset).toString())
				}
			}

			AnsiLevel.ANSI256 -> {
				add(select.toString())
				add(ansiSelectorColor256)
				add(color.toAnsi256Code().toString())
			}

			AnsiLevel.TRUECOLOR -> {
				add(select.toString())
				add(ansiSelectorColorRgb)
				add(color.redInt.toString())
				add(color.greenInt.toString())
				add(color.blueInt.toString())
			}
		}
	}

	override fun render(ansiLevel: AnsiLevel, supportsKittyUnderlines: Boolean): String = buildString {
		if (height > 0) {
			for (rowIndex in 0 until height) {
				appendRowTo(this, rowIndex, ansiLevel, supportsKittyUnderlines)
				append("\n")
			}
			// Remove trailing newline.
			setLength(length - 1)
		}
	}
}

internal class TextPixel(codePoint: Int) {
	var codePoint: Int = codePoint
		set(value) {
			field = value
			combining = null
		}

	/** Zero-width code points (combining marks, joiners, variation selectors) drawn with this one. */
	var combining: String? = null
	var background: Color = Color.Unspecified
	var foreground: Color = Color.Unspecified
	var textStyle: TextStyle = TextStyle.Empty
	var underlineStyle: UnderlineStyle = UnderlineStyle.Unspecified
	var underlineColor: Color = Color.Unspecified
	var link: String? = null

	fun isEmpty(): Boolean {
		return codePoint == SpaceCharCodePoint &&
			background.isUnspecifiedColor &&
			foreground.isUnspecifiedColor &&
			textStyle.isEmptyTextStyle &&
			underlineStyle.isUnspecifiedUnderlineStyle &&
			underlineColor.isUnspecifiedColor &&
			link == null &&
			combining == null
	}

	override fun toString() = buildString {
		append("TextPixel(\"")
		if (codePoint == WideContinuationCodePoint) {
			append("<wide>")
		} else {
			appendCodePoint(codePoint)
			combining?.let(::append)
		}
		append("\"")
		if (background.isSpecifiedColor) {
			append(" bg=")
			append(background)
		}
		if (foreground.isSpecifiedColor) {
			append(" fg=")
			append(foreground)
		}
		// TODO style
		append(')')
	}
}

private const val HexDigits = "0123456789ABCDEF"
