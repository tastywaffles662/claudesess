package io.github.tastywaffles662.palisade.core.engine

import io.github.tastywaffles662.palisade.core.model.Event
import io.github.tastywaffles662.palisade.core.model.FindingDraft

/**
 * Turns events into findings. Implementations must be fast and free of side effects:
 * they run on every recorded event, and tests feed them recorded fixtures.
 */
interface Detector {
    /** Stable id, stored with every finding this detector produces. */
    val id: String

    fun inspect(event: Event): List<FindingDraft>
}

/** Where sensors send what they observe. */
interface EventSink {
    /** Stores [event], runs detectors over it and returns it with its assigned id. */
    suspend fun record(event: Event): Event
}
