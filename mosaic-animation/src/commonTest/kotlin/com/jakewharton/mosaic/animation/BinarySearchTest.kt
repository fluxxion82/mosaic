package com.jakewharton.mosaic.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class BinarySearchTest {
	private val array = floatArrayOf(0f, 0.25f, 0.5f, 1f)

	@Test fun found() {
		assertThat(binarySearch(array, 0f)).isEqualTo(0)
		assertThat(binarySearch(array, 0.25f)).isEqualTo(1)
		assertThat(binarySearch(array, 1f)).isEqualTo(3)
	}

	@Test fun absentReturnsInsertionPoint() {
		assertThat(binarySearch(array, -1f)).isEqualTo(-1)
		assertThat(binarySearch(array, 0.3f)).isEqualTo(-3)
		assertThat(binarySearch(array, 2f)).isEqualTo(-5)
	}

	@Test fun empty() {
		assertThat(binarySearch(FloatArray(0), 1f)).isEqualTo(-1)
	}
}
