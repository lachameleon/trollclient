/* TROLL CLIENT HOMEPAGE - the Cloudflare Worker.
   Static files come straight from ./public (the assets binding). The Worker only runs for
   /api/* and /downloads/*, and everything that has to be remembered lives in one Durable
   Object, TrollHQ: the hit counter, downloads, the guestbook, the poll, a couple of fun
   global tallies, and live WebSockets that push all of it to every open tab. Anonymous
   usage stats from the client itself live in a second object, TrollTelemetry (telemetry.js),
   and feed the analytics page. */
import { DurableObject } from "cloudflare:workers";
export { TrollTelemetry } from "./telemetry.js";

const POLL = [
	["skinblink", "SkinBlink (skin animation)"],
	["twerk", "Twerk"],
	["nowayhome", "NoWayHome"],
	["parrot", "Parrot"],
	["racket", "Racket"],
	["honk", "Honk"]
];
const BUMPABLE = { toggles: 100, blinks: 400 };
const AVATARS = ["blink", "troll", "mime", "referee", "ghost", "hacker"];
const PAGE_SIZE = 20;

const json = (data, status = 200, headers = {}) =>
	new Response(JSON.stringify(data), {
		status,
		headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers }
	});

const today = () => new Date().toISOString().slice(0, 10);

/** A visitor fingerprint that can't be turned back into an IP: salted, and the salt changes daily. */
async function visitorHash(request, env) {
	const ip = request.headers.get("cf-connecting-ip") || "local";
	const data = new TextEncoder().encode((env.SALT || "troll-salt") + "|" + today() + "|" + ip);
	const digest = await crypto.subtle.digest("SHA-256", data);
	return [...new Uint8Array(digest).slice(0, 12)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Strips control characters (keeps newlines where asked) and trims to a length. */
function clean(value, max, multiline = false) {
	if (typeof value !== "string") return "";
	const pattern = multiline ? /[\u0000-\u0009\u000b-\u001f\u007f‪-‮⁦-⁩]/g : /[\u0000-\u001f\u007f‪-‮⁦-⁩]/g;
	let out = value.replace(pattern, "").trim();
	if (multiline) out = out.replace(/\n{3,}/g, "\n\n");
	return [...out].slice(0, max).join("");
}

export class TrollHQ extends DurableObject {
	constructor(ctx, env) {
		super(ctx, env);
		this.sql = ctx.storage.sql;
		// in memory only: who did what recently, for throttling. Losing it on eviction is fine.
		this.recent = new Map();
		this.pending = null;
		ctx.blockConcurrencyWhile(async () => this.migrate());
		// keep-alives are answered without waking the object up
		ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
	}

	migrate() {
		this.sql.exec(`
			CREATE TABLE IF NOT EXISTS counters (name TEXT PRIMARY KEY, value INTEGER NOT NULL DEFAULT 0);
			CREATE TABLE IF NOT EXISTS daily (day TEXT PRIMARY KEY, visits INTEGER NOT NULL DEFAULT 0, views INTEGER NOT NULL DEFAULT 0);
			CREATE TABLE IF NOT EXISTS poll (option TEXT PRIMARY KEY, votes INTEGER NOT NULL DEFAULT 0);
			CREATE TABLE IF NOT EXISTS poll_voters (who TEXT PRIMARY KEY, day TEXT NOT NULL);
			CREATE TABLE IF NOT EXISTS guestbook (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				name TEXT NOT NULL, site TEXT NOT NULL DEFAULT '', msg TEXT NOT NULL,
				avatar TEXT NOT NULL DEFAULT 'troll', at INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0
			);
		`);
		for (const [id] of POLL) this.sql.exec("INSERT OR IGNORE INTO poll (option, votes) VALUES (?, 0)", id);
		// the odometer was already rolling when the site moved here
		this.sql.exec("INSERT OR IGNORE INTO counters (name, value) VALUES ('visits', 1337), ('views', 4200), ('downloads', 0), ('toggles', 0), ('blinks', 0)");
		if (this.sql.exec("SELECT COUNT(*) AS n FROM guestbook").one().n === 0) {
			this.sql.exec("INSERT INTO guestbook (name, site, msg, avatar, at) VALUES (?, '', ?, 'troll', ?)",
				"webmaster", "welcome to the guestbook! it's real now: everyone can read what you write here, so be nice. ish.",
				Date.parse("2026-10-04T12:00:00Z"));
		}
	}

	counter(name) {
		const row = this.sql.exec("SELECT value FROM counters WHERE name = ?", name).toArray()[0];
		return row ? row.value : 0;
	}

	add(name, n = 1) {
		this.sql.exec("INSERT INTO counters (name, value) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET value = value + excluded.value", name, n);
	}

	/** True if `key` hasn't happened in the last `ms` milliseconds (and records that it has now). */
	allow(key, ms) {
		const now = Date.now();
		const last = this.recent.get(key);
		if (last && now - last < ms) return false;
		this.recent.set(key, now);
		if (this.recent.size > 5000) {
			for (const [k, t] of this.recent) if (now - t > 3600000) this.recent.delete(k);
		}
		return true;
	}

	stats() {
		const days = this.sql.exec("SELECT day, visits, views FROM daily ORDER BY day DESC LIMIT 14").toArray().reverse();
		const t = days.length && days[days.length - 1].day === today() ? days[days.length - 1] : { visits: 0, views: 0 };
		const latest = this.sql.exec("SELECT name, substr(msg, 1, 80) AS msg, at FROM guestbook WHERE hidden = 0 ORDER BY id DESC LIMIT 1").toArray()[0] || null;
		return {
			visits: this.counter("visits"),
			views: this.counter("views"),
			downloads: this.counter("downloads"),
			toggles: this.counter("toggles"),
			blinks: this.counter("blinks"),
			guestbook: this.sql.exec("SELECT COUNT(*) AS n FROM guestbook WHERE hidden = 0").one().n,
			online: this.ctx.getWebSockets().length,
			today: t.visits,
			todayViews: t.views,
			days,
			latest
		};
	}

	// ------------------------------------------------------------ live updates
	broadcast(message) {
		const text = JSON.stringify(message);
		for (const ws of this.ctx.getWebSockets()) {
			try { ws.send(text); } catch (e) { /* closing */ }
		}
	}

	/** Stats go out at most twice a second, however busy it gets. */
	pushStats() {
		if (this.pending) return;
		this.pending = setTimeout(() => {
			this.pending = null;
			this.broadcast({ type: "stats", stats: this.stats() });
		}, 500);
	}

	async fetch(request) {
		if (request.headers.get("upgrade") !== "websocket") return new Response("expected a websocket", { status: 426 });
		const [client, server] = Object.values(new WebSocketPair());
		this.ctx.acceptWebSocket(server);
		server.send(JSON.stringify({ type: "stats", stats: this.stats() }));
		this.pushStats();
		return new Response(null, { status: 101, webSocket: client });
	}

	webSocketMessage() { /* nothing to say back; pings are auto-answered */ }

	webSocketClose(ws, code) {
		const ok = code >= 1000 && code < 5000 && ![1005, 1006, 1015].includes(code);
		try { ws.close(ok ? code : 1000, "bye"); } catch (e) { /* already gone */ }
		this.pushStats();
	}

	webSocketError() {
		this.pushStats();
	}

	// ------------------------------------------------------------ RPC
	hit(who, fresh) {
		const day = today();
		// a reload storm from one visitor counts once every couple of seconds
		const view = this.allow("view:" + who, 2000);
		// and a "new visit" at most once every half hour
		const visit = fresh && this.allow("visit:" + who, 1800000);
		if (view) this.add("views");
		if (visit) this.add("visits");
		if (view || visit) {
			this.sql.exec("INSERT INTO daily (day, visits, views) VALUES (?, ?, ?) ON CONFLICT(day) DO UPDATE SET visits = visits + excluded.visits, views = views + excluded.views",
				day, visit ? 1 : 0, view ? 1 : 0);
			this.pushStats();
		}
		if (visit) this.broadcast({ type: "visit", visits: this.counter("visits") });
		return { stats: this.stats(), counted: visit };
	}

	download() {
		this.add("downloads");
		this.broadcast({ type: "download", downloads: this.counter("downloads") });
		this.pushStats();
	}

	bump(who, key, n) {
		const max = BUMPABLE[key];
		if (!max || !this.allow("bump:" + key + ":" + who, 4000)) return false;
		this.add(key, Math.max(0, Math.min(max, Math.floor(n) || 0)));
		this.pushStats();
		return true;
	}

	guestbook(before) {
		const rows = before
			? this.sql.exec("SELECT id, name, site, msg, avatar, at FROM guestbook WHERE hidden = 0 AND id < ? ORDER BY id DESC LIMIT ?", before, PAGE_SIZE + 1).toArray()
			: this.sql.exec("SELECT id, name, site, msg, avatar, at FROM guestbook WHERE hidden = 0 ORDER BY id DESC LIMIT ?", PAGE_SIZE + 1).toArray();
		return { entries: rows.slice(0, PAGE_SIZE), more: rows.length > PAGE_SIZE, total: this.stats().guestbook };
	}

	sign(who, entry) {
		if (!this.allow("sign:" + who, 45000)) return { error: "slow down! one entry every 45 seconds.", status: 429 };
		const last = this.sql.exec("SELECT msg FROM guestbook ORDER BY id DESC LIMIT 1").toArray()[0];
		if (last && last.msg === entry.msg) return { error: "someone just said exactly that.", status: 409 };
		const at = Date.now();
		const id = this.sql.exec("INSERT INTO guestbook (name, site, msg, avatar, at) VALUES (?, ?, ?, ?, ?) RETURNING id",
			entry.name, entry.site, entry.msg, entry.avatar, at).one().id;
		const saved = { id, ...entry, at };
		this.broadcast({ type: "guestbook", entry: saved });
		this.pushStats();
		return { entry: saved };
	}

	hide(id) {
		this.sql.exec("UPDATE guestbook SET hidden = 1 WHERE id = ?", id);
		this.broadcast({ type: "unsign", id });
		this.pushStats();
		return { ok: true };
	}

	poll() {
		const votes = Object.fromEntries(this.sql.exec("SELECT option, votes FROM poll").toArray().map((r) => [r.option, r.votes]));
		const options = POLL.map(([id, label]) => ({ id, label, votes: votes[id] || 0 }));
		return { options, total: options.reduce((a, o) => a + o.votes, 0) };
	}

	vote(who, option) {
		if (!POLL.some(([id]) => id === option)) return { error: "that's not on the ballot", status: 400 };
		// one vote a day: the visitor hash is salted per day, so yesterday's hashes can go
		const day = today();
		this.sql.exec("DELETE FROM poll_voters WHERE day <> ?", day);
		if (this.sql.exec("SELECT 1 FROM poll_voters WHERE who = ?", who).toArray().length) {
			return { error: "you already voted today", status: 429, ...this.poll() };
		}
		this.sql.exec("INSERT INTO poll_voters (who, day) VALUES (?, ?)", who, day);
		this.sql.exec("UPDATE poll SET votes = votes + 1 WHERE option = ?", option);
		const result = this.poll();
		this.broadcast({ type: "poll", poll: result });
		return result;
	}
}

// ------------------------------------------------------------ skins from Mojang, for the skin animation lab
async function playerSkin(name, ctx) {
	if (!/^[A-Za-z0-9_]{2,16}$/.test(name)) return json({ error: "that's not a minecraft username" }, 400);
	const cache = caches.default;
	const key = new Request("https://skins.troll.internal/v1/" + name.toLowerCase());
	const cached = await cache.match(key);
	if (cached) return cached;

	let res = await fetch("https://api.mojang.com/users/profiles/minecraft/" + name);
	if (!res.ok && res.status !== 404 && res.status !== 204) {
		res = await fetch("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name);
	}
	if (res.status === 404 || res.status === 204) return json({ error: "no player called " + name }, 404);
	if (!res.ok) return json({ error: "mojang isn't answering right now" }, 502);
	const { id } = await res.json();

	res = await fetch("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
	if (!res.ok) return json({ error: "mojang isn't answering right now" }, 502);
	const profile = await res.json();
	const prop = (profile.properties || []).find((p) => p.name === "textures");
	const textures = prop ? JSON.parse(atob(prop.value)).textures || {} : {};
	if (!textures.SKIN) return json({ error: name + " is wearing the default skin" }, 404);

	const png = await fetch(textures.SKIN.url.replace(/^http:/, "https:"));
	if (!png.ok) return json({ error: "couldn't fetch that skin" }, 502);
	const out = new Response(png.body, {
		headers: {
			"content-type": "image/png",
			"cache-control": "public, max-age=3600",
			"x-skin-model": textures.SKIN.metadata && textures.SKIN.metadata.model === "slim" ? "slim" : "classic",
			"x-skin-name": profile.name || name
		}
	});
	ctx.waitUntil(cache.put(key, out.clone()));
	return out;
}

async function body(request, max = 4096) {
	if (!(request.headers.get("content-type") || "").includes("application/json")) return null;
	const text = await request.text();
	if (text.length > max) return null;
	try { return JSON.parse(text); } catch (e) { return null; }
}

const telemetry = (env) => env.TELEMETRY.get(env.TELEMETRY.idFromName("global"));

async function api(request, env, ctx, url) {
	const hq = env.HQ.get(env.HQ.idFromName("global"));
	const path = url.pathname.replace(/\/+$/, "");
	const method = request.method;

	if (path === "/api/live") {
		if (request.headers.get("upgrade") !== "websocket") return json({ error: "this one's a websocket" }, 426);
		return hq.fetch(request);
	}
	if (path === "/api/stats" && method === "GET") return json(await hq.stats());
	if (path === "/api/hit" && method === "POST") {
		const data = (await body(request)) || {};
		return json(await hq.hit(await visitorHash(request, env), data.fresh === true));
	}
	if (path === "/api/bump" && method === "POST") {
		const data = (await body(request)) || {};
		const ok = await hq.bump(await visitorHash(request, env), String(data.key), Number(data.n));
		return json({ ok }, ok ? 200 : 429);
	}
	if (path === "/api/poll" && method === "GET") return json(await hq.poll());
	if (path === "/api/poll" && method === "POST") {
		const data = (await body(request)) || {};
		const { status, ...result } = await hq.vote(await visitorHash(request, env), String(data.option));
		return json(result, status || 200);
	}
	if (path === "/api/guestbook" && method === "GET") {
		const before = parseInt(url.searchParams.get("before") || "0", 10);
		return json(await hq.guestbook(before > 0 ? before : 0));
	}
	if (path === "/api/guestbook" && method === "POST") {
		const data = await body(request);
		if (!data) return json({ error: "that didn't look like a guestbook entry" }, 400);
		// bots fill in every field, including the one people can't see
		if (data.homepage2) return json({ entry: { id: 0, name: "bot", site: "", msg: "", avatar: "troll", at: Date.now() } });
		const name = clean(data.name, 32) || "anonymous coward";
		let site = clean(data.site, 120);
		const msg = clean(data.msg, 500, true);
		const avatar = AVATARS.includes(data.avatar) ? data.avatar : "troll";
		if (msg.length < 2) return json({ error: "say a bit more than that" }, 400);
		if (site && !/^https?:\/\//i.test(site)) site = "http://" + site;
		if (site) {
			try {
				const u = new URL(site);
				site = u.protocol === "http:" || u.protocol === "https:" ? u.href : "";
			} catch (e) { site = ""; }
		}
		const { status, ...result } = await hq.sign(await visitorHash(request, env), { name, site, msg, avatar });
		return json(result, status || 200);
	}
	const del = path.match(/^\/api\/guestbook\/(\d+)$/);
	if (del && method === "DELETE") {
		const token = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
		if (!env.ADMIN_TOKEN || token !== env.ADMIN_TOKEN) return json({ error: "nope" }, 403);
		return json(await hq.hide(parseInt(del[1], 10)));
	}
	if (path === "/api/telemetry" && method === "POST") {
		const report = await body(request, 65536);
		if (!report) return json({ error: "that didn't look like a report" }, 400);
		const cf = request.cf || {};
		// where, roughly: what Cloudflare already knows. Not the IP, not the city.
		const geo = { country: String(cf.country || ""), continent: String(cf.continent || ""), region: String(cf.region || "") };
		const { status, ...result } = await telemetry(env).ingest(report, geo, await visitorHash(request, env));
		return json(result, status || 200);
	}
	if (path === "/api/analytics" && method === "GET") {
		const days = url.searchParams.get("days") === "all" ? 3650 : parseInt(url.searchParams.get("days") || "30", 10);
		return json(await telemetry(env).analytics(days), 200, { "cache-control": "public, max-age=30" });
	}
	if (path === "/api/analytics/sessions" && method === "GET") {
		const token = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
		if (!env.ADMIN_TOKEN || token !== env.ADMIN_TOKEN) return json({ error: "nope" }, 403);
		return json({ sessions: await telemetry(env).latestSessions(parseInt(url.searchParams.get("limit") || "50", 10)) });
	}
	const skin = path.match(/^\/api\/skin\/([^/]+)$/);
	if (skin && method === "GET") return playerSkin(decodeURIComponent(skin[1]), ctx);

	return json({ error: "no such endpoint" }, 404);
}

export default {
	async fetch(request, env, ctx) {
		const url = new URL(request.url);
		if (url.pathname.startsWith("/api/")) {
			try {
				return await api(request, env, ctx, url);
			} catch (e) {
				console.error(e);
				return json({ error: "the server tripped over something" }, 500);
			}
		}
		const res = await env.ASSETS.fetch(request);
		// count real downloads of the jar, not HEAD requests or misses
		if (url.pathname.startsWith("/downloads/") && url.pathname.endsWith(".jar") && request.method === "GET" && res.status === 200) {
			const hq = env.HQ.get(env.HQ.idFromName("global"));
			ctx.waitUntil(hq.download());
		}
		return res;
	}
};
