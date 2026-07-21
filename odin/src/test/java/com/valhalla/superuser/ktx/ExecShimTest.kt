package com.valhalla.superuser.ktx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Drives the REAL production seam ShellResult.toLegacyResult() — the exact adaptation the deprecated
// runCommand/runCommands shims delegate to. exec() itself needs a live shell, but the mapping policy
// does not, so deleting/altering the real mapping would fail these tests.
class ExecShimTest {
    @Test fun successMapsToStdout() {
        val r = ShellResult(0, listOf("ok"), emptyList()).toLegacyResult()
        assertTrue(r.isSuccess)
        assertEquals(listOf("ok"), r.getOrNull())
    }

    @Test fun nonZeroMapsToFailureWithCodeAndStderr() {
        val r = ShellResult(2, emptyList(), listOf("bad")).toLegacyResult()
        assertTrue(r.isFailure)
        assertEquals("Command failed with code 2: bad", r.exceptionOrNull()?.message)
    }

    @Test fun nonZeroJoinsMultiLineStderrWithNewline() {
        val r = ShellResult(1, emptyList(), listOf("line1", "line2")).toLegacyResult()
        assertTrue(r.isFailure)
        assertEquals("Command failed with code 1: line1\nline2", r.exceptionOrNull()?.message)
    }
}
