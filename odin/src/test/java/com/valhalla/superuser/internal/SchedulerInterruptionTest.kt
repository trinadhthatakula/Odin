package com.valhalla.superuser.internal

import com.valhalla.superuser.Shell
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SchedulerInterruptionTest {
    @Test fun interruptedQueuedSynchronousTaskDoesNotBlockLaterJobs() {
        val shell = BuilderImpl().build(Runtime.getRuntime().exec(arrayOf("/bin/sh")))
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiterStarted = CountDownLatch(1)
        val waiterDone = CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        try {
            shell.submitTask(object : Shell.Task {
                override fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream) {
                    running.countDown(); release.await(5, TimeUnit.SECONDS)
                }
            })
            assertTrue(running.await(2, TimeUnit.SECONDS))
            val waiter = Thread {
                waiterStarted.countDown()
                try {
                    shell.execTask(object : Shell.Task {
                        override fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream) { error("cancelled waiter executed") }
                    })
                } catch (_: IOException) { interrupted.set(Thread.currentThread().isInterrupted) }
                finally { waiterDone.countDown() }
            }
            waiter.start()
            assertTrue(waiterStarted.await(2, TimeUnit.SECONDS))
            waiter.interrupt()
            assertTrue(waiterDone.await(2, TimeUnit.SECONDS))
            assertTrue(interrupted.get())
            release.countDown()
            assertEquals(0, shell.newJob().add("true").exec().code)
        } finally { release.countDown(); shell.close() }
    }
}
