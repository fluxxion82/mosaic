package com.jakewharton.mosaic.animation

import kotlin.math.PI

// Wasm is single-threaded, so a plain holder is sufficient.
internal actual class AtomicReference<T>(var value: T)

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <T> AtomicReference<T>.get(): T {
	return value
}

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <T> AtomicReference<T>.set(value: T) {
	this.value = value
}

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <T> AtomicReference<T>.compareAndSet(expect: T, update: T): Boolean {
	if (value !== expect) return false
	value = update
	return true
}

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <T> atomicReferenceOf(initialValue: T): AtomicReference<T> {
	return AtomicReference(initialValue)
}

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun toRadians(value: Double): Double {
	return value * (PI / 180.0)
}

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun binarySearch(array: FloatArray, position: Float): Int {
	var low = 0
	var high = array.size - 1
	while (low <= high) {
		val mid = (low + high) ushr 1
		val comparison = array[mid].compareTo(position)
		when {
			comparison < 0 -> low = mid + 1
			comparison > 0 -> high = mid - 1
			else -> return mid
		}
	}
	return -(low + 1)
}
