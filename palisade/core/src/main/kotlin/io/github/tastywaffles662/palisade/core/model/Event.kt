package io.github.tastywaffles662.palisade.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Where an event came from. [id] is what gets stored, so it must never change. */
enum class Sensor(val id: String) {
    DNS("dns"),
    FLOW("flow"),
    CRASH("crash"),
    FILES("files"),
    POWER("power"),
    POSTURE("posture"),

    /** Palisade watching itself: its access, updates and health. */
    SELF("self"),

    /** Synthetic events injected by developers to exercise the pipeline. */
    SIMULATION("simulation");

    companion object {
        fun fromId(id: String): Sensor? = entries.firstOrNull { it.id == id }
    }
}

/** Event type names. Dotted, lowercase, and stored, so never rename one. */
object EventTypes {
    const val CAPABILITY_SNAPSHOT = "capability.snapshot"
    const val CAPABILITY_GRANTED = "capability.granted"
    const val CAPABILITY_REVOKED = "capability.revoked"
    const val DEVICE_BOOT = "device.boot"
    const val SELF_UPDATED = "self.updated"
    const val SIMULATED_FINDING = "simulation.finding"
}

/**
 * Something a sensor observed. Immutable; [id] is 0 until the event store assigns one.
 *
 * [attrs] holds the sensor-specific details as JSON so that new sensors don't need
 * schema changes.
 */
data class Event(
    val id: Long = 0,
    val timestamp: Long,
    val sensor: Sensor,
    val type: String,
    val uid: Int? = null,
    val packageName: String? = null,
    val attrs: JsonObject = JsonObject(emptyMap()),
) {
    /** The attribute [name] as a string, or null if it is missing or not a primitive. */
    fun attr(name: String): String? = (attrs[name] as? JsonPrimitive)?.contentOrNull
}
