package com.valhalla.superuser.ktx

import com.valhalla.superuser.CallbackList
import com.valhalla.superuser.NoShellException
import com.valhalla.superuser.Shell
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends until this [Shell.Job] completes and returns its full [Shell.Result]
 * (exit code + stdout + stderr preserved). Replaces the [Shell.Job.submit] callback.
 *
 * The continuation resumes on the job's completing worker thread (`submit(null, cb)`),
 * so the caller's coroutine context governs dispatch — no `Dispatchers.Main` round-trip.
 */
suspend fun Shell.Job.await(): Shell.Result = suspendCancellableCoroutine { cont ->
    submit(null) { result ->
        if (cont.isActive) cont.resume(result)
    }
}

/**
 * Streams this job's output as a lossless, stream-tagged [Flow] of [ShellLine].
 *
 * Both STDOUT and STDERR are delivered ([ShellLine.isError] distinguishes them). The channel is
 * UNLIMITED: the root pipe must be drained continuously, so output is never dropped and the drain
 * is never back-pressured. The flow completes when the command ends and closes with the failure
 * cause on a transport error.
 *
 * Cancellation contract: cancelling the collector stops emission and releases references, but a
 * command already running on the shared shell **drains to completion in the background** (its
 * output is discarded). Interrupting an in-flight command / isolating long-running streams on a
 * dedicated shell is not supported in this release.
 */
public fun Shell.Job.asFlow(): Flow<ShellLine> = callbackFlow {
    fun lineSink(isError: Boolean) = object : CallbackList<String?>() {
        override fun onAddElement(e: String?) {
            if (e != null) trySendBlocking(ShellLine(e, isError))
        }
    }
    to(lineSink(isError = false), lineSink(isError = true))
    submit(null) { result ->
        if (result.code == Shell.Result.JOB_NOT_EXECUTED) {
            close(NoShellException("Shell job did not execute (code ${result.code})"))
        } else {
            close()
        }
    }
    awaitClose { /* stop emitting; the engine's gobbler keeps the pipe drained (see KDoc). */ }
}.buffer(Channel.UNLIMITED)

/**
 * Gets the main shell instance via coroutines, without blocking the calling thread.
 * Resumes exceptionally with the cause (or [NoShellException]) if shell init hard-fails.
 */
suspend fun getShellAwait(): Shell = suspendCancellableCoroutine { cont ->
    Shell.getShell(
        object : Shell.GetShellCallback {
            override fun onShell(shell: Shell) {
                if (cont.isActive) cont.resume(shell)
            }

            override fun onShellDied(error: Throwable?) {
                if (cont.isActive) {
                    cont.resumeWithException(error ?: NoShellException("Root shell initialization failed"))
                }
            }
        }
    )
}