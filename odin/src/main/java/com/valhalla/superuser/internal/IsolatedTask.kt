package com.valhalla.superuser.internal

import com.valhalla.superuser.*
import com.valhalla.superuser.ShellUtils.escapedString
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The execution shell waits; a separately acquired shell owns signalling and group verification. */
internal class IsolatedTask(private val shell: ShellImpl, private val commands: Array<out String>) : Shell.Task {
    val result = CompletableFuture<JobOutcome>()
    @Volatile var state = JobState.PREPARED
        private set
    @Volatile private var cancelled = false
    @Volatile private var started = false
    @Volatile private var control: ShellImpl? = null
    @Volatile private var group: Long? = null
    @Volatile private var cancelDeadline = Long.MAX_VALUE
    @Volatile private var deadlineAlarm: java.util.concurrent.ScheduledFuture<*>? = null
    private var terminal: JobOutcome? = null

    @Synchronized fun submit() {
        if (state != JobState.PREPARED) return
        state = JobState.QUEUED
        shell.submitTask(this)
    }

    @Synchronized fun cancel(): Boolean {
        if (result.isDone || terminal != null) return false
        if (cancelled) return true
        cancelled = true
        if (state == JobState.PREPARED || state == JobState.QUEUED) {
            finish(JobOutcomeKind.CANCELLED, null, emptyList(), emptyList(), true, true, null)
        } else {
            state = JobState.CANCELLING
            cancelDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            deadlineAlarm = watchdog.schedule({
                if (!result.isDone) {
                    shell.close()
                    control?.close()
                }
            }, 5, TimeUnit.SECONDS)
        }
        return true
    }

    override fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream) {
        synchronized(this) {
            if (result.isDone) return
            state = JobState.RUNNING
        }
        var dir: File? = null
        var monitor: java.util.concurrent.Future<*>? = null
        val done = java.util.concurrent.atomic.AtomicBoolean()
        val monitorFinished = java.util.concurrent.CountDownLatch(1)
        var confirmed = false
        var exit: Int? = null
        var failure: String? = null
        try {
            // Verify signalling authority before any user command can be dispatched.
            control = shell.buildControlShell()
            // The control probe verifies required tools before dispatching user work.
            val capability = control!!.newJob().add("test -x /system/bin/toybox && /system/bin/toybox setsid -w /system/bin/sh -c 'exit 0'").exec()
            check(capability.code == 0) { "Isolated execution requires toybox setsid wait mode" }
            if (cancelled) return
            val cache = Utils.getContext().cacheDir
            dir = File(cache, "odin-job-${java.util.UUID.randomUUID()}")
            check(dir.mkdir()) { "Cannot create isolated job directory" }
            val script = File(dir, "script")
            val pid = File(dir, "group")
            val out = File(dir, "out")
            val err = File(dir, "err")
            script.writeText("printf '%s\\n' \"\$\$\" > ${escapedString(pid.path)}\n" + commands.joinToString("\n") + "\n")
            val wrapper = "( /system/bin/toybox setsid -w /system/bin/sh ${escapedString(script.path)} > ${escapedString(out.path)} 2> ${escapedString(err.path)} & __odin_pid=\$!; wait \"\$__odin_pid\" )"
            if (cancelled) return
            monitor = StartupAttempt.executor.submit {
                try {
                    var termAt: Long? = null
                    while (!done.get()) {
                        group = pid.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: group
                        val g = group
                        if (cancelled && g != null) {
                            val now = System.nanoTime()
                            if (termAt == null) {
                                signal(g, "TERM")
                                termAt = now
                            } else if (now - termAt >= TimeUnit.MILLISECONDS.toNanos(300)) {
                                signal(g, "KILL")
                            }
                        }
                        if (cancelled && System.nanoTime() >= cancelDeadline) {
                            shell.close()
                            control?.close()
                            break
                        }
                        Thread.sleep(25)
                    }
                } finally { monitorFinished.countDown() }
            }
            val wrapperResult = ResultHolder()
            val job = object : JobTask() {
                init { add(wrapper); callback = wrapperResult; abortTransport = { shell.close() } }
                override fun exec(): Shell.Result = error("internal")
                override fun submit(executor: java.util.concurrent.Executor?, cb: Shell.ResultCallback?) = error("internal")
                override fun enqueue(): java.util.concurrent.Future<Shell.Result?> = error("internal")
            }
            synchronized(this) {
                if (cancelled) return
                // Dispatch is committed here. Cancellation after this point terminates execution.
                started = true
            }
            job.run(stdin, stdout, stderr)
            done.set(true)
            if (!monitorFinished.await(2, TimeUnit.SECONDS)) {
                control?.close()
                throw java.io.IOException("Cancellation worker did not stop")
            }
            state = JobState.DRAINING
            group = pid.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: group
            exit = wrapperResult.result.code.takeIf { it >= 0 }
            val g = group
            if (g != null) {
                // A parent can exit with children still running: retire that remaining group too.
                if (groupAlive(g)) {
                    signal(g, "TERM")
                    val grace = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
                    while (groupAlive(g) && System.nanoTime() < grace) Thread.sleep(25)
                    if (groupAlive(g)) signal(g, "KILL")
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (groupAlive(g) && System.nanoTime() < deadline) Thread.sleep(25)
                confirmed = !groupAlive(g)
            }
            if (!confirmed) {
                shell.close()
                failure = "Process-group termination could not be confirmed"
            }
            val kind = when {
                !confirmed -> JobOutcomeKind.TERMINATION_UNCONFIRMED
                cancelled -> JobOutcomeKind.CANCELLED
                exit == null -> JobOutcomeKind.FAILED
                else -> JobOutcomeKind.EXITED
            }
            finish(kind, exit, out.takeIf { it.exists() }?.readLines().orEmpty(), err.takeIf { it.exists() }?.readLines().orEmpty(), confirmed, job.readersStopped, failure)
        } catch (problem: Exception) {
            failure = problem.message ?: problem.javaClass.simpleName
            if (started) shell.close()
            finish(if (started) JobOutcomeKind.TERMINATION_UNCONFIRMED else JobOutcomeKind.FAILED, exit, emptyList(), emptyList(), !started, !started, failure)
        } finally {
            done.set(true)
            deadlineAlarm?.cancel(false)
            monitor?.cancel(true)
            control?.close()
            if (monitor != null) {
                var interrupted = false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (monitorFinished.count != 0L && System.nanoTime() < deadline) {
                    try { monitorFinished.await((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
            if (terminal?.terminationConfirmed != false) dir?.deleteRecursively()
            if (!result.isDone && cancelled && !started) {
                finish(JobOutcomeKind.CANCELLED, null, emptyList(), emptyList(), true, true, null)
            }
            synchronized(this) {
                terminal?.let { outcome ->
                    state = JobState.FINISHED
                    result.complete(outcome)
                }
            }
        }
    }

    private fun signal(g: Long, signal: String) {
        require(g > 1) { "Invalid process group" }
        control!!.newJob().add("kill -$signal -- -$g").exec()
    }

    private fun groupAlive(g: Long): Boolean {
        require(g > 1) { "Invalid process group" }
        val rows = arrayListOf<String?>()
        val r = control!!.newJob().add("/system/bin/toybox ps -A -o PGID,STAT").to(rows, null).exec()
        check(r.code == 0) { "Cannot verify process group" }
        return rows.any { row ->
            val fields = row?.trim()?.split(Regex("\\s+")).orEmpty()
            fields.size >= 2 && fields[0].toLongOrNull() == g && !fields[1].startsWith("Z")
        }
    }

    @Synchronized private fun finish(kind: JobOutcomeKind, exit: Int?, out: List<String>, err: List<String>, confirmed: Boolean, drained: Boolean, failure: String?) {
        if (result.isDone) return
        val outcome = JobOutcome(kind, exit, java.util.Collections.unmodifiableList(out.toList()), java.util.Collections.unmodifiableList(err.toList()), started, confirmed, drained, shell.isAlive, failure)
        if (!started && (state == JobState.PREPARED || state == JobState.QUEUED)) {
            state = JobState.FINISHED
            result.complete(outcome)
        } else terminal = outcome
    }

    companion object {
        private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "odin-job-deadline").apply { isDaemon = true }
        }
    }

    override fun shellDied() {
        finish(JobOutcomeKind.FAILED, null, emptyList(), emptyList(), true, true, "Shell unavailable before dispatch")
    }
}
