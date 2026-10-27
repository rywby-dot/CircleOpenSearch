package org.circleopensearch

/** Assistant-process state. PCM stays in the capture service and is never persisted. */
object MusicState {
    enum class Phase { IDLE, CONSENT, LISTENING, MATCHING, FOUND, ERROR }
    data class State(val message: String, val running: Boolean = false, val song: SongMatch? = null,
        val phase: Phase = Phase.IDLE, val level: Float = 0f, val ready: Boolean = false)
    var value = State("Play music in another app, then start recognition.")
        private set
    private val listeners = mutableSetOf<(State) -> Unit>()
    fun publish(state: State) { value = state; listeners.toList().forEach { it(state) } }
    fun add(listener: (State) -> Unit) { listeners.add(listener); listener(value) }
    fun remove(listener: (State) -> Unit) { listeners.remove(listener) }
}
