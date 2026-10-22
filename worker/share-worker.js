// Wavlin share server + link previews (Cloudflare Worker).
//
//   POST /p               {name, ids[], card?(base64 jpeg)} -> {code}   stores a shared playlist
//   GET  /p/<code>        share page (Open Graph preview tags, hands the playlist to the app)
//   GET  /p/<code>.json   {name, ids[]}  (the app reads this when it opens a short link)
//   GET  /card/<id>.jpg   the card picture used as the link preview
//   POST /card/<id>       stores a card picture for a synced playlist id (kept once)
//   GET  /?list=<id> | /?name=..&ids=..   older link formats
//
// Needs a KV namespace bound to this Worker with the variable name CARDS (data is kept 180 days).

const FALLBACK_IMAGE = "https://raw.githubusercontent.com/dvaustria741-a11y/Wavlin/main/docs/preview.png";
const MAX_CARD_BYTES = 800 * 1024;
const MAX_IDS = 10000;
const TTL_SECONDS = 180 * 24 * 3600;
const ID_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;
const VIDEO_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/;
const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
const PAGE = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>Wavlin \u2013 Shared playlist</title>\n<meta name=\"theme-color\" content=\"#121212\">\n<!--OG-->\n<style>\n  :root { --accent: #c9a8c4; --bg: #121212; --card: #1e1e1e; --text: #f2f2f2; --muted: #a8a8a8; }\n  * { box-sizing: border-box; }\n  body {\n    margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;\n    background: radial-gradient(circle at 50% 0%, #2a2230 0%, var(--bg) 60%);\n    color: var(--text); font-family: system-ui, -apple-system, \"Segoe UI\", Roboto, sans-serif;\n  }\n  .card {\n    width: min(92vw, 420px); padding: 32px 24px; border-radius: 28px;\n    background: var(--card); text-align: center; box-shadow: 0 12px 40px rgba(0,0,0,.45);\n  }\n  .brand { font-weight: 700; letter-spacing: .08em; color: var(--accent); font-size: 14px; text-transform: uppercase; }\n  h1 { margin: 12px 0 6px; font-size: 24px; word-break: break-word; }\n  p { margin: 0 0 24px; color: var(--muted); font-size: 15px; line-height: 1.45; }\n  .btn {\n    display: block; width: 100%; padding: 15px; margin-top: 12px; border-radius: 999px;\n    font-size: 16px; font-weight: 600; text-decoration: none; border: 0;\n  }\n  .primary { background: var(--accent); color: #1a1220; }\n  .secondary { background: transparent; color: var(--text); border: 1px solid #3a3a3a; }\n  .hidden { display: none; }\n</style>\n</head>\n<body>\n<main class=\"card\">\n  <div class=\"brand\">Wavlin</div>\n  <h1 id=\"title\">Shared playlist</h1>\n  <p id=\"subtitle\">Open this playlist in Wavlin.</p>\n  <a id=\"open\" class=\"btn primary\" href=\"#\">Open in Wavlin</a>\n  <a id=\"youtube\" class=\"btn secondary hidden\" href=\"#\">Open in YouTube Music</a>\n  <a id=\"get\" class=\"btn secondary\" href=\"https://github.com/dvaustria741-a11y/Wavlin/releases\">Get Wavlin</a>\n</main>\n<script>\n(function () {\n  var params = new URLSearchParams(location.search);\n  var list = params.get('list');\n  var ids = params.get('ids');\n  var name = params.get('name');\n\n  var query = list\n    ? 'list=' + encodeURIComponent(list)\n    : 'name=' + encodeURIComponent(name || '') + '&ids=' + encodeURIComponent(ids || '');\n  var route = list ? 'playlist' : 'shared';\n  var hasPlaylist = !!(list || ids);\n\n  if (!hasPlaylist) {\n    document.getElementById('subtitle').textContent = 'This link does not contain a playlist.';\n    document.getElementById('open').classList.add('hidden');\n    return;\n  }\n\n  if (name) document.getElementById('title').textContent = name;\n  if (ids) {\n    var count = ids.split(',').filter(Boolean).length;\n    document.getElementById('subtitle').textContent = count + ' track' + (count === 1 ? '' : 's') + ' \u00b7 tap to open in Wavlin';\n  }\n\n  var youtubeUrl = list ? 'https://music.youtube.com/playlist?list=' + encodeURIComponent(list) : '';\n  if (youtubeUrl) {\n    var yt = document.getElementById('youtube');\n    yt.href = youtubeUrl;\n    yt.classList.remove('hidden');\n  }\n\n  var customScheme = 'wavlin://' + route + '?' + query;\n  var intentUrl = 'intent://' + route + '?' + query +\n    '#Intent;scheme=wavlin;package=com.wavlin.music;' +\n    (youtubeUrl ? 'S.browser_fallback_url=' + encodeURIComponent(youtubeUrl) + ';' : '') + 'end';\n  var isAndroid = /Android/i.test(navigator.userAgent);\n  var target = isAndroid ? intentUrl : customScheme;\n\n  var open = document.getElementById('open');\n  open.href = target;\n\n  // Try once automatically; the button covers browsers that block unprompted app launches.\n  if (isAndroid) setTimeout(function () { location.href = target; }, 400);\n})();\n</script>\n</body>\n</html>\n";

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });

function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(bytes, (b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join("");
}

function renderPage({ title, desc, image, url, search }) {
  const og = [
    '<meta name="description" content="' + esc(desc) + '">',
    '<meta property="og:type" content="music.playlist">',
    '<meta property="og:site_name" content="Wavlin">',
    '<meta property="og:title" content="' + esc(title) + '">',
    '<meta property="og:description" content="' + esc(desc) + '">',
    '<meta property="og:url" content="' + esc(url) + '">',
    '<meta property="og:image" content="' + esc(image) + '">',
    '<meta name="twitter:card" content="summary_large_image">',
  ].join("\n");

  const html = PAGE.replace("<!--OG-->", og)
    .replace("<title>Wavlin \u2013 Shared playlist</title>", "<title>" + esc(title) + " \u2013 Wavlin</title>")
    .replace('<h1 id="title">Shared playlist</h1>', '<h1 id="title">' + esc(title) + "</h1>")
    .replace("new URLSearchParams(location.search)", "new URLSearchParams(" + JSON.stringify(search).replace(/</g, "\\u003c") + ")");

  return new Response(html, {
    headers: { "content-type": "text/html; charset=UTF-8", "cache-control": "public, max-age=300" },
  });
}

async function createPlaylist(request, env) {
  let data;
  try {
    data = await request.json();
  } catch (e) {
    return json({ error: "bad json" }, 400);
  }
  const ids = Array.isArray(data.ids) ? data.ids.filter((id) => VIDEO_ID_PATTERN.test(id)).slice(0, MAX_IDS) : [];
  if (ids.length === 0) return json({ error: "no tracks" }, 400);
  const name = String(data.name || "").slice(0, 100) || "Shared playlist";

  let card = null;
  if (data.card) {
    try {
      card = Uint8Array.from(atob(data.card), (c) => c.charCodeAt(0));
    } catch (e) {
      card = null;
    }
    if (card && card.byteLength > MAX_CARD_BYTES) card = null;
  }

  const code = newCode();
  await env.CARDS.put("pl:" + code, JSON.stringify({ name, ids }), { expirationTtl: TTL_SECONDS });
  if (card) await env.CARDS.put("card:" + code, card, { expirationTtl: TTL_SECONDS });
  return json({ code }, 201);
}

async function handleCard(request, env, id) {
  if (!ID_PATTERN.test(id)) return new Response("Not found", { status: 404 });
  const key = "card:" + id;

  if (request.method === "POST") {
    if (await env.CARDS.get(key, "stream")) return new Response("exists", { status: 409 });
    const body = await request.arrayBuffer();
    if (body.byteLength === 0 || body.byteLength > MAX_CARD_BYTES) return new Response("bad size", { status: 413 });
    await env.CARDS.put(key, body, { expirationTtl: TTL_SECONDS });
    return new Response("ok", { status: 201 });
  }

  const image = await env.CARDS.get(key, "arrayBuffer");
  if (!image) return new Response("Not found", { status: 404 });
  return new Response(image, { headers: { "content-type": "image/jpeg", "cache-control": "public, max-age=86400" } });
}

async function handleStoredPlaylist(url, env, rawCode) {
  const asJson = rawCode.endsWith(".json");
  const code = rawCode.replace(/\.json$/, "");
  const stored = await env.CARDS.get("pl:" + code, "json");
  if (!stored) return new Response("Playlist not found or expired", { status: 404 });
  if (asJson) return json(stored);

  const hasCard = !!(await env.CARDS.get("card:" + code, "stream"));
  const image = hasCard
    ? url.origin + "/card/" + code + ".jpg"
    : "https://i.ytimg.com/vi/" + encodeURIComponent(stored.ids[0]) + "/hqdefault.jpg";
  return renderPage({
    title: stored.name,
    desc: stored.ids.length + " tracks \u00b7 tap to open in Wavlin",
    image,
    url: url.href,
    search: "name=" + encodeURIComponent(stored.name) + "&ids=" + stored.ids.join(","),
  });
}

async function handleLegacy(url, env) {
  const list = url.searchParams.get("list");
  const ids = (url.searchParams.get("ids") || "").split(",").filter(Boolean);
  let title = url.searchParams.get("name") || "Playlist on Wavlin";
  let image = FALLBACK_IMAGE;
  let desc = "Tap to open this playlist in Wavlin.";

  try {
    if (list) {
      const res = await fetch(
        "https://www.youtube.com/oembed?format=json&url=" +
          encodeURIComponent("https://www.youtube.com/playlist?list=" + list),
        { cf: { cacheTtl: 3600, cacheEverything: true } },
      );
      if (res.ok) {
        const data = await res.json();
        title = data.title || title;
        image = data.thumbnail_url || image;
      }
      if (env.CARDS && ID_PATTERN.test(list) && (await env.CARDS.get("card:" + list, "stream"))) {
        image = url.origin + "/card/" + list + ".jpg";
      }
    } else if (ids.length) {
      image = "https://i.ytimg.com/vi/" + encodeURIComponent(ids[0]) + "/hqdefault.jpg";
      desc = ids.length + " tracks \u00b7 tap to open in Wavlin";
    }
  } catch (e) {
    // keep the defaults
  }

  return renderPage({ title, desc, image, url: url.href, search: url.search });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!env.CARDS) return new Response("KV binding CARDS is missing", { status: 500 });

    if (url.pathname === "/p" && request.method === "POST") return createPlaylist(request, env);

    const playlistMatch = url.pathname.match(/^\/p\/([A-Za-z0-9]{4,32}(?:\.json)?)$/);
    if (playlistMatch) return handleStoredPlaylist(url, env, playlistMatch[1]);

    const cardMatch = url.pathname.match(/^\/card\/([^/]+?)(?:\.jpg)?$/);
    if (cardMatch) return handleCard(request, env, cardMatch[1]);

    return handleLegacy(url, env);
  },
};
