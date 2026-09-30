package com.valhalla.superuser.internal

import com.valhalla.superuser.NoShellException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the real worker separately from its cancellable result. */
internal class StartupAttempt(timeoutSeconds: Long, build: () -> ShellImpl) {
    val result = CompletableFuture<ShellImpl>()
    val finished = CountDownLatch(1)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    private val cancelled = AtomicBoolean()
    private val resources = mutableListOf<() -> Unit>()
    @Volatile private var worker: Thread? = null
    @Volatile var provisional: ShellImpl? = null

    init {
        executor.execute {
            worker = Thread.currentThread()
            current.set(this)
            try {
                ensureActive()
                val shell = build()
                synchronized(this) {
                    ensureActive()
                    if (!result.complete(shell)) shell.close()
                    else { resources.clear(); provisional = null }
                }
            } catch (failure: Throwable) {
                abort(failure)
            } finally {
                current.remove()
                worker = null
                finished.countDown()
            }
        }
        val alarm = timer.schedule({ abort(NoShellException("Shell initialization deadline exceeded")) }, timeoutSeconds, TimeUnit.SECONDS)
        result.whenComplete { _, _ -> alarm.cancel(false) }
    }

    @Synchronized fun own(cleanup: () -> Unit) {
        if (cancelled.get()) {
            cleanup()
            throw NoShellException("Shell initialization was cancelled")
        }
        resources.add(cleanup)
    }

    fun remainingNanos(): Long {
        ensureActive()
        return (deadline - System.nanoTime()).coerceAtLeast(1)
    }

    fun ensureActive() {
        if (cancelled.get() || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
            throw NoShellException("Shell initialization deadline exceeded or interrupted")
        }
    }

    @Synchronized fun abort(failure: Throwable) {
        if (result.isDone && !result.isCompletedExceptionally) return
        if (cancelled.compareAndSet(false, true)) {
            resources.forEach { cleanup -> runCatching(cleanup) }
            resources.clear()
            worker?.interrupt()
            result.completeExceptionally(failure)
        }
    }

    fun await(): ShellImpl = try {
        result.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
    } catch (failure: TimeoutException) {
        abort(NoShellException("Shell initialization deadline exceeded", failure))
        throw NoShellException("Shell initialization deadline exceeded", failure)
    } catch (failure: java.util.concurrent.ExecutionException) {
        throw (failure.cause as? NoShellException ?: NoShellException("Unable to initialize shell", failure.cause))
    }

    companion object {
        val current = ThreadLocal<StartupAttempt>()
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "odin-startup").apply { isDaemon = true } }
        private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "odin-startup-deadline").apply { isDaemon = true } }
    }
}
