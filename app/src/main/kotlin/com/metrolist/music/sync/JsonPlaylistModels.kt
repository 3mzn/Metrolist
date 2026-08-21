/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Data models for JSON playlist import.
 * Expected format (array of tracks):
 * ```
 * [
 *   { "title": "Song Name", "artist": "Artist Name" },
 *   ...
 * ]
 * ```
 * Supports case-insensitive keys: "title"/"Title", "artist"/"Artist".
 */
@Serializable(with = JsonTrackSerializer::class)
data class JsonTrack(
    val title: String,
    val artist: String,
) {
    /**
     * Generate a search query for YouTube Music.
     */
    fun toSearchQuery(): String = "$title $artist"

    /**
     * Display name for UI.
     */
    fun displayName(): String = "$title - $artist"
}

/**
 * Custom serializer for case-insensitive JSON parsing.
 */
object JsonTrackSerializer : KSerializer<JsonTrack> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JsonTrack") {
        element<String>("title")
        element<String>("artist")
    }

    override fun deserialize(decoder: Decoder): JsonTrack {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("This serializer can only be used with JSON")

        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject

        // Find title (case-insensitive)
        val title =
            jsonObject.entries.find {
                it.key.equals("title", ignoreCase = true)
            }?.value?.jsonPrimitive?.content
                ?: throw SerializationException("Missing 'title' field in JSON")

        // Find artist (case-insensitive)
        val artist =
            jsonObject.entries.find {
                it.key.equals("artist", ignoreCase = true)
            }?.value?.jsonPrimitive?.content
                ?: throw SerializationException("Missing 'artist' field in JSON")

        return JsonTrack(title, artist)
    }

    override fun serialize(encoder: Encoder, value: JsonTrack) {
        throw NotImplementedError("Serialization not supported")
    }
}

/**
 * Result of importing a single track.
 */
sealed class ImportResult {
    data class Success(val track: JsonTrack, val youtubeId: String) : ImportResult()
    data class Failed(val track: JsonTrack, val reason: String) : ImportResult()
}
