package com.valhalla.superuser.internal

import com.valhalla.superuser.NoShellException
import com.valhalla.superuser.Shell
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StartupLifecycleTest {
    private class BlockedProcess : Process() {
        val destroyed = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val input = object : InputStream() {
            override fun read(): Int {
                try { destroyed.await() } finally { readFinished.countDown() }
                return -1
            }
            override fun close() { destroyed.countDown() }
        }
        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { destroyed.await(); return 1 }
        override fun exitValue(): Int {
            if (destroyed.count != 0L) throw IllegalThreadStateException()
            return 1
        }
        override fun destroy() { destroyed.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun timedOutHandshakeDestroysProcessAndStopsReader() {
        val process = BlockedProcess()
        val attempt = StartupAttempt(1) { BuilderImpl().build(process) }
        assertFailsWith<NoShellException> { attempt.await() }
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS))
        assertTrue(process.readFinished.await(2, TimeUnit.SECONDS))
        assertTrue(attempt.finished.await(2, TimeUnit.SECONDS))
    }

    @Test fun initializerFailureDestroysItsShell() {
        val process = Runtime.getRuntime().exec(arrayOf("/bin/sh"))
        val builder = BuilderImpl()
        builder.setInitializers(FailingInitializer::class.java)
        assertFailsWith<NoShellException> { builder.build(process) }
        assertTrue(process.waitFor(2, TimeUnit.SECONDS))
    }

    @Test fun laterInitializationWorksAfterTimeout() {
        val attempt = StartupAttempt(1) { BuilderImpl().build(BlockedProcess()) }
        assertFailsWith<NoShellException> { attempt.await() }
        assertTrue(attempt.finished.await(2, TimeUnit.SECONDS))
        val process = Runtime.getRuntime().exec(arrayOf("/bin/sh"))
        val shell = BuilderImpl().build(process)
        assertTrue(shell.isAlive)
        shell.close()
        assertTrue(process.waitFor(2, TimeUnit.SECONDS))
    }

    class FailingInitializer : Shell.Initializer() {
        override fun onInit(shell: Shell): Boolean = false
    }
}
