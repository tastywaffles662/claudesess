package io.github.tastywaffles662.palisade.core.capability

data class TierProgress(val tier: Tier, val granted: Int, val total: Int) {
    val complete: Boolean get() = granted == total
}

data class TierSummary(
    /** One entry per tier, lowest first. */
    val progress: List<TierProgress>,
    /** The highest tier that is complete along with every tier below it, or null. */
    val reached: Tier?,
)

object Tiers {
    /** Capabilities missing from [states] count as not granted. */
    fun summarize(states: Map<Capability, Boolean>): TierSummary {
        val progress = Tier.entries.map { tier ->
            val capabilities = Capability.entries.filter { it.tier == tier }
            TierProgress(tier, capabilities.count { states[it] == true }, capabilities.size)
        }
        val reached = progress.takeWhile { it.complete }.lastOrNull()?.tier
        return TierSummary(progress, reached)
    }
}

object AdbGrants {
    /** The commands that grant every [Tier.ADB] capability to [packageName]. */
    fun commands(packageName: String): List<String> =
        Capability.entries
            .filter { it.tier == Tier.ADB }
            .map { "adb shell pm grant $packageName ${requireNotNull(it.adbPermission)}" }
}
