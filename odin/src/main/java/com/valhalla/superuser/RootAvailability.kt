package com.valhalla.superuser

/** Fresh acquisition result, distinct from the identity of an already-open shell. */
public enum class RootAvailabilityKind { ROOT, NON_ROOT, TIMED_OUT, BUSY, FAILED }

/** Observation from explicit refresh. [generation] identifies the cache invalidation generation. */
public data class RootAvailability(val kind: RootAvailabilityKind, val generation: Long, val failure: String?) {
    public val isRoot: Boolean get() = kind == RootAvailabilityKind.ROOT
}
