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
    val isSuccess: Boolean get() = code == 0

    public companion object {
        /** The job never executed (dead shell / not run). See [Shell.Result.JOB_NOT_EXECUTED]. */
        public const val JOB_NOT_EXECUTED: Int = -1
    }
}

/** Maps a low-level [Shell.Result] to the immutable, non-null [ShellResult]. */
public fun Shell.Result.toShellResult(): ShellResult =
    ShellResult(code = code, stdout = stdout, stderr = stderr)
