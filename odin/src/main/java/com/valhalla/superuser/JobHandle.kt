package com.valhalla.superuser

import com.valhalla.superuser.internal.IsolatedTask
import com.valhalla.superuser.internal.ShellImpl
import java.util.concurrent.TimeUnit

/** State of one isolated execution. A handle is single-use. */
public enum class JobState { PREPARED, QUEUED, RUNNING, CANCELLING, DRAINING, FINISHED }

/** Terminal classification, independent of the command's exit code. */
public enum class JobOutcomeKind { EXITED, CANCELLED, FAILED, TERMINATION_UNCONFIRMED }

/**
 * Immutable completion snapshot. Cancellation never implies rollback.
 * [terminationConfirmed] covers the job's process group, excluding deliberately detached children.
 * [outputDrained] means no output reader for this execution remains active.
 * A transport failure after dispatch may have applied side effects even without an exit code.
 */
public data class JobOutcome(
    val kind: JobOutcomeKind,
    val exitCode: Int?,
    val stdout: List<String>,
    val stderr: List<String>,
    val started: Boolean,
    val terminationConfirmed: Boolean,
    val outputDrained: Boolean,
    val shellReusable: Boolean,
    val failure: String?,
)

/**
 * An opt-in isolated shell execution. [submit] commits it once; [cancel] can precede submission.
 * Requesting cancellation does not wait for termination. Await [completion] before releasing a
 * consumer lease. Cancelling a coroutine that waits for this handle does not cancel execution.
 * Commands share state within the job; state changes never persist into later jobs.
 */
public class JobHandle internal constructor(shell: ShellImpl, commands: Array<out String>) {
    internal val task = IsolatedTask(shell, commands.copyOf())
    public val state: JobState get() = task.state

    /** An observation-only future. Future.cancel does not request command termination. */
    public val completion: java.util.concurrent.CompletionStage<JobOutcome> get() = task.result.thenApply { it }

    /** Submit once; repeated calls return this handle without resubmission. */
    public fun submit(): JobHandle { task.submit(); return this }

    /** Idempotently request termination. Returns false if completion already won the race. */
    public fun cancel(): Boolean = task.cancel()

    /** Blocking Java-compatible completion wait; never use on the main thread. */
    public fun await(timeout: Long, unit: TimeUnit): JobOutcome = task.result.get(timeout, unit)
}
