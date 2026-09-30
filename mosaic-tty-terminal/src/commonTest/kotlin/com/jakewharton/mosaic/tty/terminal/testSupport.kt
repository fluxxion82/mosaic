package com.jakewharton.mosaic.tty.terminal

import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Poll [condition] with real delays until it holds, failing after five seconds. */
suspend fun awaitCondition(description: String, condition: () -> Boolean) {
	withTimeoutOrNull(5.seconds) {
		while (!condition()) delay(5.milliseconds)
	} ?: fail("Timed out waiting for $description")
}
