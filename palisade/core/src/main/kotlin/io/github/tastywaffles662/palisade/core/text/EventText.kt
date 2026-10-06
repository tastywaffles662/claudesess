package io.github.tastywaffles662.palisade.core.text

import io.github.tastywaffles662.palisade.core.capability.Capability
import io.github.tastywaffles662.palisade.core.model.Event
import io.github.tastywaffles662.palisade.core.model.EventTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** One-line, human-readable descriptions of events for the timeline. */
object EventText {
    fun describe(event: Event): String = when (event.type) {
        EventTypes.CAPABILITY_SNAPSHOT -> {
            val granted = event.arraySize("granted")
            val total = granted + event.arraySize("missing")
            "Access recorded: $granted of $total granted"
        }
        EventTypes.CAPABILITY_GRANTED -> "${capabilityLabel(event)} granted"
        EventTypes.CAPABILITY_REVOKED -> "${capabilityLabel(event)} revoked"
        EventTypes.DEVICE_BOOT -> buildString {
            append("Device booted")
            event.attr("bootCount")?.let { append(" (boot #").append(it).append(')') }
            event.attr("missedBoots")?.let { append("; ").append(it).append(" earlier boot(s) not seen") }
        }
        EventTypes.SELF_UPDATED -> "Palisade updated to ${event.attr("versionName") ?: "a new version"}"
        EventTypes.SIMULATED_FINDING -> "Test event (${event.attr("severity")?.lowercase() ?: "unknown"})"
        else -> event.type
    }

    /** The attributes as `key=value` pairs, for showing raw details. */
    fun details(event: Event): String = event.attrs.entries.joinToString("  ") { (key, value) ->
        "$key=${(value as? JsonPrimitive)?.content ?: value.toString()}"
    }

    private fun capabilityLabel(event: Event): String {
        val id = event.attr("capability") ?: return "Unknown access"
        return Capability.fromId(id)?.label ?: id
    }

    private fun Event.arraySize(name: String): Int = (attrs[name] as? JsonArray)?.size ?: 0
}
