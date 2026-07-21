package com.valhalla.superuser.ktx

import com.valhalla.superuser.NoShellException
import com.valhalla.superuser.Shell
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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
 * Converts a Shell Job's output into a reactive Flow.
 * Replaces the "CallbackList".
 *
 * Usage:
 * Shell.cmd("logcat").asFlow().collect { line -> ... }
 */
fun Shell.Job.asFlow(): Flow<String> = callbackFlow {
    // We create a custom list that emits to the flow when items are added.
    val flowList = object : java.util.ArrayList<String?>() {
        override fun add(element: String?): Boolean {
            element?.let { trySend(it) }
            return super.add(element)
        }
    }

    // Direct output to our flow-emitting list
    to(flowList)

    // Execute asynchronously using object expression for the callback
    submit(object : Shell.ResultCallback {
        override fun onResult(out: Shell.Result) {
            close() // Close the flow when the job is done
        }
    })

    awaitClose {
        // Handle cancellation if necessary, though libsu jobs might not support explicit cancel
    }
}

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