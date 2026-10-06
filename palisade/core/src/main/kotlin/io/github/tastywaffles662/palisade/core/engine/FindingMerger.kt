package io.github.tastywaffles662.palisade.core.engine

import io.github.tastywaffles662.palisade.core.model.Finding
import io.github.tastywaffles662.palisade.core.model.FindingDraft
import io.github.tastywaffles662.palisade.core.model.FindingState

enum class UpdateKind {
    /** First time this finding was seen. */
    NEW,

    /** Seen again after the user acknowledged or resolved it. */
    REOPENED,

    /** Seen again with a higher severity than before. */
    ESCALATED,

    /** Seen again, nothing new to tell the user. */
    REPEATED,

    /** Seen again, but the user allowlisted it. */
    SUPPRESSED,
}

data class FindingUpdate(val finding: Finding, val kind: UpdateKind) {
    /** True when the user hasn't heard about this yet: new, re-opened or worse than before. */
    val isNews: Boolean
        get() = kind == UpdateKind.NEW || kind == UpdateKind.REOPENED || kind == UpdateKind.ESCALATED
}

/** Folds a detector's [FindingDraft] into the stored finding with the same key. */
object FindingMerger {
    /** Evidence beyond this many events is dropped, oldest first. */
    const val MAX_EVIDENCE = 20

    fun merge(
        existing: Finding?,
        detector: String,
        draft: FindingDraft,
        eventId: Long,
        at: Long,
    ): FindingUpdate {
        if (existing == null) {
            val finding = Finding(
                detector = detector,
                key = draft.key,
                severity = draft.severity,
                confidence = draft.confidence,
                title = draft.title,
                summary = draft.summary,
                action = draft.action,
                attack = draft.attack.distinct(),
                evidence = listOf(eventId),
                firstSeen = at,
                lastSeen = at,
                count = 1,
                state = FindingState.OPEN,
            )
            return FindingUpdate(finding, UpdateKind.NEW)
        }

        val kind = when {
            existing.state == FindingState.ALLOWLISTED -> UpdateKind.SUPPRESSED
            existing.state != FindingState.OPEN -> UpdateKind.REOPENED
            draft.severity > existing.severity -> UpdateKind.ESCALATED
            else -> UpdateKind.REPEATED
        }
        val merged = existing.copy(
            // Severity and confidence only ever go up; text follows the latest draft.
            severity = maxOf(existing.severity, draft.severity),
            confidence = maxOf(existing.confidence, draft.confidence),
            title = draft.title,
            summary = draft.summary,
            action = draft.action,
            attack = (existing.attack + draft.attack).distinct(),
            evidence = (existing.evidence + eventId).takeLast(MAX_EVIDENCE),
            firstSeen = minOf(existing.firstSeen, at),
            lastSeen = maxOf(existing.lastSeen, at),
            count = existing.count + 1,
            state = if (existing.state == FindingState.ALLOWLISTED) FindingState.ALLOWLISTED else FindingState.OPEN,
        )
        return FindingUpdate(merged, kind)
    }
}
