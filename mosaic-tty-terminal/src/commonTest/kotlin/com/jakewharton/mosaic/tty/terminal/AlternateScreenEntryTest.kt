package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AlternateScreenEntryTest {
	@Test fun exitIsOwedOnceTheSwitchWasAttemptedEvenIfNeverConfirmed() = runBlocking {
		withTimeout(30.seconds) {
			val accepted = StringBuilder()
			val switchAccepted = CompletableDeferred<Unit>()
			val releaseWriter = CompletableDeferred<Unit>()
			try {
				// The TTY accepts the switch, then the writer stalls before it can report that.
				val writer = TtyWriter(shutdownTimeout = 200.milliseconds) { buffer, offset, count ->
					accepted.append(buffer.decodeToString(offset, offset + count))
					if (accepted.length == alternateScreenEnable.length) {
						switchAccepted.complete(Unit)
						runBlocking { releaseWriter.await() }
					}
					count
				}
				val entry = AlternateScreenEntry(writer)
				val entering = async(Dispatchers.IO) { entry.enter() }
				switchAccepted.await()

				// Shutdown gives up waiting for the stalled writer and restores. The user is on the
				// alternate screen, so the restore must leave it although nothing confirmed the switch.
				val restore = StringBuilder()
				async(Dispatchers.IO) {
					writer.shutdown {
						assertThat(entry.confirmed).isFalse()
						assertThat(entry.needsExit).isTrue()
						val sequences = restoreSequences(
							synchronizedOutput = true,
							alternateScreen = entry.needsExit,
							cursor = true,
							systemTheme = false,
							inBandResize = false,
							focus = false,
						)
						restore.append(sequences.joinToString(""))
					}
				}.await()
				assertThat(restore.toString()).contains(alternateScreenDisable)

				// The writer resumes only now; its late completion must not run the bookkeeping either.
				releaseWriter.complete(Unit)
				entering.await()
				assertThat(entry.confirmed).isFalse()
			} finally {
				releaseWriter.complete(Unit)
			}
		}
	}

	@Test fun nothingIsOwedBeforeAnAttempt() {
		val entry = AlternateScreenEntry(TtyWriter { _, _, count -> count })
		assertThat(entry.needsExit).isFalse()
		entry.enter()
		assertThat(entry.attempted).isTrue()
		assertThat(entry.confirmed).isTrue()
		assertThat(entry.needsExit).isTrue()
	}
}
