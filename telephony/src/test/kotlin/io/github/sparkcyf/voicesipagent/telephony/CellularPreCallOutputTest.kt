package io.github.sparkcyf.voicesipagent.telephony

import android.os.Handler
import android.telecom.Call
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.Party
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.ArrayDeque
import java.util.concurrent.Executor

class CellularPreCallOutputTest {
    private val mainTasks = ArrayDeque<Runnable>()
    private val audioTasks = ArrayDeque<Runnable>()
    private val delayed = ArrayDeque<Runnable>()
    private val main = mock(Handler::class.java)
    private val endpoint = mock(CellularEndpoint::class.java)
    private val opener = mock(CallAudioOpener::class.java)
    private val lease = mock(CallOutputLease::class.java)
    private val call = mock(Call::class.java)
    private val handle = mock(CallAudioHandle::class.java)
    private lateinit var callback: Call.Callback
    private lateinit var leg: CellularLeg

    @Suppress("DEPRECATION")
    @Before fun setup() {
        doAnswer { mainTasks.add(it.getArgument(0)); true }.`when`(main).post(any(Runnable::class.java))
        doAnswer { delayed.add(it.getArgument(0)); true }.`when`(main)
            .postDelayed(any(Runnable::class.java), any(), anyLong())
        doReturn(Call.STATE_DIALING).`when`(call).state
        doAnswer { callback = it.getArgument(0); null }.`when`(call)
            .registerCallback(any(Call.Callback::class.java), eq(main))
        doReturn(Executor { audioTasks.add(it) }).`when`(endpoint).audioExecutor
        doReturn(opener).`when`(endpoint).audioOpener
        doReturn(lease).`when`(opener).prepareOutgoingOutput()
        doReturn(true).`when`(lease).renew()
        leg = CellularLeg("pre-call", LegDirection.OUTBOUND, Party(null), "123", null, endpoint, main)
        doReturn(true).`when`(endpoint).canMuteOutgoingOutput(leg)
        doReturn(true).`when`(endpoint).canTap(leg)
        doReturn(true).`when`(endpoint).acquireMute(leg)
        doReturn(handle).`when`(opener).open(any<(String) -> Unit>() ?: {})
    }

    private fun drainMain() { while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run() }
    private fun drainAudio() { while (audioTasks.isNotEmpty()) { audioTasks.removeFirst().run(); drainMain() } }

    @Test fun dialingMutesOutputWithoutOpeningTapOrMutingMicrophone() {
        leg.bind(call); drainAudio()
        verify(lease).renew()
        verify(opener, never()).open(any<(String) -> Unit>() ?: {})
        verify(endpoint, never()).acquireMute(leg)
        assertEquals(1, delayed.size)
    }

    @Test fun activeRestoresOutputBeforeTapOpenAndStaleRenewDoesNothing() {
        leg.bind(call); drainAudio()
        callback.onStateChanged(call, Call.STATE_ACTIVE)
        drainAudio()
        val order = inOrder(lease, opener)
        order.verify(lease).renew()
        order.verify(lease).close()
        order.verify(opener).open(any<(String) -> Unit>() ?: {})
        delayed.removeFirst().run(); drainAudio()
        verify(lease, times(1)).renew()
    }

    @Test fun cancellationBeforeWorkerRunsDoesNotAcquireLease() {
        leg.bind(call)
        leg.hangup(HangupCause.CANCELLED); drainMain(); drainAudio()
        verify(opener, never()).prepareOutgoingOutput()
        verify(lease, never()).renew()
    }

    @Test fun disconnectingRestoresOutputAndCannotRenewAgain() {
        leg.bind(call); drainAudio()
        callback.onStateChanged(call, Call.STATE_DISCONNECTING); drainAudio()
        delayed.removeFirst().run(); drainAudio()
        verify(lease).close()
        verify(lease, times(1)).renew()
        verify(opener, never()).open(any<(String) -> Unit>() ?: {})
    }

    @Test fun readinessLossRestoresLease() {
        leg.bind(call); drainAudio()
        leg.onServiceLost("test"); drainAudio()
        verify(lease).close()
        verify(call).disconnect()
    }

    @Test fun unownedOrIncomingCallDoesNotAcquireOutputLease() {
        doReturn(false).`when`(endpoint).canMuteOutgoingOutput(leg)
        leg.bind(call); drainAudio()
        verify(opener, never()).prepareOutgoingOutput()
    }
}
