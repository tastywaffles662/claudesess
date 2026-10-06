package io.github.tastywaffles662.palisade.core.posture

import io.github.tastywaffles662.palisade.core.engine.EventSink
import io.github.tastywaffles662.palisade.core.model.Event
import io.github.tastywaffles662.palisade.core.model.EventTypes
import io.github.tastywaffles662.palisade.core.model.Sensor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

interface BootInfoSource {
    /** The system's boot counter, or null where the platform doesn't expose one. */
    fun bootCount(): Int?

    /** Wall-clock time of the current boot, in epoch milliseconds. */
    fun bootTimeMillis(): Long
}

data class BootState(val bootCount: Int?, val bootTimeMillis: Long)

interface BootStateStore {
    /** The boot recorded last, or null if none has been. */
    fun load(): BootState?

    fun save(state: BootState)
}

/**
 * Records each device boot once, however Palisade first learns of it: the boot
 * broadcast, the app being opened, or a periodic check. Reboots are a crash signal.
 */
class BootTracker(
    private val source: BootInfoSource,
    private val store: BootStateStore,
    private val sink: EventSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Records the current boot if it hasn't been recorded yet. Returns true if it was new. */
    suspend fun check(detectedVia: String): Boolean = mutex.withLock {
        val count = source.bootCount()
        val bootTime = source.bootTimeMillis()
        val last = store.load()
        val isNew = when {
            last == null -> true
            count != null && last.bootCount != null -> count != last.bootCount
            // Without a counter, fall back to the boot time, which drifts with clock changes.
            else -> abs(bootTime - last.bootTimeMillis) > BOOT_TIME_TOLERANCE_MS
        }
        if (!isNew) return@withLock false

        val lastCount = last?.bootCount
        val attrs = buildJsonObject {
            put("detectedVia", detectedVia)
            put("detectedAt", clock())
            if (count != null) put("bootCount", count)
            if (last == null) put("firstObservation", true)
            if (count != null && lastCount != null && count > lastCount + 1) {
                put("missedBoots", count - lastCount - 1)
            }
        }
        sink.record(Event(timestamp = bootTime, sensor = Sensor.POSTURE, type = EventTypes.DEVICE_BOOT, attrs = attrs))
        store.save(BootState(count, bootTime))
        true
    }

    companion object {
        const val BOOT_TIME_TOLERANCE_MS = 60_000L
    }
}
