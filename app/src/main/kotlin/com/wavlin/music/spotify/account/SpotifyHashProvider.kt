/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from SpatialFlow (https://github.com/MythicalSHUB/SpatialFlow),
 * Copyright (c) 2026 Shubham Karande, permissively licensed (MIT per its README, Apache-2.0 per its LICENSE file).
 */

package com.wavlin.music.spotify.account

/**
 * Persisted-query hashes of the Spotify web player's GraphQL operations.
 * These are tied to the web player build, so they can go stale when Spotify ships a new one.
 */
object SpotifyHashProvider {
    private val hashes =
        mapOf(
            "profileAttributes" to "53bcb064f6cd18c23f752bc324a791194d20df612d8e1239c735144ab0399ced",
            "libraryV3" to "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3",
            "fetchPlaylist" to "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763",
            "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
        )

    fun getHash(operationName: String): String = hashes[operationName] ?: error("No hash registered for GQL operation: $operationName")
}
