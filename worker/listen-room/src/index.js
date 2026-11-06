const json = (value, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { "content-type": "application/json" },
});
const envelope = (type, payload = {}) => JSON.stringify({ type, payload });
const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const makeCode = () => Array.from(crypto.getRandomValues(new Uint8Array(6)), b => CODE_CHARS[b % CODE_CHARS.length]).join("");

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return json({ service: "wavlin-listen-room", status: "ok" });
    if (request.headers.get("Upgrade") !== "websocket") return json({ error: "WebSocket upgrade required" }, 426);
    const room = (url.searchParams.get("room") || "").toUpperCase();
    if (!room) return json({ error: "room query parameter required" }, 400);
    return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(request);
  },
};

export class ListeningRoom {
  constructor(state) {
    this.state = state;
    this.sessions = new Map();
    this.room = null;
    this.votes = new Map(); // trackId -> userId -> up/down
    this.voteRevision = 0;
    this.votingEnabled = true;
  }

  async fetch(request) {
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    const session = { socket: server, userId: null, username: "Listener" };
    this.sessions.set(server, session);
    server.addEventListener("message", event => this.onMessage(session, event.data));
    server.addEventListener("close", () => this.onClose(session));
    server.addEventListener("error", () => this.onClose(session));
    return new Response(null, { status: 101, webSocket: client });
  }

  send(session, type, payload) {
    try { session.socket.send(envelope(type, payload)); } catch (_) { this.sessions.delete(session.socket); }
  }
  broadcast(type, payload) {
    for (const session of this.sessions.values()) this.send(session, type, payload);
  }
  broadcastUsers() {
    this.broadcast("room_users", { users: [...this.sessions.values()].filter(s => s.userId).map(s => ({
      user_id: s.userId, username: s.username, is_host: s.userId === this.room?.hostId, is_connected: true,
    })) });
  }
  snapshotVotes() {
    const votes = [];
    for (const [trackId, byUser] of this.votes) {
      let up = 0, down = 0;
      for (const choice of byUser.values()) choice === "up" ? up++ : down++;
      votes.push({ track_id: trackId, up, down });
    }
    return { votes, voting_enabled: this.votingEnabled, revision: this.voteRevision };
  }
  broadcastVotes() { this.broadcast("queue_vote_state", this.snapshotVotes()); }

  onMessage(session, raw) {
    let msg;
    try { msg = JSON.parse(raw); } catch (_) { return this.send(session, "error", { code: "bad_json", message: "Invalid JSON" }); }
    const type = msg.type, p = msg.payload || {};
    if (type === "create_room") {
      if (this.room) return this.send(session, "error", { code: "room_exists", message: "Room already initialized" });
      const code = makeCode();
      session.userId = crypto.randomUUID(); session.username = String(p.username || "Host").slice(0, 32);
      this.room = { code, hostId: session.userId, queue: [], createdAt: Date.now() };
      this.send(session, "room_created", { room_code: code, user_id: session.userId, session_token: crypto.randomUUID() });
      this.broadcastUsers(); return;
    }
    if (type === "join_room") {
      if (!this.room) this.room = { code: "ROOM", hostId: null, queue: [], createdAt: Date.now() };
      if (!session.userId) { session.userId = crypto.randomUUID(); session.username = String(p.username || "Listener").slice(0, 32); }
      if (!this.room.hostId) this.room.hostId = session.userId;
      this.send(session, "join_approved", { room_code: this.room.code, user_id: session.userId, session_token: crypto.randomUUID(), state: { room_code: this.room.code, host_id: this.room.hostId, users: [], queue: this.room.queue, is_playing: false, position: 0, last_update: Date.now(), revision: 0 } });
      this.broadcastUsers(); this.broadcastVotes(); return;
    }
    if (!session.userId) return this.send(session, "error", { code: "not_joined", message: "Join a room first" });
    if (type === "leave_room") { this.onClose(session); session.socket.close(1000, "left"); return; }
    if (type === "vote_queue_track") {
      if (!this.votingEnabled) return this.send(session, "error", { code: "voting_closed", message: "Voting is closed" });
      const id = String(p.track_id || "");
      const vote = p.vote;
      if (!id || !["up", "down"].includes(vote)) return this.send(session, "error", { code: "invalid_vote", message: "track_id and up/down vote required" });
      if (!this.votes.has(id)) this.votes.set(id, new Map());
      this.votes.get(id).set(session.userId, vote);
      this.voteRevision++; this.broadcastVotes(); return;
    }
    if (type === "set_voting") {
      if (session.userId !== this.room?.hostId) return this.send(session, "error", { code: "host_only", message: "Host permission required" });
      this.votingEnabled = !!p.enabled; this.voteRevision++; this.broadcastVotes(); return;
    }
    if (type === "close_queue_vote") {
      if (session.userId !== this.room?.hostId) return this.send(session, "error", { code: "host_only", message: "Host permission required" });
      const ranked = [...this.votes.entries()].map(([trackId, byUser], index) => {
        let score = 0; for (const vote of byUser.values()) score += vote === "up" ? 1 : -1;
        return { trackId, score, index };
      }).sort((a, b) => b.score - a.score || a.index - b.index);
      this.votingEnabled = false; this.voteRevision++;
      this.broadcast("queue_vote_closed", { winning_track_id: ranked[0]?.trackId || null, revision: this.voteRevision });
      this.broadcastVotes(); return;
    }
    this.send(session, "error", { code: "unsupported", message: "Unsupported message type" });
  }

  onClose(session) {
    this.sessions.delete(session.socket);
    if (session.userId) this.broadcastUsers();
  }
}
