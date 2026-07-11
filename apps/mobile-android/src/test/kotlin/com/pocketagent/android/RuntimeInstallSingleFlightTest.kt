package com.pocketagent.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
