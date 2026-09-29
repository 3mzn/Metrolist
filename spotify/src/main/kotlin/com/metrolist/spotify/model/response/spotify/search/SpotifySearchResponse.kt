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
 * Ported from SimpMusic, core/service/spotify (GPL-3.0), with only these
 * mechanical changes: package and import rewriting to Metrolist's namespace, and
 * collapsing the KMP expect/ctual TOTP split into a single Android file.
 *
 * DO NOT REWRITE THE LOGIC. It is working, device-verified code. The @ProtoNumber
 * annotations in particular MUST be preserved verbatim: Spotify is spoken to in
 * protobuf, and dropping them corrupts field mapping silently rather than
 * failing to compile. See SPEC_SPOTIFY_CANVAS.md 4.2.
 * ---------------------------------------------------------------------------
 */
package com.metrolist.spotify.model.response.spotify.search

import kotlinx.serialization.Serializable

@Serializable
data class SpotifySearchResponse(
    val data: Data? = null,
) {
    @Serializable
    data class Data(
        val searchV2: Search,
    ) {
        @Serializable
        data class Search(
            val query: String? = null,
            val tracksV2: TracksV2? = null,
        ) {
            @Serializable
            data class TracksV2(
                val items: List<Items>? = null,
                val pagingInfo: PagingInfo? = null,
                val totalCount: Int? = null,
            ) {
                @Serializable
                data class PagingInfo(
                    val limit: Int? = null,
                    val nextOffset: Int? = null,
                )

                @Serializable
                data class Items(
                    val item: Item? = null,
                ) {
                    @Serializable
                    data class Item(
                        val data: DataX? = null,
                    ) {
                        @Serializable
                        data class DataX(
                            val id: String? = null,
                            val name: String? = null,
                            val artists: Artists? = null,
                            val duration: Duration? = null,
                        ) {
                            @Serializable
                            data class Duration(
                                val totalMilliseconds: Int? = null,
                            )

                            @Serializable
                            data class Artists(
                                val items: List<ItemX>? = null,
                            ) {
                                @Serializable
                                data class ItemX(
                                    val profile: Profile? = null,
                                    val uri: String? = null,
                                ) {
                                    @Serializable
                                    data class Profile(
                                        val name: String? = null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}