package com.jakewharton.mosaic

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun nanoTime(): Long = (performanceNow() * 1_000_000.0).toLong()

internal fun performanceNow(): Double = js("performance.now()")
