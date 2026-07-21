package com.valhalla.superuser.ktx

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Drives the REAL production seam Throwable.toTransportFailureOrRethrow() — the exact never-throws
// policy exec() applies in its catch. A CancellationException must propagate (structured concurrency);
// any other Throwable becomes a transport-failure ShellResult. Altering the real policy fails these.
class ShellRepositoryCancellationTest {
    @Test fun cancellationPropagates() {
        assertFailsWith<CancellationException> {
            CancellationException("cancelled").toTransportFailureOrRethrow()
        }
    }

    @Test fun otherFailureBecomesTransportFailureResult() {
        val r = RuntimeException("boom").toTransportFailureOrRethrow()
        assertEquals(ShellResult.JOB_NOT_EXECUTED, r.code)
        assertEquals(emptyList<String>(), r.stdout)
        assertEquals(listOf("boom"), r.stderr)
    }
}
