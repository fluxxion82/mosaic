package com.jakewharton.mosaic.animation

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
