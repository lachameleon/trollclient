/* TROLL CLIENT HOMEPAGE - the Cloudflare Worker.
   Static files come straight from ./public (the assets binding). The Worker only runs for
   /api/* and /downloads/*, and everything that has to be remembered lives in Durable Objects:
   TrollHQ has the hit counter, downloads, the forum (forum.js), the poll, a couple of fun global
   tallies, and live WebSockets that push all of it to every open tab. TrollTelemetry (telemetry.js)
   holds anonymous usage stats from the client for the analytics page, and TrollChat (chat.js) is
   the chatroom every running client is in. The client's updater asks /api/update what's new. */
import { DurableObject } from "cloudflare:workers";
import RELEASES from "./releases.json";
import * as Forum from "./forum.js";
import { json, today, visitorHash, clean, body, moderator, compareVersions } from "./util.js";
export { TrollTelemetry } from "./telemetry.js";
export { TrollChat } from "./chat.js";

const POLL = [
	["skinblink", "SkinBlink (skin animation)"],
	["twerk", "Twerk"],
	["nowayhome", "NoWayHome"],
	["parrot", "Parrot"],
	["racket", "Racket"],
	["honk", "Honk"]
];
const BUMPABLE = { toggles: 100, blinks: 400 };
const FORUM_OPS = ["index", "board", "thread", "find", "create", "reply", "edit", "remove", "moderate", "search"];

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
		this.sql.exec("INSERT OR IGNORE INTO counters (name, value) VALUES ('visits', 1337), ('views', 4200), ('downloads', 0), ('updates', 0), ('toggles', 0), ('blinks', 0)");
		// things the site has to keep to itself, made here instead of being configured: the salt for visitor hashes and tripcodes
		this.sql.exec("CREATE TABLE IF NOT EXISTS kept (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
		const random = [...crypto.getRandomValues(new Uint8Array(32))].map((b) => b.toString(16).padStart(2, "0")).join("");
		this.sql.exec("INSERT OR IGNORE INTO kept (name, value) VALUES ('salt', ?)", random);
		// the guestbook table stays as it was; the forum copies its entries in once and takes over from there
		Forum.migrate(this);
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
		const forum = Forum.counts(this);
		return {
			visits: this.counter("visits"),
			views: this.counter("views"),
			downloads: this.counter("downloads"),
			updates: this.counter("updates"),
			toggles: this.counter("toggles"),
			blinks: this.counter("blinks"),
			posts: forum.posts,
			threads: forum.threads,
			online: this.ctx.getWebSockets().length,
			today: t.visits,
			todayViews: t.views,
			days,
			latest: forum.latest
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

	/** For the Worker's visitor hashes and tripcodes (util.js caches it). It never goes anywhere else. */
	salt() {
		return this.sql.exec("SELECT value FROM kept WHERE name = 'salt'").one().value;
	}

	/** The client's updater fetched a jar: counted on its own, never as a download. */
	updated() {
		this.add("updates");
	}

	/** Everything the forum does, by name (see forum.js). */
	forum(op, ...args) {
		if (!FORUM_OPS.includes(op)) return { error: "no such thing", status: 404 };
		return Forum[op](this, ...args);
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

const telemetry = (env) => env.TELEMETRY.get(env.TELEMETRY.idFromName("global"));
const chat = (env) => env.CHAT.get(env.CHAT.idFromName("global"));

// ------------------------------------------------------------ updates, for the client's updater
/** True if a release built for `built` ("26.2") runs on `minecraft` ("26.2" or a patch of it, "26.2.1"). */
const runsOn = (built, minecraft) => minecraft === built || minecraft.startsWith(built + ".");

/**
 * The newest release for the Minecraft version the client is actually running, and only if it's newer
 * than what it has. A client on another Minecraft version is never offered a jar that wasn't built for it.
 */
function updateFor(version, minecraft) {
	const fits = (RELEASES.releases || [])
		.filter((r) => (r.minecraft || []).some((built) => runsOn(built, minecraft)))
		.sort((a, b) => compareVersions(b.version, a.version));
	const best = fits[0];
	if (!best || !version || compareVersions(best.version, version) <= 0) {
		return { update: false, version, minecraft, latest: best ? best.version : null };
	}
	return {
		update: true, version: best.version, minecraft: best.minecraft, file: best.file, size: best.size, sha256: best.sha256,
		date: best.date || "", url: "/api/update/" + best.file
	};
}

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
	if (path === "/api/forum" || path.startsWith("/api/forum/")) return Forum.forumApi(request, env, hq, path, url);

	if (path === "/api/chat") {
		if (request.headers.get("upgrade") !== "websocket") return json({ error: "this one's a websocket" }, 426);
		// the room gets the visitor hash (for rate limits and mutes), never the address
		const to = new URL(request.url);
		to.searchParams.set("who", await visitorHash(request, env));
		return chat(env).fetch(new Request(to, request));
	}
	// who a website key belongs to, so a page can tell a good key from a stale one
	if (path === "/api/mod" && method === "GET") {
		const name = await moderator(request, env);
		return name ? json({ moderator: name }) : json({ error: "nope" }, 403);
	}
	if (path === "/api/chat/history" && method === "GET") {
		return json(await chat(env).room(parseInt(url.searchParams.get("limit") || "30", 10)));
	}
	const said = path.match(/^\/api\/chat\/(\d+)(\/mute)?$/);
	if (said && (method === "DELETE" || (said[2] && method === "POST"))) {
		if (!(await moderator(request, env))) return json({ error: "nope" }, 403);
		if (!said[2]) return json(await chat(env).hide(parseInt(said[1], 10)));
		const data = (await body(request)) || {};
		const { status, ...result } = await chat(env).mute(parseInt(said[1], 10), Number(data.hours));
		return json(result, status || 200);
	}

	if (path === "/api/update" && method === "GET") {
		return json(updateFor(clean(url.searchParams.get("version") || "", 40), clean(url.searchParams.get("minecraft") || "", 40)));
	}
	const jar = path.match(/^\/api\/update\/([A-Za-z0-9._+-]+\.jar)$/);
	if (jar && method === "GET") {
		// only jars in the release list, and without touching the download counter
		if (!(RELEASES.releases || []).some((r) => r.file === jar[1])) return json({ error: "no such release" }, 404);
		const res = await env.ASSETS.fetch(new Request(new URL("/downloads/" + jar[1], url), { method: "GET" }));
		if (res.status === 200) ctx.waitUntil(hq.updated());
		return res;
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
		if (!(await moderator(request, env))) return json({ error: "nope" }, 403);
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
