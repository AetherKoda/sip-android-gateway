package io.github.sparkcyf.voicesipagent.telephony

import io.github.sparkcyf.voicesipagent.voice.MediaPort

/** An open cellular audio tap. */
interface CallAudioHandle {
    val port: MediaPort
    /** Called on main: release local media promptly and defer remote cleanup without waiting for IPC. */
    fun close()
}

/**
 * Supplies cellular call audio once a call is ACTIVE. Implemented by the privileged (Shizuku)
 * layer; kept as an interface so telephony has no dependency on how audio is obtained.
 */
fun interface CallAudioOpener {
    /** Output only, for one owned outbound call before ACTIVE. Called on the audio executor. */
    fun prepareOutgoingOutput(): CallOutputLease? = null
    /** Throws if audio is unavailable; [onLost] fires if the tap dies mid-call. */
    fun open(onLost: (String) -> Unit): CallAudioHandle
}

interface CallOutputLease {
    /** Refresh the short helper lease; false means suppression is no longer requested. */
    fun renew(): Boolean
    /** Synchronous restoration, serialized before opening the ACTIVE tap. */
    fun close()
}
