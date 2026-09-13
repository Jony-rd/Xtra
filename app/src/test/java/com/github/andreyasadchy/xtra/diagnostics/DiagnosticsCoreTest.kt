package com.github.andreyasadchy.xtra.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class DiagnosticsCoreTest {
    @Test
    fun ringRetainsOnlyNewestEntries() {
        val ring = DiagnosticsEntryRing(500)
        repeat(501) { sequence -> ring.append(entry(sequence.toLong())) }

        val snapshot = ring.snapshotNewestFirst()
        assertEquals(500, snapshot.size)
        assertEquals(500L, snapshot.first().sequence)
        assertEquals(1L, snapshot.last().sequence)
    }

    @Test
    fun ringIsBoundedAndCorrelationIdsAreUniqueWithConcurrentProducers() {
        val ring = DiagnosticsEntryRing(500)
        val ids = Collections.synchronizedSet(mutableSetOf<String>())
        val idGenerator = DiagnosticsCorrelationIdGenerator()
        val executor = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(800)
        repeat(800) { sequence ->
            executor.execute {
                ring.append(entry(sequence.toLong()))
                ids += idGenerator.next()
                done.countDown()
            }
        }
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(500, ring.snapshotNewestFirst().size)
        assertEquals(800, ids.size)
    }

    @Test
    fun fieldValuesAreRestrictedByTheirKeys() {
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.COUNT, "12")))
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.REQUEST_BYTES, "512")))
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.NETWORK_LIBRARY, "http_engine")))
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.ACCOUNT_ID, "123456")))
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.ACCOUNT_LOGIN, "example_user")))
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.PROGRESS, "4/?")))
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.STATE, "reconnected")))
        assertNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.COUNT, "twelve")))
        assertNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.STATE, "this is prose")))
        assertNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.CHANNEL_ID, "viewer-login")))
        assertNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.ACCOUNT_ID, "not-a-user-id")))
        assertNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.ACCOUNT_LOGIN, "Display Name")))
        assertFalse(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.STATE, "session_id=secret")) != null)
    }

    @Test
    fun accountContextCanBeRemovedFromAnEntryBeforeExport() {
        val entry = entry(1).copy(
            fields = listOf(
                DiagnosticsField(DiagnosticsFieldKey.ACCOUNT_ID, "123456"),
                DiagnosticsField(DiagnosticsFieldKey.ACCOUNT_LOGIN, "example_user"),
                DiagnosticsField(DiagnosticsFieldKey.COUNT, "2"),
            ),
        )

        val redacted = DiagnosticsSanitizer.withoutAccountContext(entry)

        assertEquals(listOf(DiagnosticsField(DiagnosticsFieldKey.COUNT, "2")), redacted.fields)
    }

    @Test
    fun formattedExportsRedactAccountContextByDefault() {
        val entry = entry(1).copy(
            fields = listOf(
                DiagnosticsField(DiagnosticsFieldKey.ACCOUNT_ID, "123456"),
                DiagnosticsField(DiagnosticsFieldKey.ACCOUNT_LOGIN, "example_user"),
            ),
        )

        assertFalse(DiagnosticsFormatter.formatEntry(entry).contains("example_user"))
        assertTrue(DiagnosticsFormatter.formatEntry(entry, includeAccountContext = true).contains("example_user"))
    }

    @Test
    fun requestLifecycleIncludesAttemptAndParentCorrelation() {
        val entry = entry(1).copy(
            event = DiagnosticsLifecycleEvent.REQUEST_RETRY.value,
            correlationId = "d000001",
            parentCorrelationId = "d000000",
            fields = listOf(
                DiagnosticsField(DiagnosticsFieldKey.ATTEMPT, "2"),
                DiagnosticsField(DiagnosticsFieldKey.RETRY, "true"),
            ),
        )

        val formatted = DiagnosticsFormatter.formatEntry(entry)

        assertTrue(formatted.contains("parentCorrelation=d000000"))
        assertTrue(formatted.contains("attempt=2"))
        assertTrue(formatted.contains("retry=true"))
    }

    @Test
    fun graphqlErrorsAreNotClassifiedAsSuccessfulHttpResponses() {
        assertFalse(diagnosticsRequestSucceeded(200, graphQlError = true, malformedResponse = false))
        assertTrue(diagnosticsRequestSucceeded(200, graphQlError = false, malformedResponse = false))
        assertFalse(diagnosticsRequestSucceeded(503, graphQlError = false, malformedResponse = false))
    }

    @Test
    fun requestTokenHasExactlyOneTerminalTransition() {
        val token = DiagnosticsLogger.RequestToken(
            correlationId = "d000001",
            startedAtElapsedMs = 1L,
            category = DiagnosticsCategory.GQL,
            transport = DiagnosticsTransport.GQL,
            operation = "test",
        )

        assertTrue(token.tryFinish())
        assertFalse(token.tryFinish())
    }

    @Test
    fun requestRetryAdvancesTheSharedAttempt() {
        val token = DiagnosticsLogger.RequestToken(
            correlationId = "d000001",
            startedAtElapsedMs = 1L,
            category = DiagnosticsCategory.PROGRESSION,
            transport = DiagnosticsTransport.SPADE,
            operation = "WatchCredit",
        )

        assertEquals(2L, token.nextAttempt())
        assertEquals(2L, token.currentAttempt())
    }

    private fun entry(sequence: Long) = DiagnosticsEntry(
        sequence = sequence,
        timestampMs = sequence,
        category = DiagnosticsCategory.GQL,
        severity = DiagnosticsSeverity.INFO,
        transport = DiagnosticsTransport.GQL,
        operation = "test",
        event = "event",
        phase = DiagnosticsPhase.EVENT,
    )
}
