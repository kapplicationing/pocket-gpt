package com.pocketagent.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeInstallSingleFlightTest {
    @Test
    fun `same fingerprint waiters build replace finalize and warm once`() {
        val coordinator = RuntimeInstallSingleFlight()
        val installedFingerprint = AtomicReference<String?>(null)
        val candidateReads = AtomicInteger(0)
        val builds = AtomicInteger(0)
        val replacements = AtomicInteger(0)
        val warmupCancellations = AtomicInteger(0)
        val finalizations = AtomicInteger(0)
        val warmups = AtomicInteger(0)
        val coalesced = AtomicInteger(0)
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        fun install(): RuntimeInstallOutcome {
            return coordinator.install(
                shouldInstall = { true },
                readCandidate = {
                    candidateReads.incrementAndGet()
                    RuntimeInstallCandidate(fingerprint = "same-fingerprint", payload = "graph")
                },
                readInstalledFingerprint = installedFingerprint::get,
                build = {
                    builds.incrementAndGet()
                    buildEntered.countDown()
                    releaseBuild.await()
                    "delegate"
                },
                replace = { _, _ ->
                    warmupCancellations.incrementAndGet()
                    replacements.incrementAndGet()
                    RuntimeReplacementResult.replaced()
                },
                finalizePublished = { _, _ ->
                    finalizations.incrementAndGet()
                    warmups.incrementAndGet()
                    true
                },
                commitFingerprint = installedFingerprint::set,
                onCoalesced = { coalesced.incrementAndGet() },
            )
        }

        try {
            val first = executor.submit<RuntimeInstallOutcome> { install() }
            assertTrue(buildEntered.await(2, TimeUnit.SECONDS))
            val second = executor.submit<RuntimeInstallOutcome> {
                secondStarted.countDown()
                install()
            }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFalse(second.isDone, "same-fingerprint waiter must coalesce behind the owner")

            releaseBuild.countDown()
            assertIs<RuntimeInstallOutcome.Installed>(first.get(2, TimeUnit.SECONDS))
            assertIs<RuntimeInstallOutcome.Coalesced>(second.get(2, TimeUnit.SECONDS))
            assertEquals(2, candidateReads.get(), "waiter must re-read after acquiring ownership")
            assertEquals(1, builds.get())
            assertEquals(1, replacements.get())
            assertEquals(1, warmupCancellations.get(), "coalesced waiter must not cancel warmup")
            assertEquals(1, finalizations.get())
            assertEquals(1, warmups.get())
            assertEquals(1, coalesced.get())
            assertEquals("same-fingerprint", installedFingerprint.get())
        } finally {
            releaseBuild.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `returning to installed fingerprint retries retained cleanup before coalescing`() {
        val coordinator = RuntimeInstallSingleFlight()
        val installedFingerprint = AtomicReference<String?>("fingerprint-f")
        val candidateFingerprint = AtomicReference("fingerprint-g")
        val retainedCleanupPending = AtomicBoolean(false)
        val allowRetainedCleanup = AtomicBoolean(false)
        val cleanupAttempts = AtomicInteger(0)
        val builds = AtomicInteger(0)
        val replacements = AtomicInteger(0)
        val coalesced = AtomicInteger(0)

        fun install(): RuntimeInstallOutcome {
            return coordinator.install(
                shouldInstall = { true },
                readCandidate = {
                    RuntimeInstallCandidate(
                        fingerprint = candidateFingerprint.get(),
                        payload = "graph",
                    )
                },
                readInstalledFingerprint = installedFingerprint::get,
                preflight = {
                    if (!retainedCleanupPending.get()) {
                        RuntimeInstallPreflight.Ready
                    } else {
                        cleanupAttempts.incrementAndGet()
                        if (allowRetainedCleanup.get()) {
                            retainedCleanupPending.set(false)
                            RuntimeInstallPreflight.Ready
                        } else {
                            RuntimeInstallPreflight.Deferred("retained-g-cleanup-pending")
                        }
                    }
                },
                build = {
                    builds.incrementAndGet()
                    "delegate-${candidateFingerprint.get()}"
                },
                replace = { _, _ ->
                    replacements.incrementAndGet()
                    retainedCleanupPending.set(true)
                    RuntimeReplacementResult.rejected("G_CLOSE_REJECTED")
                },
                finalizePublished = { _, _ -> error("rejected G must not finalize") },
                commitFingerprint = installedFingerprint::set,
                onCoalesced = { coalesced.incrementAndGet() },
            )
        }

        assertIs<RuntimeInstallOutcome.Rejected>(install())
        assertEquals("fingerprint-f", installedFingerprint.get())
        assertEquals(1, builds.get())
        assertEquals(1, replacements.get())

        candidateFingerprint.set("fingerprint-f")
        val deferredF = assertIs<RuntimeInstallOutcome.Deferred>(install())
        assertEquals("retained-g-cleanup-pending", deferredF.detail)
        assertEquals(1, cleanupAttempts.get())
        assertEquals(0, coalesced.get())
        assertEquals(1, builds.get(), "cleanup deferral must not build another delegate")

        allowRetainedCleanup.set(true)
        assertIs<RuntimeInstallOutcome.Coalesced>(install())
        assertEquals(2, cleanupAttempts.get())
        assertFalse(retainedCleanupPending.get())
        assertEquals(1, coalesced.get())
        assertEquals(1, builds.get(), "cleanup retry followed by coalescence must not rebuild F")
        assertEquals(1, replacements.get())
    }

    @Test
    fun `stale published delegate does not commit fingerprint`() {
        val installedFingerprint = AtomicReference<String?>(null)
        val coordinator = RuntimeInstallSingleFlight()

        val outcome = coordinator.install(
            shouldInstall = { true },
            readCandidate = { RuntimeInstallCandidate("fingerprint", "graph") },
            readInstalledFingerprint = installedFingerprint::get,
            build = { "delegate" },
            replace = { _, _ -> RuntimeReplacementResult.replaced() },
            finalizePublished = { _, _ -> false },
            commitFingerprint = installedFingerprint::set,
            onCoalesced = { error("must not coalesce") },
        )

        assertIs<RuntimeInstallOutcome.PublishedDelegateStale>(outcome)
        assertEquals(null, installedFingerprint.get())
    }
}
