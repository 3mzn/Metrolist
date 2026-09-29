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
 * Ported from SimpMusic, `core/service/ktorExt/encoding`: the `expect fun
 * createBrotliEncoder` declaration and the `androidMain` actual were merged
 * into this single file, because Metrolist is Android-only and has no KMP
 * source sets. Logic is otherwise unchanged.
 * ---------------------------------------------------------------------------
 */

package com.metrolist.spotify.encoding

import io.ktor.client.plugins.compression.ContentEncodingConfig
import io.ktor.util.ContentEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import org.brotli.dec.BrotliInputStream
import kotlin.coroutines.CoroutineContext

/**
 * Brotli support for the Spotify client.
 *
 * `org.brotli:dec` only decodes, so [encode] is unsupported. Ktor only advertises
 * `br` in `Accept-Encoding` when a request asks for it and a matching encoder is
 * registered; the Spotify client relies on `gzip`/`deflate`, so this gap is not
 * reachable in practice. If Spotify ever starts requiring brotli uploads, a real
 * encoder is needed here.
 */
object BrotliEncoder : ContentEncoder {
    override val name: String = "br"

    override fun decode(
        source: ByteReadChannel,
        coroutineContext: CoroutineContext,
    ): ByteReadChannel = BrotliInputStream(source.toInputStream()).toByteReadChannel(coroutineContext)

    override fun encode(
        source: ByteReadChannel,
        coroutineContext: CoroutineContext,
    ): ByteReadChannel = throw UnsupportedOperationException("Encode not implemented by the library yet.")

    override fun encode(
        source: ByteWriteChannel,
        coroutineContext: CoroutineContext,
    ): ByteWriteChannel = throw UnsupportedOperationException("Encode not implemented by the library yet.")
}

/** Registers [BrotliEncoder] on this [ContentEncodingConfig], as SimpMusic's helper did. */
fun ContentEncodingConfig.brotli(quality: Float? = null) {
    customEncoder(BrotliEncoder, quality)
}
