package com.valhalla.superuser.internal

import com.valhalla.superuser.Shell
import com.valhalla.superuser.ShellUtils.cleanInputStream
import com.valhalla.superuser.ShellUtils.escapedString
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile

internal class ShellImpl(private val builder: BuilderImpl, private val process: Process) : Shell() {
    @Volatile
    override var status: Int
        private set

    private val stdIn: NoCloseOutputStream
    private val stdOut: NoCloseInputStream
    private val stdErr: NoCloseInputStream

    // Guarded by scheduleLock
    private val scheduleLock = ReentrantLock()
    private val idle: Condition = scheduleLock.newCondition()
    private val tasks = ArrayDeque<Task?>()
    private var isRunningTask = false
    private var retiring = false

    private class SyncTask(private val condition: Condition) : Task {
        private var set = false

        fun signal() {
            set = true
            condition.signal()
        }

        fun await() {
            while (!set) {
                condition.await()
            }
        }

        override fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream) {}
    }

    private class NoCloseInputStream(`in`: InputStream?) : FilterInputStream(`in`) {
        override fun close() {}

        @Throws(IOException::class)
        fun close0() {
            `in`.close()
        }
    }

    private class NoCloseOutputStream(out: OutputStream) :
        FilterOutputStream(out as? BufferedOutputStream ?: BufferedOutputStream(out)) {
        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        @Throws(IOException::class)
        override fun close() {
            out.flush()
        }

        @Throws(IOException::class)
        fun close0() {
            super.close()
        }
    }

    private val released = java.util.concurrent.atomic.AtomicBoolean()

    init {
        status = UNKNOWN
        stdIn = NoCloseOutputStream(process.outputStream)
        stdOut = NoCloseInputStream(process.inputStream)
        stdErr = NoCloseInputStream(process.errorStream)

        StartupAttempt.current.get()?.own { release() }

        // Shell checks might get stuck indefinitely
        val check = FutureTask<Int?> { this.shellCheck() }
        StartupAttempt.executor.execute(check)
        try {
            try {
                status = check.get(StartupAttempt.current.get()?.remainingNanos() ?: TimeUnit.SECONDS.toNanos(builder.timeout), TimeUnit.NANOSECONDS)!!
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause is IOException) {
                    throw cause
                } else {
                    throw IOException("Unknown ExecutionException", cause)
                }
            } catch (e: TimeoutException) {
                throw IOException("Shell check timeout", e)
            } catch (e: InterruptedException) {
                throw IOException("Shell check interrupted", e)
            }
        } catch (e: IOException) {
            check.cancel(true)
            release()
            throw e
        }
    }

    @Throws(IOException::class)
    private fun shellCheck(): Int {
        try {
            process.exitValue()
            throw IOException("Created process has terminated")
        } catch (_: IllegalThreadStateException) {
            // Process is alive
        }

        // Clean up potential garbage from InputStreams
        cleanInputStream(stdOut)
        cleanInputStream(stdErr)

        var status = NON_ROOT_SHELL
        BufferedReader(InputStreamReader(stdOut)).use { br ->
            stdIn.write(("echo SHELL_TEST\n").toByteArray(StandardCharsets.UTF_8))
            stdIn.flush()
            var s = br.readLine()
            if (s.isNullOrEmpty() || !s.contains("SHELL_TEST")) throw IOException("Created process is not a shell")

            stdIn.write(("id\n").toByteArray(StandardCharsets.UTF_8))
            stdIn.flush()
            s = br.readLine()
            if (!s.isNullOrEmpty() && s.contains("uid=0")) {
                status = ROOT_SHELL
                // noinspection ConstantConditions
                val cwd = escapedString(System.getProperty("user.dir") ?: "/")
                stdIn.write(("cd $cwd\n").toByteArray(StandardCharsets.UTF_8))
                stdIn.flush()
            }
        }
        return status
    }

    private fun release() {
        status = UNKNOWN
        if (!released.compareAndSet(false, true)) return
        // Destroy first: stream close/flush may itself wait on a blocked writer or reader.
        if (android.os.Build.VERSION.SDK_INT >= 26) process.destroyForcibly() else process.destroy()
        StartupAttempt.executor.execute { runCatching { stdIn.close0() } }
        StartupAttempt.executor.execute { runCatching { stdErr.close0() } }
        StartupAttempt.executor.execute { runCatching { stdOut.close0() } }
    }

    @Throws(InterruptedException::class)
    override fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean {
        if (status < 0) return true

        scheduleLock.lock()
        try {
            if (isRunningTask && !idle.await(timeout, unit)) return false
            close()
        } finally {
            scheduleLock.unlock()
        }

        return true
    }

    override fun close() {
        if (status < 0) return
        release()
    }

    override val isAlive: Boolean
        get() {
            // If status is unknown, it is not alive
            if (status < 0) return false

            try {
                process.exitValue()
                // Process is dead, shell is not alive
                release()
                return false
            } catch (_: IllegalThreadStateException) {
                // Process is still running
                return true
            }
        }

    @Synchronized
    @Throws(IOException::class)
    private fun exec0(task: Task) {
        if (status < 0) {
            task.shellDied()
            return
        }

        cleanInputStream(stdOut)
        cleanInputStream(stdErr)
        try {
            stdIn.write('\n'.code)
            stdIn.flush()
        } catch (_: IOException) {
            release()
            task.shellDied()
            return
        }

        if (task is JobTask) task.abortTransport = { close() }
        task.run(stdIn, stdOut, stdErr)
    }

    private fun processTasks() {
        var task: Task?
        while ((processNextTask(false).also { task = it }) != null) {
            try {
                exec0(task!!)
            } catch (_: IOException) {
            }
        }
    }

    private fun processNextTask(fromExec: Boolean): Task? {
        scheduleLock.lock()
        try {
            val task = tasks.poll()
            if (task == null) {
                isRunningTask = false
                idle.signalAll()
                return null
            }
            if (task is SyncTask) {
                task.signal()
                return null
            }
            if (fromExec) {
                // Put the task back in front of the queue
                tasks.offerFirst(task)
            } else {
                return task
            }
        } finally {
            scheduleLock.unlock()
        }
        EXECUTOR.execute { this.processTasks() }
        return null
    }

    override fun submitTask(task: Task) {
        scheduleLock.lock()
        try {
            if (retiring) { task.shellDied(); return }
            tasks.offer(task)
            if (!isRunningTask) {
                isRunningTask = true
                EXECUTOR.execute { this.processTasks() }
            }
        } finally {
            scheduleLock.unlock()
        }
    }

    @Throws(IOException::class)
    override fun execTask(task: Task) {
        scheduleLock.lock()
        try {
            if (retiring) { task.shellDied(); return }
            if (isRunningTask) {
                val sync = SyncTask(scheduleLock.newCondition())
                tasks.offer(sync)
                // Wait until it's our turn
                try {
                    sync.await()
                } catch (failure: InterruptedException) {
                    if (!tasks.remove(sync)) processNextTask(true)
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for shell", failure)
                }
            }
            isRunningTask = true
        } finally {
            scheduleLock.unlock()
        }
        try { exec0(task) } finally { processNextTask(true) }
    }

    fun retire(timeout: Long, unit: TimeUnit): Boolean {
        scheduleLock.lock()
        try {
            retiring = true
            var remaining = unit.toNanos(timeout)
            while (isRunningTask) {
                if (remaining <= 0) return false
                remaining = idle.awaitNanos(remaining)
            }
            close()
            return true
        } finally { scheduleLock.unlock() }
    }

    fun buildControlShell(): ShellImpl = builder.controlCopy().build().also {
        if (it.isRoot != isRoot) {
            it.close()
            throw IOException("Cancellation control shell has different root authority")
        }
    }

    override fun newJob(): Job {
        return ShellJob(this)
    }
}
