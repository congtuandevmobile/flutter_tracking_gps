package com.uprace.flutter_tracking_gps

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The service-ready acknowledgement: recording may begin only after it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundHandshakeTest {
    @Test fun acknowledgementBeforeTheTimeoutIsReady() = runTest {
        val h = ForegroundHandshake()
        val wait = h.expect()
        h.acknowledge()
        assertTrue(h.await(wait, 1_000))
    }

    @Test fun noAcknowledgementIsNotReady() = runTest {
        val h = ForegroundHandshake()
        assertFalse(h.await(h.expect(), 1_000))
    }

    @Test fun aRefusedStartSurfacesAsTheFailure() = runTest {
        val h = ForegroundHandshake()
        val wait = h.expect()
        h.fail(IllegalStateException("background start not allowed"))
        val e = assertFailsWith<IllegalStateException> { h.await(wait, 1_000) }
        assertEquals("background start not allowed", e.message)
    }

    @Test fun anOldAcknowledgementDoesNotSatisfyANewWait() = runTest {
        val h = ForegroundHandshake()
        h.expect()
        h.acknowledge()
        assertFalse(h.await(h.expect(), 1_000))
    }

    @Test fun anAcknowledgementSatisfiesEveryOverlappingRequest() = runTest {
        val h = ForegroundHandshake()
        val a = h.expect()
        val b = h.expect()
        h.acknowledge()
        assertTrue(h.await(a, 1_000), "the older request is not abandoned")
        assertTrue(h.await(b, 1_000))
    }

    @Test fun aFailureFailsEveryOverlappingRequest() = runTest {
        val h = ForegroundHandshake()
        val a = h.expect()
        val b = h.expect()
        h.fail(IllegalStateException("refused"))
        assertFailsWith<IllegalStateException> { h.await(a, 1_000) }
        assertFailsWith<IllegalStateException> { h.await(b, 1_000) }
    }

    @Test fun onlyTheNewestRequestMayStopTheService() = runTest {
        val h = ForegroundHandshake()
        val a = h.expect()
        val b = h.expect()
        assertFalse(h.isCurrent(a), "an older request timing out must not stop the service")
        assertTrue(h.isCurrent(b))
        assertFalse(h.await(a, 10))
        assertTrue(h.isCurrent(b), "the older request's cleanup did not disturb the newer one")
    }
}
