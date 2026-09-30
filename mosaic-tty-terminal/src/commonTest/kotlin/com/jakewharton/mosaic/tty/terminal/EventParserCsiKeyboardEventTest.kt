package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.Down
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.End
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.Home
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.KpBegin
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.Left
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierAlt
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierCapsLock
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierCtrl
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierHyper
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierMeta
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierNumLock
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierShift
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierSuper
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.Right
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.Up
import com.jakewharton.mosaic.terminal.UnknownEvent
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class EventParserCsiKeyboardEventTest : BaseEventParserTest() {
	@Test fun up() {
		testTerminal.write("${CSI}A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Up))
	}

	@Test fun down() {
		testTerminal.write("${CSI}B")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Down))
	}

	@Test fun right() {
		testTerminal.write("${CSI}C")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Right))
	}

	@Test fun left() {
		testTerminal.write("${CSI}D")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Left))
	}

	@Test fun begin() {
		testTerminal.write("${CSI}E")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(KpBegin))
	}

	@Test fun end() {
		testTerminal.write("${CSI}F")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(End))
	}

	@Test fun home() {
		testTerminal.write("${CSI}H")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Home))
	}

	@Test fun shiftTab() {
		// Back-tab: what a terminal without the Kitty protocol sends for Shift+Tab.
		testTerminal.write("${CSI}Z")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(0x09, modifiers = ModifierShift))
	}

	@Test fun shiftTabMatchesTheKittyEncoding() {
		testTerminal.write("${CSI}Z${CSI}9;2u")
		val legacy = parser.next()
		val kitty = parser.next()
		assertThat(kitty).isEqualTo(KeyboardEvent(0x09, modifiers = ModifierShift))
		assertThat(legacy).isEqualTo(kitty)
	}

	@Test fun shiftTabWithModifiersKeepsShift() {
		// The modifier parameter may spell shift out or leave it to the final byte; either way it is set.
		testTerminal.write("${CSI}1;6Z${CSI}1;5Z")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(0x09, modifiers = ModifierShift or ModifierCtrl))
		assertThat(parser.next()).isEqualTo(KeyboardEvent(0x09, modifiers = ModifierShift or ModifierCtrl))
	}

	@Test fun shiftTabFollowedByMoreInputEndsAtTheZ() {
		testTerminal.write("${CSI}ZA")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(0x09, modifiers = ModifierShift))
		assertThat(parser.next()).isEqualTo(KeyboardEvent('A'.code))
	}

	@Test fun shiftTabNon1p0() {
		testTerminal.write("${CSI}2;2Z")
		assertThat(parser.next()).isEqualTo(
			UnknownEvent("1b5b323b325a".hexToByteArray()),
		)
	}

	@Test fun shiftTabSplitAcrossReads() = runBlocking {
		withTimeout(30.seconds) {
			try {
				// Only the introducer arrives at first: the parser must wait for the final byte.
				testTerminal.write(CSI)
				val event = async(Dispatchers.IO) { parser.next() }
				delay(100.milliseconds)
				testTerminal.write("Z")
				assertThat(event.await()).isEqualTo(KeyboardEvent(0x09, modifiers = ModifierShift))
			} finally {
				// Never leave the reader blocked when an assertion or the timeout cuts the test short.
				testTerminal.interruptTtyRead()
			}
		}
	}

	@Test fun modifierShiftUp() {
		testTerminal.write("${CSI}1;2A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Up, modifiers = ModifierShift))
	}

	@Test fun modifierAltUp() {
		testTerminal.write("${CSI}1;3A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Up, modifiers = ModifierAlt))
	}

	@Test fun modifierCtrlUp() {
		testTerminal.write("${CSI}1;5A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Up, modifiers = ModifierCtrl))
	}

	@Test fun modifierSuperUp() {
		testTerminal.write("${CSI}1;9A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Up, modifiers = ModifierSuper))
	}

	@Test fun modifierHyperUp() {
		testTerminal.write("${CSI}1;17A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Up, modifiers = ModifierHyper))
	}

	@Test fun modifierMetaUp() {
		testTerminal.write("${CSI}1;33A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Up, modifiers = ModifierMeta))
	}

	@Test fun modifierCapsLockUp() {
		testTerminal.write("${CSI}1;65A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Up, modifiers = ModifierCapsLock))
	}

	@Test fun modifierNumLockUp() {
		testTerminal.write("${CSI}1;129A")
		assertThat(parser.next()).isEqualTo(KeyboardEvent(Up, modifiers = ModifierNumLock))
	}

	@Test fun non1p0() {
		testTerminal.write("${CSI}2;2H")
		assertThat(parser.next()).isEqualTo(
			UnknownEvent("1b5b323b3248".hexToByteArray()),
		)
	}

	@Test fun emptyModifier() {
		testTerminal.write("${CSI}1;H")
		assertThat(parser.next()).isEqualTo(
			UnknownEvent("1b5b313b48".hexToByteArray()),
		)
	}

	@Test fun nonDigitModifier() {
		testTerminal.write("${CSI}1;/H")
		assertThat(parser.next()).isEqualTo(
			UnknownEvent("1b5b313b2f48".hexToByteArray()),
		)
	}
}
