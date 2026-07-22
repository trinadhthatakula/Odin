package com.valhalla.superuser.ktx

import com.valhalla.superuser.Shell

/**
 * Lossless, immutable result of a shell command run via [exec].
 *
 * @property code the exit code of the last command (0..255), or [JOB_NOT_EXECUTED] (-1) if the job
 *   could not run at all (dead shell / broken pipe).
 * @property stdout STDOUT lines (never null elements).
 * @property stderr STDERR lines (never null elements).
 */
public data class ShellResult(
    val code: Int,
    val stdout: List<String>,
    val stderr: List<String>,
) {
    /** `true` iff the command completed with exit code 0. */
    public val isSuccess: Boolean get() = code == 0

    public companion object {
        /** The job never executed (dead shell / not run). See [Shell.Result.JOB_NOT_EXECUTED]. */
        public const val JOB_NOT_EXECUTED: Int = -1
    }
}

/** Maps a low-level [Shell.Result] to the immutable, non-null [ShellResult]. */
public fun Shell.Result.toShellResult(): ShellResult =
    ShellResult(code = code, stdout = stdout, stderr = stderr)

/**
 * Adapts a lossless [ShellResult] back to the legacy lossy `kotlin.Result<List<String>>` contract
 * used by [ShellRepository]'s deprecated `runCommand`/`runCommands` shims: a successful (exit-0)
 * result maps to `Result.success(stdout)`; any other exit code maps to a `Result.failure` carrying an
 * [java.io.IOException] whose message is `"Command failed with code <code>: <stderr joined by \n>"`.
 *
 * `internal` (module-only) so it stays out of the public API dump, yet exists as a real seam the
 * shim tests can drive directly (the mapping policy needs no live shell).
 */
internal fun ShellResult.toLegacyResult(): Result<List<String>> =
    if (isSuccess) Result.success(stdout)
    else Result.failure(java.io.IOException("Command failed with code $code: ${stderr.joinToString("\n")}"))

/**
 * Maps a non-cancellation failure to a transport-failure [ShellResult] (code
 * [ShellResult.JOB_NOT_EXECUTED], empty stdout, the failure message as the sole stderr line);
 * rethrows [kotlinx.coroutines.CancellationException] so structured concurrency is preserved.
 *
 * This is the exact never-throws policy [ShellRepository.exec] applies in its `catch`. `internal`
 * (module-only) so it stays out of the public API dump, yet exists as a real seam the cancellation
 * test can drive directly.
 */
internal fun Throwable.toTransportFailureOrRethrow(): ShellResult {
    if (this is kotlinx.coroutines.CancellationException) throw this
    return ShellResult(ShellResult.JOB_NOT_EXECUTED, emptyList(), listOf(message ?: this::class.java.simpleName))
}
