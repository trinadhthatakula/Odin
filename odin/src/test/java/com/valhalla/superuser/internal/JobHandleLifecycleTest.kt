package com.valhalla.superuser.internal

import com.valhalla.superuser.*
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JobHandleLifecycleTest {
    private fun shell() = BuilderImpl().build(Runtime.getRuntime().exec(arrayOf("/bin/sh")))

    @Test fun preparedCancellationIsTerminalAndSubmissionCannotReviveIt() {
        shell().use { shell ->
            val handle = shell.prepareIsolatedJob("echo forbidden")
            assertTrue(handle.cancel())
            assertFalse(handle.cancel())
            handle.submit().submit()
            val outcome = handle.await(1, TimeUnit.SECONDS)
            assertEquals(JobOutcomeKind.CANCELLED, outcome.kind)
            assertFalse(outcome.started)
            assertTrue(outcome.terminationConfirmed)
            assertEquals(JobState.FINISHED, handle.state)
        }
    }

    @Test fun queuedCancellationCompletesOnceAndNeverDispatches() {
        shell().use { shell ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            shell.submitTask(object : Shell.Task {
                override fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            })
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val handle = shell.submitIsolated("echo forbidden")
                val completions = java.util.concurrent.atomic.AtomicInteger()
                handle.completion.whenComplete { _, _ -> completions.incrementAndGet() }
                assertTrue(handle.cancel())
                assertFalse(handle.await(1, TimeUnit.SECONDS).started)
                release.countDown()
                assertEquals(0, shell.newJob().add("true").exec().code)
                assertEquals(1, completions.get())
            } finally { release.countDown() }
        }
    }

    @Test fun cancellingObservationDoesNotCancelTheExecutionHandle() {
        shell().use { shell ->
            val handle = shell.prepareIsolatedJob("true")
            val observation = handle.completion.toCompletableFuture()
            assertTrue(observation.cancel(true))
            assertEquals(JobState.PREPARED, handle.state)
            assertTrue(handle.cancel())
            assertEquals(JobOutcomeKind.CANCELLED, handle.await(1, TimeUnit.SECONDS).kind)
        }
    }
}
