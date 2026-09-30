package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.io.bytestring.encodeToByteString

class TtyTerminalTest {
	@Test fun worksEvenWithoutReply() = terminalTest {
		val teardown = withTerminal { setup ->
			// No reply means cursor support was not reported, so it is hidden unconditionally.
			assertThat(setup).isEqualTo("${CSI}0c$CSI?25l".encodeToByteString())
		}
		assertThat(teardown).isEqualTo("$CSI?25h".encodeToByteString())
	}
}
