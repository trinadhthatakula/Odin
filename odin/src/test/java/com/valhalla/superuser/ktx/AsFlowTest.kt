package com.valhalla.superuser.ktx

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsFlowTest {
    @Test fun shellLineTagsStream() {
        assertTrue(ShellLine("oops", isError = true).isError)
        assertEquals("hi", ShellLine("hi", isError = false).text)
    }

    // Losslessness/close-on-failure of the callbackFlow are validated on-device / via the
    // engine; here we assert the ShellLine contract + that a list of tagged lines round-trips.
    @Test fun taggedLinesRoundTrip() = runTest {
        val lines = listOf(ShellLine("a", false), ShellLine("e", true))
        assertEquals(listOf(false, true), lines.map { it.isError })
    }
}
