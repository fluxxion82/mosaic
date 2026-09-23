package com.jakewharton.mosaic.browser

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.Event
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event as DomEvent
import org.w3c.dom.events.KeyboardEvent as DomKeyboardEvent

/**
 * A [Terminal] backed by a DOM element.
 *
 * Input goes through a visually hidden `<textarea>` inside [element], as in xterm.js: `keydown`
 * delivers functional keys and shortcuts, and `input` delivers text. That makes soft keyboards on
 * touch devices, paste, dead keys, and IME composition work. Clicking [element] focuses the
 * textarea unless the user is selecting text.
 *
 * The size is derived from the element's content box and the measured width of one character
 * cell. The theme follows `prefers-color-scheme`.
 */
internal class BrowserTerminal(
	private val element: HTMLElement,
	private val captureTab: Boolean,
) : Terminal,
	Terminal.State,
	Terminal.Capabilities {
	override val name: String get() = "browser"
	override val interactive: Boolean get() = true

	override val state: Terminal.State get() = this
	override val capabilities: Terminal.Capabilities get() = this

	private val eventChannel = Channel<Event>(Channel.UNLIMITED)
	override val events: Channel<Event> get() = eventChannel

	override val focused = MutableStateFlow(false)
	override val theme = MutableStateFlow(Terminal.Theme.Unknown)
	override val size = MutableStateFlow(Terminal.Size.Default)

	override val ansiLevel: AnsiLevel get() = AnsiLevel.TRUECOLOR
	override val cursorVisibility: Boolean get() = false
	override val focusEvents: Boolean get() = true
	override val inBandResizeEvents: Boolean get() = false
	override val kittyGraphics: Boolean get() = false
	override val kittyKeyboard: Boolean get() = false
	override val kittyNotifications: Boolean get() = false
	override val kittyPointerShape: Boolean get() = false
	override val kittyTextSizingScale: Boolean get() = false
	override val kittyTextSizingWidth: Boolean get() = false
	override val kittyUnderline: Boolean get() = false
	override val synchronizedOutput: Boolean get() = false
	override val themeEvents: Boolean get() = true

	private val darkQuery = window.matchMedia("(prefers-color-scheme: dark)")
	private val probe = (document.createElement("span") as HTMLElement).apply {
		textContent = "M".repeat(10)
		setAttribute("aria-hidden", "true")
		style.apply {
			position = "absolute"
			visibility = "hidden"
			setProperty("white-space", "pre")
		}
	}

	private val input = (document.createElement("textarea") as HTMLTextAreaElement).apply {
		className = "mosaic-input"
		setAttribute("aria-label", "Terminal input")
		setAttribute("autocapitalize", "off")
		setAttribute("autocomplete", "off")
		setAttribute("autocorrect", "off")
		setAttribute("spellcheck", "false")
		setAttribute("rows", "1")
		style.apply {
			// Focusable and on-screen (for IME candidate windows) but invisible.
			position = "fixed"
			left = "0"
			top = "0"
			width = "1px"
			height = "1px"
			padding = "0"
			border = "0"
			// iOS zooms the page when focusing an input with a font smaller than 16px.
			fontSize = "16px"
			opacity = "0"
			setProperty("resize", "none")
			setProperty("overflow", "hidden")
			setProperty("caret-color", "transparent")
			setProperty("pointer-events", "none")
		}
	}

	private val onKeyDown: (DomEvent) -> Unit = { event ->
		val keyEvent = (event as DomKeyboardEvent).toMosaicOrNull(captureTab)
		if (keyEvent != null) {
			event.preventDefault()
			eventChannel.trySend(keyEvent)
		}
	}
	private val onInput: (DomEvent) -> Unit = { event ->
		val composing = isComposing(event)
		// Outside composition the textarea is always empty, so a delete means the user pressed
		// Backspace on a soft keyboard that reported keydown as "Unidentified". During composition
		// it only edits the pending text. Line breaks are inserted as text and flushed as Enter.
		if (inputType(event) == "deleteContentBackward" && !composing) {
			eventChannel.trySend(KeyboardEvent(127))
		}
		if (!composing) flushInput()
	}
	private val onCompositionEnd: (DomEvent) -> Unit = { flushInput() }
	private val onClick: (DomEvent) -> Unit = {
		// Leave a text selection alone so it can be copied.
		if (selectionIsCollapsed()) focus()
	}
	private val onFocus: (DomEvent) -> Unit = { focused.value = true }
	private val onBlur: (DomEvent) -> Unit = { focused.value = false }
	private val onThemeChange: (DomEvent) -> Unit = { updateTheme() }
	private val resizeObserver = ResizeObserver { _, _ -> updateSize() }

	init {
		element.appendChild(probe)
		element.appendChild(input)
		element.addEventListener("click", onClick)
		input.addEventListener("keydown", onKeyDown)
		input.addEventListener("input", onInput)
		input.addEventListener("compositionend", onCompositionEnd)
		input.addEventListener("focus", onFocus)
		input.addEventListener("blur", onBlur)
		darkQuery.addEventListener("change", onThemeChange)
		resizeObserver.observe(element)
		// The probe resizes when a web font finishes loading, which changes the cell size.
		resizeObserver.observe(probe)
		updateTheme()
		updateSize()
	}

	fun focus() {
		focusWithoutScroll(input)
	}

	private fun flushInput() {
		val text = input.value
		if (text.isEmpty()) return
		input.value = ""
		for (event in textKeyboardEvents(text)) {
			eventChannel.trySend(event)
		}
	}

	private fun updateTheme() {
		theme.value = if (darkQuery.matches) Terminal.Theme.Dark else Terminal.Theme.Light
	}

	private fun updateSize() {
		val cell = probe.getBoundingClientRect()
		val cellWidth = cell.width / 10.0
		val cellHeight = cell.height
		if (cellWidth <= 0.0 || cellHeight <= 0.0) return
		// clientWidth/clientHeight include padding but not borders or scrollbars.
		val computed = window.getComputedStyle(element)
		fun px(value: String) = value.removeSuffix("px").toDoubleOrNull() ?: 0.0
		val width = (element.clientWidth - px(computed.paddingLeft) - px(computed.paddingRight)).toInt()
		val height = (element.clientHeight - px(computed.paddingTop) - px(computed.paddingBottom)).toInt()
		val newSize = Terminal.Size(
			columns = (width / cellWidth).toInt().coerceAtLeast(1),
			rows = (height / cellHeight).toInt().coerceAtLeast(1),
			width = width,
			height = height,
		)
		if (newSize != size.value) {
			size.value = newSize
		}
	}

	override fun close() {
		resizeObserver.disconnect()
		darkQuery.removeEventListener("change", onThemeChange)
		element.removeEventListener("click", onClick)
		input.removeEventListener("keydown", onKeyDown)
		input.removeEventListener("input", onInput)
		input.removeEventListener("compositionend", onCompositionEnd)
		input.removeEventListener("focus", onFocus)
		input.removeEventListener("blur", onBlur)
		input.remove()
		probe.remove()
		eventChannel.close()
	}
}

private fun inputType(event: DomEvent): String = js("event.inputType || ''")

private fun isComposing(event: DomEvent): Boolean = js("!!event.isComposing")

private fun selectionIsCollapsed(): Boolean = js("window.getSelection()?.isCollapsed ?? true")

internal fun focusWithoutScroll(element: HTMLElement) {
	js("element.focus({ preventScroll: true })")
}

private external class ResizeObserver(callback: (JsAny, JsAny) -> Unit) : JsAny {
	fun observe(target: Element)
	fun disconnect()
}
