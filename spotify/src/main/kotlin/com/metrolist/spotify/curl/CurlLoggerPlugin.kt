/*
 * Spotify Canvas support in Metrolist.
 *
 * Copyright (C) 2025 maxrave-dev (SimpMusic)
 * Copyright (C) 2025 The Metrolist Group
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * ---------------------------------------------------------------------------
 * Ported verbatim from SimpMusic, core/service/ktorExt, which is also GPL-3.0.
 * The only change is the package declaration. Do not "improve" this file; it
 * works, and its comment style is load-bearing documentation of intent.
 * ---------------------------------------------------------------------------
 */
package com.metrolist.spotify.curl

import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlin.coroutines.cancellation.CancellationException

/**
 * Config for [CurlLogger].
 */
class CurlLoggerConfig {
    /**
     * Sink for the built curl command. Defaults to [println]; each service overrides this
     * with its own logger, e.g. `logger = { Logger.d(TAG, it) }`.
     */
    var logger: (String) -> Unit = { println(it) }

    /**
     * Header names (case-insensitive) whose value is replaced with `<redacted>`.
     * Empty by default so the printed command stays copy-paste runnable.
     * Set e.g. `setOf("Authorization", "Cookie")` to avoid leaking secrets to logs.
     */
    var redactHeaders: Set<String> = emptySet()

    /**
     * When true, drops the `Accept-Encoding` request header and appends `--compressed`
     * so the response is auto-decoded by curl instead of printing as compressed bytes.
     */
    var handleCompression: Boolean = true
}

/**
 * Logs every outgoing Ktor request as a shell-safe `curl` command that can be pasted into a
 * terminal and run as-is. Every dynamic value is wrapped in single quotes, with embedded single
 * quotes escaped via the POSIX `'\''` trick, so bodies containing `"`, `$`, backticks or newlines
 * never break the shell.
 *
 * The whole command is emitted as a single line in one [logger] call, so it stays one log entry
 * (no line continuations, no splitting into separate entries).
 *
 * Usage:
 * ```
 * install(CurlLogger) {
 *     logger = { Logger.d(TAG, it) }
 * }
 * ```
 */
val CurlLogger = createClientPlugin("CurlLogger", ::CurlLoggerConfig) {
    val log = pluginConfig.logger
    val redactHeaders = pluginConfig.redactHeaders.mapTo(mutableSetOf()) { it.lowercase() }
    val handleCompression = pluginConfig.handleCompression

    on(SendingRequest) { request, content ->
        try {
            log(buildCurlCommand(request, content, redactHeaders, handleCompression))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Logging must never break the actual request.
        }
    }
}

/** Builds the full curl command on a single line. */
private suspend fun buildCurlCommand(
    request: HttpRequestBuilder,
    content: OutgoingContent,
    redactHeaders: Set<String>,
    handleCompression: Boolean,
): String {
    val contentLengthName = HttpHeaders.ContentLength.lowercase()
    val acceptEncodingName = HttpHeaders.AcceptEncoding.lowercase()

    val parts = mutableListOf<String>()
    parts += "curl -X ${request.method.value}"
    parts += request.url.buildString().shellQuote()

    val seenHeaders = mutableSetOf<String>()
    request.headers.entries().forEach { (name, values) ->
        val lower = name.lowercase()
        if (lower == contentLengthName) return@forEach // let curl recompute it
        if (handleCompression && lower == acceptEncodingName) return@forEach
        seenHeaders += lower
        values.forEach { value ->
            val shown = if (lower in redactHeaders) "<redacted>" else value
            parts += "-H " + "$name: $shown".shellQuote()
        }
    }
    if ("content-type" !in seenHeaders) {
        content.contentType?.let { parts += "-H " + "${HttpHeaders.ContentType}: $it".shellQuote() }
    }

    if (handleCompression) parts += "--compressed"

    content.readBodyOrNull()?.takeIf { it.isNotEmpty() }?.let {
        parts += "--data-raw " + it.shellQuote()
    }

    return parts.joinToString(" ")
}

/** POSIX shell single-quoting: safe even when the value itself contains single quotes. */
private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

/** Best-effort read of the request body without consuming a one-shot channel. */
private suspend fun OutgoingContent.readBodyOrNull(): String? =
    when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> readWriteChannelBody()
        else -> null // ReadChannelContent / ProtocolUpgrade / NoContent: skip
    }

private suspend fun OutgoingContent.WriteChannelContent.readWriteChannelBody(): String? =
    try {
        coroutineScope {
            val channel = ByteChannel()
            launch {
                try {
                    writeTo(channel)
                } finally {
                    channel.flushAndClose()
                }
            }
            channel.readRemaining().readByteArray().decodeToString()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
