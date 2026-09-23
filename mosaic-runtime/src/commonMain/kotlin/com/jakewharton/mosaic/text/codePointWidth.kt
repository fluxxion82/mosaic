package com.jakewharton.mosaic.text

import de.cketti.codepoints.codePointAt

/**
 * The number of terminal cells [codePoint] occupies on its own: 2 for East Asian wide and
 * fullwidth characters (including emoji with emoji presentation), 0 for combining marks and other
 * characters which attach to the preceding one, and 1 otherwise.
 *
 * Emoji sequences can be wider than their first code point. Use [forEachTerminalCell] or
 * [terminalWidth] to measure text.
 */
public fun codePointWidth(codePoint: Int): Int = when {
	codePoint < 0x300 -> 1
	codePoint in ZeroWidthCodePointRanges -> 0
	codePoint in WideCodePointRanges -> 2
	else -> 1
}

/** The number of terminal cells this text occupies on a single line. */
public fun CharSequence.terminalWidth(): Int {
	var width = 0
	forEachTerminalCell { _, _, _, cellWidth -> width += cellWidth }
	return width
}

/**
 * Split this text into the clusters a terminal draws as one character, calling [block] with each
 * cluster's `[start, end)` char range, its first code point, and its width in cells (1 or 2):
 *
 * - Zero-width code points (combining marks, variation selectors, joiners) extend the cluster.
 * - U+FE0F VARIATION SELECTOR-16 requests emoji presentation, widening a narrow base to 2.
 * - The code point after U+200D ZERO WIDTH JOINER joins the cluster (emoji ZWJ sequences).
 * - Emoji modifiers (skin tones) extend the cluster.
 * - Two regional indicators form one flag of width 2.
 *
 * Zero-width code points at the start of the text have nothing to attach to and are dropped.
 */
public fun CharSequence.forEachTerminalCell(block: (start: Int, end: Int, codePoint: Int, width: Int) -> Unit) {
	var clusterStart = -1
	var clusterCodePoint = 0
	var clusterWidth = 0
	var joinNext = false
	var regionalIndicators = 0

	var index = 0
	while (index < length) {
		val codePoint = codePointAt(index)
		val next = index + if (codePoint >= 0x10000) 2 else 1
		val width = codePointWidth(codePoint)
		val regional = codePoint in 0x1F1E6..0x1F1FF

		val extends = clusterStart != -1 &&
			(width == 0 || joinNext || codePoint in 0x1F3FB..0x1F3FF || (regional && regionalIndicators == 1))
		if (extends) {
			if (codePoint == 0xFE0F) clusterWidth = 2
			if (regional) {
				regionalIndicators++
				clusterWidth = 2
			}
			joinNext = codePoint == 0x200D
		} else {
			if (clusterStart != -1) block(clusterStart, index, clusterCodePoint, clusterWidth)
			if (width == 0) {
				clusterStart = -1
			} else {
				clusterStart = index
				clusterCodePoint = codePoint
				clusterWidth = width
				regionalIndicators = if (regional) 1 else 0
			}
			joinNext = false
		}
		index = next
	}
	if (clusterStart != -1) block(clusterStart, length, clusterCodePoint, clusterWidth)
}

/** Binary search over sorted inclusive `start, end` pairs. */
private operator fun IntArray.contains(codePoint: Int): Boolean {
	var low = 0
	var high = size / 2 - 1
	while (low <= high) {
		val mid = (low + high) ushr 1
		when {
			codePoint < this[mid * 2] -> high = mid - 1
			codePoint > this[mid * 2 + 1] -> low = mid + 1
			else -> return true
		}
	}
	return false
}
