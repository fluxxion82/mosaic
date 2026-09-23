package com.jakewharton.mosaic

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.TestTerminal
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class IdleFrameTest {
	private class CountingClock : MonotonicFrameClock {
		val frames = BroadcastFrameClock()
		var requests = 0

		override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
			requests++
			return frames.withFrameNanos(onFrame)
		}
	}

	private fun TestScope.sendFrames(clock: CountingClock, count: Int) {
		repeat(count) {
			runCurrent()
			clock.frames.sendFrame(it * 16_000_000L)
		}
		runCurrent()
	}

	@Test fun idleCompositionStopsRequestingFrames() = runTest {
		val clock = CountingClock()
		val job = Job()
		val mosaic = Mosaic(coroutineContext + job + clock, onDraw = {}, terminal = TestTerminal())
		try {
			mosaic.setContent { Text("static") }
			sendFrames(clock, 20)
			assertThat(clock.requests).isLessThan(3)
		} finally {
			mosaic.cancel()
			job.cancel()
		}
	}

	@Test fun stateChangeWakesIdleComposition() = runTest {
		val clock = CountingClock()
		val job = Job()
		var text by mutableStateOf("before")
		var rendered = ""
		val mosaic = Mosaic(
			coroutineContext = coroutineContext + job + clock,
			onDraw = { rendered = it.draw().render(AnsiLevel.NONE, false) },
			terminal = TestTerminal(),
		)
		try {
			mosaic.setContent { Text(text) }
			sendFrames(clock, 5)
			assertThat(rendered).isEqualTo("before")

			text = "after"
			Snapshot.sendApplyNotifications()
			sendFrames(clock, 5)
			assertThat(rendered).isEqualTo("after")
		} finally {
			mosaic.cancel()
			job.cancel()
		}
	}

	@Test fun keyEventWakesIdleComposition() = runTest {
		val clock = CountingClock()
		val job = Job()
		val terminal = TestTerminal()
		var rendered = ""
		val mosaic = Mosaic(
			coroutineContext = coroutineContext + job + clock,
			onDraw = { rendered = it.draw().render(AnsiLevel.NONE, false) },
			terminal = terminal,
		)
		try {
			mosaic.setContent {
				var key by remember { mutableStateOf("none") }
				Text(
					key,
					modifier = Modifier.onKeyEvent {
						key = it.key
						true
					},
				)
			}
			sendFrames(clock, 5)
			val idleRequests = clock.requests

			terminal.events.trySend(KeyboardEvent('x'.code))
			sendFrames(clock, 5)
			assertThat(rendered).isEqualTo("x")
			assertThat(clock.requests).isGreaterThan(idleRequests)
		} finally {
			mosaic.cancel()
			job.cancel()
		}
	}

	@Test fun animationKeepsRequestingFramesThenReturnsToIdle() = runTest {
		val clock = CountingClock()
		val job = Job()
		var rendered = ""
		val mosaic = Mosaic(
			coroutineContext = coroutineContext + job + clock,
			onDraw = { rendered = it.draw().render(AnsiLevel.NONE, false) },
			terminal = TestTerminal(),
		)
		try {
			mosaic.setContent {
				var frame by remember { mutableIntStateOf(0) }
				LaunchedEffect(Unit) {
					repeat(5) { withFrameNanos { frame++ } }
				}
				Text("frame $frame")
			}
			sendFrames(clock, 10)
			assertThat(rendered).isEqualTo("frame 5")
			val afterAnimation = clock.requests
			assertThat(afterAnimation).isGreaterThanOrEqualTo(5)

			sendFrames(clock, 20)
			assertThat(clock.requests).isLessThan(afterAnimation + 2)
		} finally {
			mosaic.cancel()
			job.cancel()
		}
	}
}
