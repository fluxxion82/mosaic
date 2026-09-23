package com.jakewharton.mosaic.browser

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine

/** A [MonotonicFrameClock] which ticks on the browser's `requestAnimationFrame`. */
internal object RafFrameClock : MonotonicFrameClock {
	override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
		return suspendCancellableCoroutine { continuation ->
			val handle = window.requestAnimationFrame { millis ->
				continuation.resumeWith(runCatching { onFrame((millis * 1_000_000.0).toLong()) })
			}
			continuation.invokeOnCancellation { window.cancelAnimationFrame(handle) }
		}
	}
}
