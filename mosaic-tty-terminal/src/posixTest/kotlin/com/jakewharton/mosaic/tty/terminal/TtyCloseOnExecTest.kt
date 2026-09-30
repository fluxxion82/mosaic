package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isTrue
import com.jakewharton.mosaic.tty.TestTerminal
import com.jakewharton.mosaic.tty.mosaic_test_tty_descriptors_are_cloexec
import kotlin.test.Test

class TtyCloseOnExecTest {
	@Test fun ttyAndPipesAreNotInheritedByChildProcesses() {
		TestTerminal.bind().use {
			assertThat(mosaic_test_tty_descriptors_are_cloexec()).isTrue()
		}
	}
}
