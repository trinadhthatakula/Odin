package com.valhalla.superuser.ktx

import com.valhalla.superuser.Shell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellResultTest {
    private fun result(code: Int, out: List<String?>, err: List<String?>) = object : Shell.Result() {
        override val out = out.toMutableList()
        override val err = err.toMutableList()
        override val code = code
    }

    @Test fun mappingPreservesCodeAndFiltersNulls() {
        val sr = result(0, listOf("a", null, "b"), listOf(null, "e")).toShellResult()
        assertEquals(0, sr.code)
        assertEquals(listOf("a", "b"), sr.stdout)
        assertEquals(listOf("e"), sr.stderr)
        assertTrue(sr.isSuccess)
    }

    @Test fun nonZeroIsNotSuccess() {
        assertFalse(result(1, emptyList(), emptyList()).toShellResult().isSuccess)
    }

    @Test fun shellResultShapeIsImmutableAndDestructurable() {
        val (code, out, err) = ShellResult(2, listOf("x"), listOf("y"))
        assertEquals(2, code); assertEquals(listOf("x"), out); assertEquals(listOf("y"), err)
        assertEquals(ShellResult.JOB_NOT_EXECUTED, -1)
    }

    @Test fun shellResultStdErrAccessorsOnShellResultType() {
        val r = result(0, listOf("a", null), listOf("b", null))
        assertEquals(listOf("a"), r.stdout); assertEquals(listOf("b"), r.stderr)
    }
}
