package org.circleopensearch

/** One capture per session, including delivery before the UI is shown. */
class SingleCapture<T> {
    enum class Phase { WAITING, READY, UNAVAILABLE, TIMED_OUT, CLOSED }
    var phase = Phase.WAITING
        private set
    var value: T? = null
        private set
    fun receive(result: T?): Boolean {
        if (phase != Phase.WAITING && phase != Phase.TIMED_OUT) return false
        value = result
        phase = if (result == null) Phase.UNAVAILABLE else Phase.READY
        return true
    }
    fun timeout() { if (phase == Phase.WAITING) phase = Phase.TIMED_OUT }
    fun close() { value = null; phase = Phase.CLOSED }
}
