package io.github.tastywaffles662.palisade.core.engine

import io.github.tastywaffles662.palisade.core.model.Event
import io.github.tastywaffles662.palisade.core.model.Finding

interface EventStore {
    /** Persists [event] and returns its new id. */
    suspend fun append(event: Event): Long
}

interface FindingStore {
    suspend fun find(detector: String, key: String): Finding?

    /** Inserts [finding] when its id is 0, otherwise updates it. Returns its id. */
    suspend fun save(finding: Finding): Long
}
