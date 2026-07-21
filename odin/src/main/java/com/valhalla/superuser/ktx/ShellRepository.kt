package com.valhalla.superuser.ktx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Interface for interacting with the Root Shell.
 * Inject this into your ViewModels via Koin.
 * DO NOT use static Shell.shell calls in your UI layer.
 */
public interface ShellRepository {
    public suspend fun isRootGranted(): Boolean

    /**
     * Runs [commands] as one shell job and returns a lossless [ShellResult].
     *
     * Never throws for shell/command failure: a transport failure (dead shell / broken pipe /
     * [NoShellException]) yields `ShellResult(ShellResult.JOB_NOT_EXECUTED, emptyList(), listOf(msg))`.
     * A completed command returns its real exit code (including non-zero). `CancellationException`
     * always propagates. `vararg` runs as a single job → one combined [ShellResult] (last-command code).
     */
    public suspend fun exec(vararg commands: String): ShellResult

    /**
     * Legacy lossy runner kept for source compatibility.
     *
     * Returns a [kotlin.Result] whose success value is only the STDOUT lines; the exit code and
     * STDERR are discarded (a non-zero exit becomes `Result.failure`). Prefer [exec], which
     * preserves all three via [ShellResult].
     */
    @Deprecated(
        "Lossy: drops exit code + stderr. Use exec() for a lossless ShellResult.",
        ReplaceWith("exec(command)")
    )
    public suspend fun runCommand(command: String): Result<List<String>>

    /**
     * Legacy lossy multi-command runner kept for source compatibility.
     *
     * Runs [commands] as one job and returns a [kotlin.Result] whose success value is only the
     * combined STDOUT lines; the exit code and STDERR are discarded. Prefer [exec], which
     * preserves all three via [ShellResult].
     */
    @Deprecated(
        "Lossy: drops exit code + stderr. Use exec() for a lossless ShellResult.",
        ReplaceWith("exec(*commands)")
    )
    public suspend fun runCommands(vararg commands: String): Result<List<String>>
}

public class RealShellRepository : ShellRepository {

    // Lazy, bounded, failure-safe root check. A hard shell-init failure now resumes getShellAwait()
    // exceptionally (via onShellDied); the timeout additionally covers a worker that never returns.
    // Either way this UI-gating probe resolves to false instead of suspending indefinitely.
    override suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(SHELL_INIT_TIMEOUT_MS) {
            try {
                getShellAwait().isRoot
            } catch (e: Exception) {
                // Rethrow cancellation so withTimeoutOrNull handles the timeout (and structured
                // concurrency is preserved); any other failure resolves the probe to false.
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
        } ?: false
    }

    override suspend fun exec(vararg commands: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            getShellAwait().newJob().add(*commands).to(ArrayList(), ArrayList()).await().toShellResult()
        } catch (e: Exception) {
            // Never-throws for shell/command failure; cancellation still propagates.
            if (e is kotlinx.coroutines.CancellationException) throw e
            ShellResult(ShellResult.JOB_NOT_EXECUTED, emptyList(), listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    @Deprecated("Lossy: drops exit code + stderr. Use exec() for a lossless ShellResult.", ReplaceWith("exec(command)"))
    override suspend fun runCommand(command: String): Result<List<String>> = execToLegacy(command)

    @Deprecated("Lossy: drops exit code + stderr. Use exec() for a lossless ShellResult.", ReplaceWith("exec(*commands)"))
    override suspend fun runCommands(vararg commands: String): Result<List<String>> = execToLegacy(*commands)

    // Adapts exec() back to the old kotlin.Result<List<String>> contract for the deprecated shims.
    private suspend fun execToLegacy(vararg commands: String): Result<List<String>> {
        val r = exec(*commands)
        return if (r.isSuccess) Result.success(r.stdout)
        else Result.failure(IOException("Command failed with code ${r.code}: ${r.stderr.joinToString("\n")}"))
    }

    private companion object {
        // Upper bound for shell-init before the root probe gives up and reports "no root".
        private const val SHELL_INIT_TIMEOUT_MS = 10_000L
    }
}