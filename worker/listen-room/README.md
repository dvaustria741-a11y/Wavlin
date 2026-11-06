# Wavlin Listening Room Worker

This is a Cloudflare Worker + Durable Objects room backend for Listening Room 2.0.
It is intentionally separate from `worker/share-worker.js` (playlist sharing).

## Deploy

1. Install Wrangler and authenticate with Cloudflare.
2. From this directory, run `wrangler deploy`.
3. Configure the Android Listen Together server URL to this Worker endpoint (WebSocket URL).
4. Keep the Durable Object migration in `wrangler.toml` intact.

## Protocol

WebSocket JSON envelopes use `{ "type": "...", "payload": {...} }`.

Supported initial room operations:
- `create_room`: {username}
- `join_room`: {room_code, username} (joins immediately in this starter backend)
- `leave_room`
- `vote_queue_track`: {track_id, vote: "up"|"down}
- `queue_vote_state`: server broadcast containing votes, voting_enabled, revision
- `set_voting`: host-only {enabled}
- `close_queue_vote`: host-only; chooses the track with highest net score (up-down), then earliest insertion order, broadcasts close result

The room server is authoritative: a user has one vote per track, and a later vote replaces their earlier vote.
