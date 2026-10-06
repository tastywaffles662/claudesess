package io.github.tastywaffles662.palisade.core.capability

import io.github.tastywaffles662.palisade.core.engine.Detector
import io.github.tastywaffles662.palisade.core.model.Event
import io.github.tastywaffles662.palisade.core.model.EventTypes
import io.github.tastywaffles662.palisade.core.model.FindingDraft
import io.github.tastywaffles662.palisade.core.model.Sensor
import io.github.tastywaffles662.palisade.core.model.Severity

/** Raises a finding when Palisade loses access it previously had. */
class CapabilityRevokedDetector : Detector {
    override val id: String = ID

    override fun inspect(event: Event): List<FindingDraft> {
        if (event.sensor != Sensor.SELF || event.type != EventTypes.CAPABILITY_REVOKED) return emptyList()
        val capability = event.attr("capability")?.let(Capability::fromId) ?: return emptyList()
        val adb = capability.tier == Tier.ADB
        return listOf(
            FindingDraft(
                key = capability.id,
                // Taking back an adb grant needs a computer with debugging access, or root.
                severity = if (adb) Severity.MEDIUM else Severity.LOW,
                confidence = 1f,
                title = "${capability.label} was revoked",
                summary = if (adb) {
                    "Removing an adb grant needs a computer with USB debugging access to this " +
                        "phone, or root. If you didn't do it, someone else may have had that access."
                } else {
                    "Palisade lost access it had before. Anyone who can change settings on this " +
                        "phone can do this, and so can an app with accessibility access."
                },
                action = "If you changed this yourself, grant it again or acknowledge this finding. " +
                    "If you didn't, check which apps have accessibility or device admin access.",
                attack = listOf(ATTACK_DISABLE_OR_MODIFY_TOOLS),
            ),
        )
    }

    companion object {
        const val ID = "capability.revoked"

        /** MITRE ATT&CK for Mobile: Impair Defenses: Disable or Modify Tools. */
        const val ATTACK_DISABLE_OR_MODIFY_TOOLS = "T1629.003"
    }
}
