package com.jakewharton.mosaic

internal expect fun env(name: String): String?

internal expect fun nonInteractiveExit(): Nothing
