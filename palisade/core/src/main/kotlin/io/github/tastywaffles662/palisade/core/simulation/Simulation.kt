package io.github.tastywaffles662.palisade.core.simulation

import io.github.tastywaffles662.palisade.core.engine.Detector
import io.github.tastywaffles662.palisade.core.model.Event
import io.github.tastywaffles662.palisade.core.model.EventTypes
import io.github.tastywaffles662.palisade.core.model.FindingDraft
import io.github.tastywaffles662.palisade.core.model.Sensor
import io.github.tastywaffles662.palisade.core.model.Severity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Synthetic events for developers. They travel the real path (store, detectors,
 * findings, notifications), so injecting one checks that the plumbing works.
 */
object Simulation {
    fun findingEvent(severity: Severity, at: Long): Event = Event(
        timestamp = at,
        sensor = Sensor.SIMULATION,
        type = EventTypes.SIMULATED_FINDING,
        attrs = buildJsonObject { put("severity", severity.name) },
    )
}

/** Turns simulated events into test findings. Only registered in debug builds. */
class SimulationDetector : Detector {
    override val id: String = "simulation"

    override fun inspect(event: Event): List<FindingDraft> {
        if (event.sensor != Sensor.SIMULATION || event.type != EventTypes.SIMULATED_FINDING) return emptyList()
        val severity = Severity.entries.firstOrNull { it.name == event.attr("severity") } ?: Severity.INFO
        return listOf(
            FindingDraft(
                key = "test-${severity.name.lowercase()}",
                severity = severity,
                confidence = 1f,
                title = "Test finding (${severity.label.lowercase()})",
                summary = "Injected from the debug tools to check detection, storage and notifications.",
                action = "Acknowledge it to clear it.",
            ),
        )
    }
}
