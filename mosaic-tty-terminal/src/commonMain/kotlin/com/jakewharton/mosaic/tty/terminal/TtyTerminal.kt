@file:OptIn(ExperimentalAtomicApi::class)

package com.jakewharton.mosaic.tty.terminal

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.CapabilityQueryEvent
import com.jakewharton.mosaic.terminal.CursorPositionEvent
import com.jakewharton.mosaic.terminal.DebugEvent
import com.jakewharton.mosaic.terminal.DecModeReportEvent
import com.jakewharton.mosaic.terminal.DecModeReportEvent.Setting
import com.jakewharton.mosaic.terminal.Event
import com.jakewharton.mosaic.terminal.FocusEvent
import com.jakewharton.mosaic.terminal.KittyGraphicsEvent
import com.jakewharton.mosaic.terminal.KittyKeyboardQueryEvent
import com.jakewharton.mosaic.terminal.KittyNotificationEvent
import com.jakewharton.mosaic.terminal.KittyPointerQueryEvent
import com.jakewharton.mosaic.terminal.OperatingStatusResponseEvent
import com.jakewharton.mosaic.terminal.PrimaryDeviceAttributesEvent
import com.jakewharton.mosaic.terminal.ResizeEvent
import com.jakewharton.mosaic.terminal.SystemThemeEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.terminal.TerminalVersionEvent
import com.jakewharton.mosaic.tty.IOException
import com.jakewharton.mosaic.tty.Tty
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private class TtyTerminal(
	override val name: String?,
	override val state: Terminal.State,
	override val capabilities: Terminal.Capabilities,
	override val events: ReceiveChannel<Event>,
	private val closeJob: Job,
) : Terminal {
	override val interactive get() = true
	override fun close() = closeJob.cancel()

	class State(
		override val focused: StateFlow<Boolean>,
		override val theme: StateFlow<Terminal.Theme>,
		override val size: StateFlow<Terminal.Size>,
	) : Terminal.State

	class Capabilities(
		override val ansiLevel: AnsiLevel,
		override val cursorVisibility: Boolean,
		override val focusEvents: Boolean,
		override val inBandResizeEvents: Boolean,
		override val kittyGraphics: Boolean,
		override val kittyKeyboard: Boolean,
		override val kittyNotifications: Boolean,
		override val kittyPointerShape: Boolean,
		override val kittyTextSizingScale: Boolean,
		override val kittyTextSizingWidth: Boolean,
		override val kittyUnderline: Boolean,
		override val synchronizedOutput: Boolean,
		override val themeEvents: Boolean,
	) : Terminal.Capabilities
}

private const val StageDeviceAttributes = 3
private const val StageCapabilityQueries = 2
private const val StageDefaultQueries = 1
private const val StageNormalOperation = 0

/**
 * Bind a [Terminal] to this TTY. Its reader runs in [scope] until the terminal is closed; wait for
 * that coroutine to finish before closing this TTY. [withTerminalIn] does both for you.
 */
public suspend fun Tty.asTerminalIn(
	scope: CoroutineScope,
	/** When true, each terminal event will be immediately followed by a matching [DebugEvent]. */
	emitDebugEvents: Boolean = false,
): Terminal = asTerminalIn(scope, emitDebugEvents, TtyWriter(write = ::write))

/**
 * Bind a [Terminal] to this TTY for the duration of [block], which also receives an `output`
 * function for writing to the TTY. Its writes are serialized with the terminal's own control
 * sequences, so each call is written completely or not at all relative to them, and output which
 * arrives once the terminal has started restoring the TTY is dropped instead of corrupting it.
 *
 * The terminal is closed when [block] returns or fails, and this only returns once the terminal's
 * reader has finished as well, so the TTY may be closed right afterwards.
 */
public suspend fun Tty.withTerminalIn(
	/** When true, each terminal event will be immediately followed by a matching [DebugEvent]. */
	emitDebugEvents: Boolean = false,
	block: suspend (terminal: Terminal, output: (String) -> Unit) -> Unit,
) {
	withTerminalIn(DefaultShutdownTimeout, emitDebugEvents, block = block)
}

internal suspend fun Tty.withTerminalIn(
	shutdownTimeout: Duration,
	emitDebugEvents: Boolean,
	/** Test seam invoked by the reader immediately before each read. */
	beforeRead: () -> Unit = {},
	block: suspend (terminal: Terminal, output: (String) -> Unit) -> Unit,
) {
	// Own the reader and the finalizer: leave only once both have finished, also when cancelled.
	// Otherwise the caller could close the TTY underneath a reader which is still winding down.
	coroutineScope {
		val writer = TtyWriter(shutdownTimeout, ::write)
		asTerminalIn(this, emitDebugEvents, writer, beforeRead).use { terminal ->
			block(terminal) { writer.write(it.encodeToByteArray()) }
		}
	}
}

private suspend fun Tty.asTerminalIn(
	scope: CoroutineScope,
	emitDebugEvents: Boolean,
	writer: TtyWriter,
	beforeRead: () -> Unit = {},
): Terminal {
	// Entering raw mode can fail, so perform it before any additional control sequences which change
	// settings. We also need to be in character mode to query capabilities with control sequences.
	enableRawMode()

	val focused = MutableStateFlow(true)
	val theme = MutableStateFlow(Terminal.Theme.Unknown)
	val size = MutableStateFlow(Terminal.Size.Default)

	val events = Channel<Event>(64, onBufferOverflow = DROP_OLDEST)

	// Set before the reader is interrupted. The interrupt itself is not a reliable stop signal: a
	// timed read (the bare Escape disambiguation) consumes it as a mere timeout.
	val stopping = AtomicBoolean(false)

	setCallback(EventParserTtyCallback(focused, size, events, emitDebugEvents))

	/** Write a control sequence as one transaction. Dropped once the TTY is being restored. */
	fun write(s: String) {
		writer.write(s.encodeToByteArray())
	}

	// Each of these will become true when their respective feature is recognized by the terminal
	// and was not already configured to our desired setting. Revert each toggled setting on exit.
	var toggleCursor = false
	var toggleFocus = false
	var toggleInBandResize = false
	var toggleSystemTheme = false

	// TODO The design of finalization hook fights us here. We don't need suspension.
	val interruptJob = scope.launch(Unconfined, start = UNDISPATCHED) {
		withTerminalFinalizationHook(
			hook = {
				// Let a frame in flight finish, then restore. The writer drops anything that follows.
				writer.shutdown { emergency ->
					// Best effort: a failing write (EIO on a vanished PTY) must not skip the rest. In an
					// emergency the TTY may be stuck, so all sequences together get one short deadline and
					// nothing here blocks on the TTY.
					val deadline = TimeSource.Monotonic.markNow() + EmergencyRestoreTimeout
					try {
						if (toggleSystemTheme) tryRestore(systemThemeDisable, emergency, deadline)
						if (toggleInBandResize) tryRestore(inBandResizeDisable, emergency, deadline)
						if (toggleFocus) tryRestore(focusDisable, emergency, deadline)
						if (toggleCursor) tryRestore(cursorEnable, emergency, deadline)
					} finally {
						if (emergency) resetImmediately() else reset()
					}
				}
			},
			block = {
				try {
					awaitCancellation()
				} finally {
					// When cancelled (from signal or normally), wake up the reader parse loop so it can exit.
					stopping.store(true)
					interruptRead()
				}
			},
		)
	}

	write("${CSI}0c")
	val debugBootstrap = env("MOSAIC_TTY_TERMINAL_DEBUG") == "true"
	var stage = StageDeviceAttributes

	var cursorVisibility = false
	var cursorReported = false
	var focusEvents = false
	var inBandResizeEvents = false
	var kittyGraphics = false
	var kittyKeyboard = false
	var kittyNotifications = false
	var kittyPointerShape = false
	var kittyTextSizingScale = false
	var kittyTextSizingWidth = false
	var kittyTextSizingCursorPositionCount = 0
	var kittyTextSizingLastCursorPosition: CursorPositionEvent? = null
	var kittyUnderlines = false
	var synchronizedOutput = false
	var terminalName: String? = null
	var themeEvents = false

	val bootstrapDone = CompletableDeferred<Unit>()
	suspend fun readEvents(parser: EventParser) {
		while (true) {
			val event = parser.next()
			// The terminal is closing, a shutdown signal interrupted the read, or the read was
			// interrupted; a timed read may have swallowed either interrupt as a timeout and returned
			// an event instead of null. Whichever it was, stop, so the terminal is closed from here.
			if (stopping.load() || shutdownSignal() != 0 || event == null) return
			if (stage != StageNormalOperation && debugBootstrap) {
				write("$event\r\n")
			}
			when (event) {
				is PrimaryDeviceAttributesEvent -> {
					if (stage == StageNormalOperation) continue

					if (event.id == 1) {
						// VT100 terminals can't handle most of the other queries so just bail.
						stage = StageNormalOperation
						bootstrapDone.complete(Unit)
						continue
					}

					stage = StageCapabilityQueries
					write(
						"$CSI?$cursorMode\$p" +
							"$CSI?$focusMode\$p" +
							"$CSI?$synchronizedOutputMode\$p" +
							"$CSI?$systemThemeMode\$p" +
							"$CSI?$inBandResizeMode\$p" +
							"$CSI?996n" + // Theme query
							"${APC}Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA$ST" + // Kitty graphics
							"$CSI?u" + // Kitty keyboard
							"${OSC}99;i=1:p=?$ST" + // Kitty notifications
							"${OSC}22;?__current__$ST" + // Kitty pointer shape
							"${CSI}6n${OSC}66;w=1; $ST${CSI}6n${OSC}66;s=2; $ST${CSI}6n\r" + // Kitty text sizing
							"$DCS+q5375$ST" + // Kitty underline ("Su")
							"$CSI>0q" + // Xterm version
							"${CSI}5n", // DSR (end marker)
					)
				}

				is DecModeReportEvent -> {
					if (stage != StageCapabilityQueries) continue

					when (event.mode) {
						cursorMode -> {
							cursorReported = true
							cursorVisibility = event.setting.canBeChanged
							if (event.setting == Setting.Set) {
								toggleCursor = true
								write(cursorDisable)
							}
						}

						focusMode -> {
							if (event.setting.isSupported) {
								focusEvents = true
								toggleFocus = event.setting == Setting.Reset
								// Enabling focus notification (even if already set) _might_ trigger an initial
								// event. There is otherwise no explicit way to request the initial value.
								write(focusEnable)
							}
						}

						synchronizedOutputMode -> {
							synchronizedOutput = event.setting.canBeChanged
						}

						systemThemeMode -> {
							themeEvents = event.setting.isSupported
							if (event.setting == Setting.Reset) {
								toggleSystemTheme = true
								write(systemThemeEnable)
							}
						}

						inBandResizeMode -> {
							if (event.setting.isSupported) {
								inBandResizeEvents = true
								toggleInBandResize = event.setting == Setting.Reset
								// Enabling in-band resize (even if already set) should trigger an initial event.
								write(inBandResizeEnable)
							}
						}
					}
				}

				is OperatingStatusResponseEvent -> {
					if (stage == StageCapabilityQueries) {
						if (focusEvents or inBandResizeEvents) {
							// By enabling these modes (or by sending an explicit default value query after
							// enabling the mode) wait for a reply about the default with a second DSR.
							stage = StageDefaultQueries
							write("${CSI}5n")
						} else {
							stage = StageNormalOperation
							bootstrapDone.complete(Unit)
						}
					} else if (stage == StageDefaultQueries) {
						bootstrapDone.complete(Unit)
					}
				}

				is KittyKeyboardQueryEvent -> {
					if (stage == StageCapabilityQueries) {
						kittyKeyboard = true
					}
				}

				is KittyGraphicsEvent -> {
					if (stage == StageCapabilityQueries) {
						kittyGraphics = true
					}
				}

				is KittyPointerQueryEvent -> {
					if (stage == StageCapabilityQueries) {
						kittyPointerShape = true
					}
				}

				is KittyNotificationEvent -> {
					if (stage == StageCapabilityQueries) {
						kittyNotifications = true
					}
				}

				is CapabilityQueryEvent -> {
					if (stage == StageCapabilityQueries && event.success) {
						if ("Su" in event.data) {
							kittyUnderlines = true
						}
					}
				}

				is CursorPositionEvent -> {
					if (stage == StageCapabilityQueries) {
						when (kittyTextSizingCursorPositionCount++) {
							1 -> kittyTextSizingWidth = event != kittyTextSizingLastCursorPosition
							2 -> kittyTextSizingScale = event != kittyTextSizingLastCursorPosition
						}
						kittyTextSizingLastCursorPosition = event
					}
				}

				is TerminalVersionEvent -> {
					if (stage == StageCapabilityQueries) {
						terminalName = event.data
					}
				}

				is FocusEvent -> {
					if (focusEvents) {
						focused.value = event.focused
					} else {
						// TODO Report unsolicited focus events... somewhere.
					}
				}

				is ResizeEvent -> {
					if (inBandResizeEvents) {
						size.value = Terminal.Size(event.columns, event.rows, event.width, event.height)
					} else {
						// TODO Report unsolicited resize events... somewhere.
					}
				}

				is SystemThemeEvent -> {
					if (themeEvents || stage == StageCapabilityQueries) {
						theme.value = if (event.isDark) {
							Terminal.Theme.Dark
						} else {
							Terminal.Theme.Light
						}
					} else {
						// TODO Report unsolicited theme events... somewhere.
					}
				}

				else -> {}
			}

			events.trySend(event)
		}
	}

	scope.launch(Dispatchers.IO) {
		val parser = EventParser(this@asTerminalIn, beforeRead)
		try {
			readEvents(parser)
		} finally {
			// This thread is the only one which invokes the callback, so only it may free it.
			setCallback(null)
			// Whatever stopped the reader (an interrupt, a shutdown signal, EOF, an error), close the
			// terminal so the TTY is restored from this ordinary thread. After a shutdown signal the
			// hook then redelivers it, so this never returns in that case.
			interruptJob.cancel()
		}
	}

	// Spend at most 1 second bootstrapping capabilities and defaults. In theory, there could
	// exist a terminal which does not respond to DA1 or DSR. Does that terminal actually work?
	// Who knows, but we don't want to hang forever waiting. Take whatever we got so far
	// (if anything) and move on with rendering.
	withTimeoutOrNull(1.seconds) {
		bootstrapDone.await()
	}
	stage = StageNormalOperation

	if (!cursorReported) {
		// Some terminals (such as the Linux console) support hiding the cursor but do not answer the
		// mode query. Terminals ignore DEC private modes they do not recognize, so hide it anyway.
		toggleCursor = true
		write(cursorDisable)
	}

	if (debugBootstrap) {
		write("\r\n")
	}

	if (!inBandResizeEvents) {
		currentSize().let { (columns, rows) ->
			size.value = Terminal.Size(columns, rows)
		}
		enableWindowResizeEvents()
	}

	val ansiLevel = detectAnsiLevel()

	return TtyTerminal(
		name = terminalName,
		state = TtyTerminal.State(
			focused = focused,
			theme = theme,
			size = size,
		),
		capabilities = TtyTerminal.Capabilities(
			ansiLevel = ansiLevel,
			cursorVisibility = cursorVisibility,
			focusEvents = focusEvents,
			inBandResizeEvents = inBandResizeEvents,
			kittyGraphics = kittyGraphics,
			kittyKeyboard = kittyKeyboard,
			kittyNotifications = kittyNotifications,
			kittyPointerShape = kittyPointerShape,
			kittyTextSizingScale = kittyTextSizingScale,
			kittyTextSizingWidth = kittyTextSizingWidth,
			kittyUnderline = kittyUnderlines,
			synchronizedOutput = synchronizedOutput,
			themeEvents = themeEvents,
		),
		events = events,
		closeJob = interruptJob,
	)
}

/** How long all restore sequences together may wait for a stuck TTY in an emergency. */
private val EmergencyRestoreTimeout = 500.milliseconds

/**
 * Write a restore sequence, ignoring an I/O failure so the remaining restore steps still run. In
 * an [emergency] the write never blocks and whatever the TTY did not accept by [deadline] is
 * dropped, so the total time spent restoring is bounded however many sequences there are.
 */
private fun Tty.tryRestore(s: String, emergency: Boolean, deadline: TimeMark) {
	try {
		val bytes = s.encodeToByteArray()
		if (!emergency) {
			writeFully(bytes)
			return
		}
		val remaining = -deadline.elapsedNow() // Negative until the deadline, so negate.
		writeWithTimeout(bytes, 0, bytes.size, remaining.inWholeMilliseconds.coerceAtLeast(0).toInt())
	} catch (_: IOException) {
	}
}

internal fun detectAnsiLevel(): AnsiLevel {
	if (!env("NO_COLOR").isNullOrEmpty()) {
		return AnsiLevel.NONE
	}
	val term = env("COLORTERM") ?: env("TERM") ?: "dumb"
	if (term.contains("24bit", ignoreCase = true) || term.contains("truecolor", ignoreCase = true)) {
		return AnsiLevel.TRUECOLOR
	}
	if (term.contains("256")) {
		return AnsiLevel.ANSI256
	}
	if (term != "dumb") {
		return AnsiLevel.ANSI16
	}
	// https://github.com/microsoft/terminal/issues/1040#issuecomment-496691842
	if (env("WT_SESSION") != null) {
		return AnsiLevel.TRUECOLOR
	}
	// Per https://conemu.github.io/en/ConEmuEnvironment.html
	if (env("ConEmuANSI") == "ON") {
		return AnsiLevel.TRUECOLOR
	}
	return AnsiLevel.NONE
}

private val Setting.isSupported get() = when (this) {
	Setting.NotRecognized -> false
	Setting.Set -> true
	Setting.Reset -> true
	Setting.PermanentlySet -> true
	Setting.PermanentlyReset -> false
}

private val Setting.canBeChanged get() = when (this) {
	Setting.NotRecognized -> false
	Setting.Set -> true
	Setting.Reset -> true
	Setting.PermanentlySet -> false
	Setting.PermanentlyReset -> false
}
