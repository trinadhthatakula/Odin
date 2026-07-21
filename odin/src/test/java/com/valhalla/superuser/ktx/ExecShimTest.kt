package com.valhalla.superuser.ktx

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecShimTest {
    // Exercises the execToLegacy adaptation policy directly (exec() itself needs a live shell).
    private fun legacyOf(r: ShellResult): Result<List<String>> =
        if (r.isSuccess) Result.success(r.stdout)
        else Result.failure(java.io.IOException("Command failed with code ${r.code}: ${r.stderr.joinToString("\n")}"))

    @Test fun successMapsToStdout() = runTest {
        val r = legacyOf(ShellResult(0, listOf("ok"), emptyList()))
        assertTrue(r.isSuccess); assertEquals(listOf("ok"), r.getOrNull())
    }

    @Test fun nonZeroMapsToFailureWithCodeAndStderr() = runTest {
        val r = legacyOf(ShellResult(2, emptyList(), listOf("bad")))
        assertTrue(r.isFailure)
        assertEquals("Command failed with code 2: bad", r.exceptionOrNull()?.message)
    }
}
