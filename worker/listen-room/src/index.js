const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json" } });
const envelope = (type, payload = {}) => JSON.stringify({ type, payload });
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const code = () => Array.from(crypto.getRandomValues(new Uint8Array(6)), b => ALPHABET[b % ALPHABET.length]).join("");

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return json({ service: "wavlin-listen-room", status: "ok" });
    if (url.pathname === "/rooms" && request.method === "POST") {
      const roomCode = code();
      const stub = env.ROOMS.get(env.ROOMS.idFromName(roomCode));
      await stub.fetch("https://room/init", { method: "POST", body: roomCode });
      return json({ room_code: roomCode, websocket: `/room?room=${roomCode}` }, 201);
    }
    if (url.pathname === "/room" && request.headers.get("Upgrade") === "websocket") {
      const room = (url.searchParams.get("room") || "").toUpperCase();
      if (!/^[A-Z2-9]{6}$/.test(room)) return json({ error: "invalid room code" }, 400);
      return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(request);
    }
    return json({ error: "not found" }, 404);
  },
};

export class ListeningRoom {
  constructor(state) {
    this.state = state;
    this.sessions = new Map();
    this.roomCode = null;
    this.hostId = null;
    this.votes = new Map();
    this.voteRevision = 0;
    this.votingEnabled = true;
  }
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/init" && request.method === "POST") {
      this.roomCode = await request.text();
      await this.state.storage.put("roomCode", this.roomCode);
      return new Response("ok");
    }
    if (url.pathname !== "/room" || request.headers.get("Upgrade") !== "websocket") return new Response("Not found", {status:404});
    this.roomCode ||= await this.state.storage.get("roomCode");
    if (!this.roomCode) return json({error:"room not initialized"},404);
    const pair = new WebSocketPair(), client = pair[0], server = pair[1];
    server.accept();
    const session = {socket:server,userId:null,username:"Listener"};
    this.sessions.set(server,session);
    server.addEventListener("message",e=>this.onMessage(session,e.data));
    server.addEventListener("close",()=>this.onClose(session));
    server.addEventListener("error",()=>this.onClose(session));
    return new Response(null,{status:101,webSocket:client});
  }
  send(s,type,payload={}) { try{s.socket.send(envelope(type,payload));}catch{this.sessions.delete(s.socket);} }
  broadcast(type,payload={}) { for(const s of this.sessions.values())this.send(s,type,payload); }
  users(){return [...this.sessions.values()].filter(s=>s.userId).map(s=>({user_id:s.userId,username:s.username,is_host:s.userId===this.hostId,is_connected:true}));}
  broadcastUsers(){this.broadcast("room_users",{users:this.users()});}
  voteState(){const votes=[];for(const [track_id,m] of this.votes){let up=0,down=0;for(const v of m.values())v==="up"?up++:down++;votes.push({track_id,up,down});}return {votes,voting_enabled:this.votingEnabled,revision:this.voteRevision};}
  broadcastVotes(){this.broadcast("queue_vote_state",this.voteState());}
  async onMessage(s,raw){
    let m;try{m=JSON.parse(raw);}catch{return this.send(s,"error",{code:"bad_json",message:"Invalid JSON"});}
    const p=m.payload||{};
    if(m.type==="create_room"){
      if(this.hostId)return this.send(s,"error",{code:"room_exists",message:"Room already has a host"});
      s.userId=crypto.randomUUID();s.username=String(p.username||"Host").slice(0,32);this.hostId=s.userId;
      await this.state.storage.put("hostId",this.hostId);
      this.send(s,"room_created",{room_code:this.roomCode,user_id:s.userId,session_token:crypto.randomUUID()});this.broadcastUsers();return;
    }
    if(m.type==="join_room"){
      s.userId ||= crypto.randomUUID();s.username=String(p.username||"Listener").slice(0,32);
      this.send(s,"join_approved",{room_code:this.roomCode,user_id:s.userId,session_token:crypto.randomUUID(),state:{room_code:this.roomCode,host_id:this.hostId,users:this.users(),queue:[],current_track:null,is_playing:false,position:0,last_update:Date.now(),revision:0}});
      this.broadcastUsers();this.broadcastVotes();return;
    }
    if(!s.userId)return this.send(s,"error",{code:"not_joined",message:"Join a room first"});
    if(m.type==="leave_room"){this.onClose(s);s.socket.close(1000,"left");return;}
    if(m.type==="vote_queue_track"){
      if(!this.votingEnabled)return this.send(s,"error",{code:"voting_closed",message:"Voting is closed"});
      const id=String(p.track_id||""),v=p.vote;if(!id||!["up","down"].includes(v))return this.send(s,"error",{code:"invalid_vote",message:"track_id and up/down required"});
      if(!this.votes.has(id))this.votes.set(id,new Map());this.votes.get(id).set(s.userId,v);this.voteRevision++;this.broadcastVotes();return;
    }
    if(m.type==="set_voting"){
      if(s.userId!==this.hostId)return this.send(s,"error",{code:"host_only",message:"Host permission required"});
      this.votingEnabled=!!p.enabled;this.voteRevision++;this.broadcastVotes();return;
    }
    if(m.type==="close_queue_vote"){
      if(s.userId!==this.hostId)return this.send(s,"error",{code:"host_only",message:"Host permission required"});
      const rank=[...this.votes].map(([trackId,m],i)=>({trackId,i,score:[...m.values()].reduce((n,v)=>n+(v==="up"?1:-1),0)})).sort((a,b)=>b.score-a.score||a.i-b.i);
      this.votingEnabled=false;this.voteRevision++;this.broadcast("queue_vote_closed",{winning_track_id:rank[0]?.trackId||null,revision:this.voteRevision});this.broadcastVotes();return;
    }
    this.send(s,"error",{code:"unsupported",message:"Unsupported message type"});
  }
  onClose(s){this.sessions.delete(s.socket);if(s.userId)this.broadcastUsers();}
}
