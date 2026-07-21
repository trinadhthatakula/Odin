package com.valhalla.superuser.ktx

/**
 * One line of live shell output, tagged by stream.
 * @property text the line contents.
 * @property isError `true` if this line came from STDERR, `false` for STDOUT.
 */
public data class ShellLine(val text: String, val isError: Boolean)
