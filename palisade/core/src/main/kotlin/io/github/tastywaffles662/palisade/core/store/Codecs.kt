package io.github.tastywaffles662.palisade.core.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Text encodings for the structured columns of the event store. Decoding never throws:
 * a corrupt row should show up as missing details, not crash the timeline.
 */
object Codecs {
    private val EMPTY = JsonObject(emptyMap())

    fun encodeAttrs(attrs: JsonObject): String = attrs.toString()

    fun decodeAttrs(text: String): JsonObject =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: EMPTY

    fun encodeIds(ids: List<Long>): String = JsonArray(ids.map { JsonPrimitive(it) }).toString()

    fun decodeIds(text: String): List<Long> = decodeArray(text).mapNotNull { it.longOrNull }

    fun encodeStrings(values: List<String>): String = JsonArray(values.map { JsonPrimitive(it) }).toString()

    fun decodeStrings(text: String): List<String> = decodeArray(text).mapNotNull { it.contentOrNull }

    private fun decodeArray(text: String): List<JsonPrimitive> =
        runCatching { (Json.parseToJsonElement(text) as? JsonArray)?.filterIsInstance<JsonPrimitive>() }
            .getOrNull()
            .orEmpty()
}
