package io.github.tastywaffles662.palisade.core.model

/** Declaration order is significance order. [level] is what gets stored. */
enum class Severity(val level: Int, val label: String) {
    INFO(0, "Info"),
    LOW(1, "Low"),
    MEDIUM(2, "Medium"),
    HIGH(3, "High"),
    CRITICAL(4, "Critical");

    companion object {
        fun fromLevel(level: Int): Severity = entries.firstOrNull { it.level == level } ?: INFO
    }
}

enum class FindingState {
    OPEN,
    ACKNOWLEDGED,

    /** The user said to ignore this finding; repeats are counted but stay quiet. */
    ALLOWLISTED,
    RESOLVED,
}

/** What a detector reports about one event, before it is merged into a stored [Finding]. */
data class FindingDraft(
    /** Identifies the finding within its detector; repeats with the same key merge. */
    val key: String,
    val severity: Severity,
    val confidence: Float,
    val title: String,
    val summary: String,
    /** What the user should do about it. */
    val action: String,
    /** MITRE ATT&CK for Mobile technique ids, e.g. "T1629.003". */
    val attack: List<String> = emptyList(),
)

/** A conclusion drawn from one or more events, deduplicated by ([detector], [key]). */
data class Finding(
    val id: Long = 0,
    val detector: String,
    val key: String,
    val severity: Severity,
    val confidence: Float,
    val title: String,
    val summary: String,
    val action: String,
    val attack: List<String>,
    /** Ids of the most recent supporting events, oldest first. */
    val evidence: List<Long>,
    val firstSeen: Long,
    val lastSeen: Long,
    val count: Int,
    val state: FindingState,
)
