package com.valhalla.superuser.internal

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.*

class JobFramingTest {
    @Test fun missingEndMarkerIsTransportFailure() {
        assertFailsWith<IOException> { StreamGobbler.OUT(ByteArrayInputStream("partial\n".toByteArray()), arrayListOf()).call() }
    }

    @Test fun tailWithoutNewlineIsPreservedBeforeEndMarker() {
        val output = arrayListOf<String?>()
        val bytes = "tail${JobTask.END_UUID}\n7\n".toByteArray()
        assertEquals(7, StreamGobbler.OUT(ByteArrayInputStream(bytes), output).call())
        assertEquals(listOf<String?>("tail"), output)
    }
}
