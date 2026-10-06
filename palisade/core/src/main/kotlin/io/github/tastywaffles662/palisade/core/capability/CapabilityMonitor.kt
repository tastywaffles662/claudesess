package io.github.tastywaffles662.palisade.core.capability

import io.github.tastywaffles662.palisade.core.engine.EventSink
import io.github.tastywaffles662.palisade.core.model.Event
import io.github.tastywaffles662.palisade.core.model.EventTypes
import io.github.tastywaffles662.palisade.core.model.Sensor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Reads what access Palisade currently has. */
fun interface CapabilityProbe {
    fun current(): Map<Capability, Boolean>
}

/** Remembers the states seen by the previous check. */
interface CapabilityStateStore {
    /** Null until the first check has been saved. */
    fun load(): Map<Capability, Boolean>?

    fun save(states: Map<Capability, Boolean>)
}

data class CapabilityChange(val capability: Capability, val granted: Boolean)

/**
 * Notices when access is granted or taken away and records it as events. Losing access
 * silently is how monitoring gets blinded, so every change is kept.
 */
class CapabilityMonitor(
    private val probe: CapabilityProbe,
    private val store: CapabilityStateStore,
    private val sink: EventSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _states = MutableStateFlow<Map<Capability, Boolean>>(emptyMap())

    /** The result of the latest check; empty until the first one finishes. */
    val states: StateFlow<Map<Capability, Boolean>> = _states.asStateFlow()

    /** Probes access now, records any changes, and returns the current states. */
    suspend fun check(trigger: String): Map<Capability, Boolean> = mutex.withLock {
        val current = probe.current()
        val previous = store.load()
        val now = clock()
        if (previous == null) {
            sink.record(snapshotEvent(current, trigger, now))
        } else {
            for (change in diff(previous, current)) {
                sink.record(changeEvent(change, trigger, now))
            }
        }
        // Saved only after the events are recorded: a crash in between repeats an event
        // on the next check rather than losing it.
        store.save(current)
        _states.value = current
        current
    }

    companion object {
        /** Changes between two checks. Capabilities absent from either side are skipped. */
        fun diff(previous: Map<Capability, Boolean>, current: Map<Capability, Boolean>): List<CapabilityChange> =
            Capability.entries.mapNotNull { capability ->
                val before = previous[capability] ?: return@mapNotNull null
                val after = current[capability] ?: return@mapNotNull null
                if (before == after) null else CapabilityChange(capability, after)
            }

        private fun snapshotEvent(states: Map<Capability, Boolean>, trigger: String, now: Long) = Event(
            timestamp = now,
            sensor = Sensor.SELF,
            type = EventTypes.CAPABILITY_SNAPSHOT,
            attrs = buildJsonObject {
                put("trigger", trigger)
                putJsonArray("granted") { states.filterValues { it }.keys.forEach { add(it.id) } }
                putJsonArray("missing") { states.filterValues { !it }.keys.forEach { add(it.id) } }
            },
        )

        private fun changeEvent(change: CapabilityChange, trigger: String, now: Long) = Event(
            timestamp = now,
            sensor = Sensor.SELF,
            type = if (change.granted) EventTypes.CAPABILITY_GRANTED else EventTypes.CAPABILITY_REVOKED,
            attrs = buildJsonObject {
                put("capability", change.capability.id)
                put("tier", change.capability.tier.level)
                put("trigger", trigger)
            },
        )
    }
}
