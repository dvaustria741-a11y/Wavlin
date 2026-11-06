/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.listentogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for server-authoritative listening-room queue votes.
 *
 * The room server must validate membership, deduplicate a user's vote, and broadcast
 * QueueVoteStatePayload after every accepted vote. The client intentionally does not
 * calculate a winner locally: all participants must observe the same server result.
 */
object QueueVoteTypes {
    const val VOTE_QUEUE_TRACK = "vote_queue_track"
    const val QUEUE_VOTE_STATE = "queue_vote_state"
    const const val QUEUE_VOTE_CLOSED = "queue_vote_closed"
}

@Serializable
data class QueueVotePayload(
    @SerialName("track_id") val trackId: String,
    val vote: QueueVoteChoice,
)

@Serializable
enum class QueueVoteChoice {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
}

@Serializable
data class QueueVoteTally(
    @SerialName("track_id") val trackId: String,
    val up: Int = 0,
    val down: Int = 0,
)

@Serializable
data class QueueVoteStatePayload(
    val votes: List<QueueVoteTally> = emptyList(),
    @SerialName("voting_enabled") val votingEnabled: Boolean = true,
    @SerialName("closes_at") val closesAt: Long? = null,
    val revision: Long = 0L,
)

@Serializable
data class QueueVoteClosedPayload(
    @SerialName("winning_track_id") val winningTrackId: String? = null,
    val revision: Long = 0L,
)
