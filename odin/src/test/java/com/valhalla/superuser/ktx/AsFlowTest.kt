package com.valhalla.superuser.ktx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AsFlowTest {
    @Test fun shellLineTagsStream() {
        assertTrue(ShellLine("oops", isError = true).isError)
        assertEquals("hi", ShellLine("hi", isError = false).text)
    }

    // Losslessness/close-on-failure of the callbackFlow are validated on-device / via the
    // engine; here we assert the ShellLine data-class contract (equality + copy).
    @Test fun shellLineDataClassContract() {
        assertEquals(ShellLine("x", false), ShellLine("x", false))
        assertNotEquals(ShellLine("x", false), ShellLine("x", true))
        assertEquals(ShellLine("x", true), ShellLine("x", false).copy(isError = true))
    }
}
