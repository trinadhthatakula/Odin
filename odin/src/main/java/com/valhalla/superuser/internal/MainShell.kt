package com.valhalla.superuser.internal

import androidx.annotation.GuardedBy
import androidx.annotation.RestrictTo
import com.valhalla.superuser.NoShellException
import com.valhalla.superuser.RootAvailability
import com.valhalla.superuser.RootAvailabilityKind
import com.valhalla.superuser.Shell
import com.valhalla.superuser.Shell.GetShellCallback
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

@RestrictTo(RestrictTo.Scope.LIBRARY)
internal object MainShell {
    @GuardedBy("self")
    private val mainShell = arrayOfNulls<ShellImpl>(1)

    @GuardedBy("class")
    private var mainBuilder: BuilderImpl? = null

    private val lock = Any()
    private var attempt: StartupAttempt? = null
    private var stale = false
    private var generation = 0L
    private var retiring: ShellImpl? = null
    private var refreshing: CompletableFuture<RootAvailability>? = null

    fun invalidate() = synchronized(lock) {
        stale = true
        generation++
        Utils.invalidateRootState()
    }

    fun refresh(): RootAvailability {
        val pending = synchronized(lock) {
            refreshing?.takeIf { !it.isDone } ?: CompletableFuture<RootAvailability>().also { future ->
                refreshing = future
                stale = true
                generation++
                Utils.invalidateRootState()
                val refreshGeneration = generation
                val old = retiring ?: cached ?: attempt?.result?.takeIf { it.isDone && !it.isCompletedExceptionally }?.getNow(null)
                retiring = old
                val previous = attempt
                attempt = null
                synchronized(mainShell) { mainShell[0] = null }
                StartupAttempt.executor.execute {
                    var kind: RootAvailabilityKind
                    var error: String? = null
                    var replacement: ShellImpl? = null
                    try {
                        previous?.abort(NoShellException("Shell cache invalidated"))
                        if (previous != null && !previous.finished.await(5, TimeUnit.SECONDS)) {
                            throw NoShellException("Previous initialization worker has not stopped")
                        }
                        if (old != null && !old.retire(5, TimeUnit.SECONDS)) {
                            future.complete(RootAvailability(RootAvailabilityKind.BUSY, refreshGeneration, "Accepted shell work is still running"))
                            return@execute
                        }
                        val builder = synchronized(lock) { mainBuilder ?: BuilderImpl().also { mainBuilder = it } }
                        val fresh = StartupAttempt(builder.timeout.coerceAtMost(10)) { builder.build() }
                        synchronized(lock) { attempt = fresh }
                        replacement = fresh.await()
                        kind = if (replacement.isRoot) RootAvailabilityKind.ROOT else RootAvailabilityKind.NON_ROOT
                    } catch (failure: Exception) {
                        error = failure.message
                        kind = if (error?.contains("deadline") == true || failure is java.util.concurrent.TimeoutException) RootAvailabilityKind.TIMED_OUT else RootAvailabilityKind.FAILED
                    }
                    synchronized(lock) {
                        retiring = null
                        if (generation == refreshGeneration && replacement != null) {
                            synchronized(mainShell) { mainShell[0] = replacement }
                            Utils.setConfirmedRootState(replacement.isRoot)
                            stale = false
                        } else {
                            replacement?.close()
                            if (replacement != null) {
                                kind = RootAvailabilityKind.FAILED
                                error = "Refresh superseded by a newer invalidation"
                            }
                        }
                    }
                    future.complete(RootAvailability(kind, refreshGeneration, error))
                }
            }
        }
        return pending.get()
    }

    @JvmStatic
    fun get(): ShellImpl {
        // Initializers may acquire their own provisional shell, but other callers never see it.
        StartupAttempt.current.get()?.provisional?.let { return it }
        val needsRefresh = synchronized(lock) { stale || refreshing?.isDone == false }
        if (needsRefresh) {
            val observation = refresh()
            if (observation.kind != RootAvailabilityKind.ROOT && observation.kind != RootAvailabilityKind.NON_ROOT) {
                throw NoShellException(observation.failure ?: "Root refresh failed")
            }
        }
        val pending = synchronized(lock) {
            cached?.let { return it }
            val previous = attempt
            if (previous != null && (previous.finished.count != 0L || (!previous.result.isCompletedExceptionally && previous.result.getNow(null)?.isAlive == true))) previous
            else {
                val builder = mainBuilder ?: BuilderImpl().also { mainBuilder = it }
                StartupAttempt(builder.timeout) { builder.build() }.also { attempt = it }
            }
        }
        val shell = pending.await()
        synchronized(lock) {
            if (attempt !== pending || stale || retiring != null) {
                throw NoShellException("Shell initialization was superseded by refresh")
            }
            synchronized(mainShell) { mainShell[0] = shell }
            Utils.setConfirmedRootState(shell.isRoot)
        }
        return shell
    }

    private fun returnShell(s: Shell, e: Executor?, cb: GetShellCallback) {
        if (e == null) cb.onShell(s)
        else e.execute { cb.onShell(s) }
    }

    @JvmStatic
    fun get(executor: Executor?, callback: GetShellCallback) {
        val shell: Shell? = synchronized(lock) { if (stale || refreshing?.isDone == false) null else cached }
        if (shell != null) {
            returnShell(shell, executor, callback)
        } else {
            // Else we get shell in worker thread and call the callback when we get a Shell
            StartupAttempt.executor.execute {
                try {
                    returnShell(get(), executor, callback)
                } catch (e: Exception) {
                    // Shell creation failed. Log it, keep the worker thread alive (catch-all), AND
                    // signal the terminal failure to the callback via the same executor path
                    // returnShell uses — so awaiting callers fail fast instead of suspending forever.
                    Utils.ex(e)
                    if (e is CancellationException) throw e
                    if (executor == null) callback.onShellDied(e)
                    else executor.execute { callback.onShellDied(e) }
                }
            }
        }
    }

    val cached: ShellImpl?
        get() = synchronized(mainShell) {
            mainShell[0]?.takeIf { it.isAlive }.also { mainShell[0] = it }
        }

    fun setBuilder(builder: Shell.Builder?) = synchronized(lock) {
        check(attempt == null && cached == null) { "The main shell was already created" }
        mainBuilder = builder as BuilderImpl?
    }

    fun newJob(`in`: InputStream): Shell.Job {
        return PendingJob().add(`in`)
    }

    fun newJob(vararg commands: String?): Shell.Job {
        return PendingJob().apply {
            commands.forEach { cmd ->
                if (cmd != null) add(cmd)
            }
        }
    }
}
