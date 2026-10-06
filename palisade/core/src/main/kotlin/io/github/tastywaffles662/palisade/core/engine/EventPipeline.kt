package io.github.tastywaffles662.palisade.core.engine

import io.github.tastywaffles662.palisade.core.model.Event
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface FindingListener {
    fun onFindingUpdated(update: FindingUpdate)
}

/**
 * The single path from sensors to findings: persist the event, run every detector
 * over it, merge what they report into stored findings and tell the [listener].
 *
 * Events are processed one at a time, so a finding's read-merge-write never races.
 */
class EventPipeline(
    private val events: EventStore,
    private val findings: FindingStore,
    private val detectors: List<Detector>,
    private val listener: FindingListener = FindingListener {},
    private val onDetectorError: (detectorId: String, error: Exception) -> Unit = { _, _ -> },
) : EventSink {
    private val mutex = Mutex()

    override suspend fun record(event: Event): Event = mutex.withLock {
        val stored = event.copy(id = events.append(event))
        for (detector in detectors) {
            val drafts = try {
                detector.inspect(stored)
            } catch (e: Exception) {
                // One broken detector must not stop the others or lose the event.
                onDetectorError(detector.id, e)
                continue
            }
            for (draft in drafts) {
                val existing = findings.find(detector.id, draft.key)
                val update = FindingMerger.merge(existing, detector.id, draft, stored.id, stored.timestamp)
                val id = findings.save(update.finding)
                listener.onFindingUpdated(update.copy(finding = update.finding.copy(id = id)))
            }
        }
        stored
    }
}
