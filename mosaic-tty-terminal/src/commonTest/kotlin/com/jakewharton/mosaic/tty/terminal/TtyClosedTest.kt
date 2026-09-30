package com.jakewharton.mosaic.tty.terminal

import com.jakewharton.mosaic.tty.TestTerminal
import com.jakewharton.mosaic.tty.Tty
import kotlin.test.Test

class TtyClosedTest {
	@Test fun setCallbackAfterCloseDoesNothing() {
		TestTerminal.bind().use { testTerminal ->
			val tty = testTerminal.tty
			tty.setCallback(NoOpCallback)
			tty.close()
			// A terminal's reader may still be winding down after the TTY was closed.
			tty.setCallback(null)
			tty.setCallback(NoOpCallback)
		}
	}

	private object NoOpCallback : Tty.Callback {
		override fun onFocus(focused: Boolean) = Unit
		override fun onKey() = Unit
		override fun onMouse() = Unit
		override fun onResize(columns: Int, rows: Int, width: Int, height: Int) = Unit
	}
}
