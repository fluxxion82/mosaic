@file:Suppress("NOTHING_TO_INLINE")

package com.jakewharton.mosaic

import androidx.compose.runtime.Stable

internal const val SpaceCharCodePoint = ' '.code

/**
 * Unicode code point cannot contain a negative value.
 */
internal const val UnspecifiedCodePoint: Int = -1

/**
 * `true` when this is [UnspecifiedCodePoint].
 */
@Stable
internal inline val Int.isUnspecifiedCodePoint: Boolean get() = this == UnspecifiedCodePoint

/**
 * `false` when this is [UnspecifiedCodePoint].
 */
@Stable
internal inline val Int.isSpecifiedCodePoint: Boolean get() = this != UnspecifiedCodePoint

/**
 * Marks the second cell of a wide character. It renders as nothing, since the terminal advances
 * two columns when printing the character in the first cell.
 */
internal const val WideContinuationCodePoint: Int = -2
