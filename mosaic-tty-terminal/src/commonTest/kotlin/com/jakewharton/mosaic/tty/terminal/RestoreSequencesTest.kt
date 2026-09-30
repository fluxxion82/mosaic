package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class RestoreSequencesTest {
	private val all = restoreSequences(
		synchronizedOutput = true,
		alternateScreen = true,
		cursor = true,
		systemTheme = true,
		inBandResize = true,
		focus = true,
	)

	@Test fun essentialSequencesComeFirstAndNoneIsDestructive() {
		assertThat(all).containsExactly(
			synchronizedOutputDisable,
			alternateScreenDisable,
			cursorEnable,
			systemThemeDisable,
			inBandResizeDisable,
			focusDisable,
		)
		for (sequence in all) {
			assertThat(sequence).doesNotContain(clearScreen)
		}
	}

	@Test fun onlyTheTogglesInEffectAreRestored() {
		val sequences = restoreSequences(
			synchronizedOutput = false,
			alternateScreen = false,
			cursor = true,
			systemTheme = false,
			inBandResize = false,
			focus = true,
		)
		assertThat(sequences).containsExactly(cursorEnable, focusDisable)
	}

	@Test fun normalRestoreWritesEverythingFully() {
		val written = StringBuilder()
		writeRestoreSequences(all, emergency = false, budget = 1.seconds, writeFully = { written.append(it.decodeToString()) }, writeWithin = { _, _ -> error("unused") })
		assertThat(written.toString()).isEqualTo(all.joinToString(""))
	}

	@Test fun emergencyRestoreGetsTheEssentialsThroughATtyWhichAcceptsLittle() {
		// A TTY which only ever accepts as many bytes as the first two sequences need.
		val accepted = StringBuilder()
		var capacity = synchronizedOutputDisable.length + alternateScreenDisable.length
		writeRestoreSequences(
			sequences = all,
			emergency = true,
			budget = 1.seconds,
			writeFully = { error("must not block") },
			writeWithin = { bytes, _ ->
				val count = minOf(bytes.size, capacity)
				capacity -= count
				accepted.append(bytes.decodeToString(0, count))
				count
			},
		)
		assertThat(accepted.toString()).isEqualTo(synchronizedOutputDisable + alternateScreenDisable)
	}

	@Test fun emergencyRestoreKeepsGoingAfterAPartiallyAcceptedSequence() {
		// The cursor sequence is cut short; the shorter ones after it still get their chance.
		val calls = mutableListOf<String>()
		var remaining = synchronizedOutputDisable.length + alternateScreenDisable.length + 2
		writeRestoreSequences(
			sequences = all,
			emergency = true,
			budget = 1.seconds,
			writeFully = { error("must not block") },
			writeWithin = { bytes, _ ->
				val count = minOf(bytes.size, remaining)
				remaining -= count
				calls += bytes.decodeToString(0, count)
				count
			},
		)
		assertThat(calls.size).isEqualTo(all.size)
		assertThat(calls[0]).isEqualTo(synchronizedOutputDisable)
		assertThat(calls[1]).isEqualTo(alternateScreenDisable)
		assertThat(calls[2]).isEqualTo(cursorEnable.substring(0, 2))
	}
}
