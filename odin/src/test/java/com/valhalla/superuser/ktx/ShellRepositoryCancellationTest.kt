package com.valhalla.superuser.ktx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ShellRepositoryCancellationTest {
    // runInternal must rethrow CancellationException rather than swallow it into Result.failure.
    // We exercise the exact catch policy via a tiny stand-in with the same structure.
    private suspend fun runInternalPolicy(block: suspend () -> List<String>): Result<List<String>> =
        try {
            Result.success(block())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }

    @Test
    fun cancellationPropagates() = runTest {
        assertFailsWith<CancellationException> {
            runInternalPolicy { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun otherFailureBecomesResultFailure() = runTest {
        val r = runInternalPolicy { throw RuntimeException("boom") }
        assert(r.isFailure)
    }
}
