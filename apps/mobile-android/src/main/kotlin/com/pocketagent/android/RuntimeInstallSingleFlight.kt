package com.pocketagent.android

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class RuntimeInstallCandidate<Payload>(
    val fingerprint: String,
    val payload: Payload,
)

internal sealed interface RuntimeInstallOutcome {
    data object Installed : RuntimeInstallOutcome
    data object Coalesced : RuntimeInstallOutcome
    data object Skipped : RuntimeInstallOutcome
    data object PublishedDelegateStale : RuntimeInstallOutcome
    data class Deferred(val detail: String) : RuntimeInstallOutcome
    data class Rejected(val replacement: RuntimeReplacementResult) : RuntimeInstallOutcome
}

internal sealed interface RuntimeInstallPreflight {
    data object Ready : RuntimeInstallPreflight
    data class Deferred(val detail: String) : RuntimeInstallPreflight
}

/**
 * Serializes production runtime installation on an already-background caller.
 *
 * The candidate and installed fingerprint are both read after ownership is acquired. Therefore a
 * waiter coalesces with the completed owner instead of building a second facade from a stale read.
 */
internal class RuntimeInstallSingleFlight {
    private val owner = ReentrantLock()

    @Suppress("LongParameterList")
    fun <Payload, Delegate> install(
        shouldInstall: () -> Boolean,
        readCandidate: () -> RuntimeInstallCandidate<Payload>,
        readInstalledFingerprint: () -> String?,
        build: (Payload) -> Delegate,
        replace: (Payload, Delegate) -> RuntimeReplacementResult,
        finalizePublished: (Payload, Delegate) -> Boolean,
        commitFingerprint: (String) -> Unit,
        onCoalesced: (Payload) -> Unit,
        preflight: (Payload) -> RuntimeInstallPreflight = { RuntimeInstallPreflight.Ready },
    ): RuntimeInstallOutcome {
        return owner.withLock {
            if (!shouldInstall()) {
                return@withLock RuntimeInstallOutcome.Skipped
            }
            val candidate = readCandidate()
            if (candidate.fingerprint == readInstalledFingerprint()) {
                onCoalesced(candidate.payload)
                return@withLock RuntimeInstallOutcome.Coalesced
            }
            when (val readiness = preflight(candidate.payload)) {
                RuntimeInstallPreflight.Ready -> Unit
                is RuntimeInstallPreflight.Deferred -> {
                    return@withLock RuntimeInstallOutcome.Deferred(readiness.detail)
                }
            }
            val delegate = build(candidate.payload)
            val replacement = replace(candidate.payload, delegate)
            if (!replacement.success) {
                return@withLock RuntimeInstallOutcome.Rejected(replacement)
            }
            if (!finalizePublished(candidate.payload, delegate)) {
                return@withLock RuntimeInstallOutcome.PublishedDelegateStale
            }
            commitFingerprint(candidate.fingerprint)
            RuntimeInstallOutcome.Installed
        }
    }

    fun <T> runExclusive(block: () -> T): T = owner.withLock(block)
}
