package com.jakewharton.mosaic.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * The page's URL fragment without the leading `#`, percent-decoded, and updated on `hashchange`.
 *
 * Together with [SpanStyle.link][com.jakewharton.mosaic.text.SpanStyle.link] set to `"#name"`,
 * this gives clickable in-app navigation that works with the back button and deep links. Update it
 * from key handlers with [setLocationHash].
 */
@Composable
public fun rememberLocationHash(): State<String> {
	val state: MutableState<String> = remember { mutableStateOf(currentHash()) }
	DisposableEffect(Unit) {
		val listener: (Event) -> Unit = { state.value = currentHash() }
		window.addEventListener("hashchange", listener)
		onDispose { window.removeEventListener("hashchange", listener) }
	}
	return state
}

/** Navigate to `#hash`, adding a history entry. Observers of [rememberLocationHash] update. */
public fun setLocationHash(hash: String) {
	window.location.hash = encodeUri(hash)
}

// encodeURI keeps "/" and other URL delimiters readable, which fragments may contain.
private fun encodeUri(value: String): String = js("encodeURI(value)")

private fun currentHash(): String = decodeUriComponent(window.location.hash.removePrefix("#"))

private fun decodeUriComponent(value: String): String = js("{ try { return decodeURIComponent(value) } catch (e) { return value } }")
