package io.github.tastywaffles662.palisade.core

import io.github.tastywaffles662.palisade.core.capability.Capability
import io.github.tastywaffles662.palisade.core.capability.CapabilityStateStore
import io.github.tastywaffles662.palisade.core.engine.EventSink
import io.github.tastywaffles662.palisade.core.engine.EventStore
import io.github.tastywaffles662.palisade.core.engine.FindingStore
import io.github.tastywaffles662.palisade.core.model.Event
import io.github.tastywaffles662.palisade.core.model.Finding
import io.github.tastywaffles662.palisade.core.posture.BootState
import io.github.tastywaffles662.palisade.core.posture.BootStateStore

class InMemoryEventStore : EventStore {
    val events = mutableListOf<Event>()

    override suspend fun append(event: Event): Long {
        val id = events.size + 1L
        events += event.copy(id = id)
        return id
    }
}

class InMemoryFindingStore : FindingStore {
    val findings = mutableMapOf<Long, Finding>()

    override suspend fun find(detector: String, key: String): Finding? =
        findings.values.firstOrNull { it.detector == detector && it.key == key }

    override suspend fun save(finding: Finding): Long {
        val id = if (finding.id == 0L) findings.size + 1L else finding.id
        findings[id] = finding.copy(id = id)
        return id
    }
}

/** Collects recorded events without running detectors. */
class RecordingSink : EventSink {
    val events = mutableListOf<Event>()

    override suspend fun record(event: Event): Event {
        val stored = event.copy(id = events.size + 1L)
        events += stored
        return stored
    }
}

class InMemoryCapabilityStateStore(var saved: Map<Capability, Boolean>? = null) : CapabilityStateStore {
    override fun load(): Map<Capability, Boolean>? = saved

    override fun save(states: Map<Capability, Boolean>) {
        saved = states
    }
}

class InMemoryBootStateStore(var saved: BootState? = null) : BootStateStore {
    override fun load(): BootState? = saved

    override fun save(state: BootState) {
        saved = state
    }
}

fun allCapabilities(granted: Boolean): Map<Capability, Boolean> = Capability.entries.associateWith { granted }
