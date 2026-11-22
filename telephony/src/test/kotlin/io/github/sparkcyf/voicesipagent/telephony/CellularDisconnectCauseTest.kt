package io.github.sparkcyf.voicesipagent.telephony

import android.os.Handler
import android.telecom.Call
import android.telecom.DisconnectCause
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.ArrayDeque

/** Actual leg callbacks; Telecom supplies its final cause after DISCONNECTING. */
class CellularDisconnectCauseTest {
    private val tasks = ArrayDeque<Runnable>()
    private val main = mock(Handler::class.java)
    private val endpoint = mock(CellularEndpoint::class.java)
    private val call = mock(Call::class.java)
    private val details = mock(Call.Details::class.java)
    private lateinit var callback: Call.Callback
    private lateinit var leg: CellularLeg

    @Suppress("DEPRECATION")
    @Before fun setup() {
        doAnswer { tasks.add(it.getArgument(0)); true }.`when`(main).post(any(Runnable::class.java))
        doReturn(Call.STATE_DIALING).`when`(call).state
        doReturn(details).`when`(call).details
        doAnswer { callback = it.getArgument(0); null }.`when`(call)
            .registerCallback(any(Call.Callback::class.java), eq(main))
        leg = CellularLeg("test-disconnect", LegDirection.OUTBOUND, Party(null), "123", null, endpoint, main)
        leg.bind(call)
        assertEquals(LegState.PROCEEDING, leg.state)
    }

    private fun drainMain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    private fun finalCause(code: Int) {
        val cause = mock(DisconnectCause::class.java)
        doReturn(code).`when`(cause).code
        doReturn(cause).`when`(details).disconnectCause
    }

    private fun remoteDisconnect(code: Int, expected: HangupCause) {
        callback.onStateChanged(call, Call.STATE_DISCONNECTING)
        assertTrue(leg.stopping)
        assertEquals(LegState.PROCEEDING, leg.state)
        finalCause(code)
        callback.onStateChanged(call, Call.STATE_DISCONNECTED)
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(expected, leg.endCause)
        verify(endpoint).onLegEnded(leg)
        verify(call, never()).disconnect()
    }

    @Test fun remoteBusyAfterDisconnectingRetainsBusyCause() {
        remoteDisconnect(DisconnectCause.BUSY, HangupCause.BUSY)
    }

    @Test fun remoteRejectionAfterDisconnectingRetainsRejectedCause() {
        remoteDisconnect(DisconnectCause.REJECTED, HangupCause.REJECTED)
    }

    private fun localDisconnect(expected: HangupCause) {
        leg.hangup(expected)
        drainMain()
        callback.onStateChanged(call, Call.STATE_DISCONNECTING)
        finalCause(DisconnectCause.LOCAL)
        callback.onStateChanged(call, Call.STATE_DISCONNECTED)
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(expected, leg.endCause)
        verify(call).disconnect()
        verify(endpoint).onLegEnded(leg)
    }

    @Test fun explicitLocalCancellationSurvivesDisconnectingCallback() {
        localDisconnect(HangupCause.CANCELLED)
    }

    @Test fun explicitLocalNormalHangupSurvivesDisconnectingCallback() {
        localDisconnect(HangupCause.NORMAL)
    }

    @Test fun lateActiveAndRepeatedDisconnectCallbacksCannotReviveOrReplaceCause() {
        callback.onStateChanged(call, Call.STATE_DISCONNECTING)
        callback.onStateChanged(call, Call.STATE_ACTIVE)
        callback.onStateChanged(call, Call.STATE_DISCONNECTING)
        assertEquals(LegState.PROCEEDING, leg.state)
        assertTrue(leg.stopping)
        verify(endpoint, never()).canTap(leg)
        finalCause(DisconnectCause.REJECTED)
        callback.onStateChanged(call, Call.STATE_DISCONNECTED)
        finalCause(DisconnectCause.BUSY)
        callback.onStateChanged(call, Call.STATE_DISCONNECTED)
        callback.onStateChanged(call, Call.STATE_ACTIVE)
        leg.onRemoved()
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(HangupCause.REJECTED, leg.endCause)
        assertNull(leg.media)
        verify(endpoint, times(1)).onLegEnded(leg)
        verify(endpoint, never()).canTap(leg)
    }
}
