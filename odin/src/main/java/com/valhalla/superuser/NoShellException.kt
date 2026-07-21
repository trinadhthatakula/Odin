package com.valhalla.superuser

/**
 * Thrown when it is impossible to construct `Shell`.
 * This is a runtime exception, and should happen very rarely.
 */
public class NoShellException : RuntimeException {
    public constructor(msg: String?) : super(msg)

    public constructor(message: String?, cause: Throwable?) : super(message, cause)
}
