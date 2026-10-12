/* TROLL CLIENT HOMEPAGE - the chatroom.
   One room for everyone running the client, whatever server they're on: the client keeps a WebSocket
   open to /api/chat and `.chat <message>` talks into it. The website's forum page listens in too.

   Listening needs nothing. Talking needs a name, and the name is the player's Minecraft name, proven
   the way a server proves it: the client tells Mojang it is "joining" a one-off id that mixes our
   challenge with a nonce of its own, and we ask Mojang whether that player did. No token ever comes
   here. Accounts that can't do that (offline, dev) may still talk, but are marked unverified.

   That proof is also all the authority there is. Moderators are the Minecraft accounts listed in
   MODERATORS (a plain var in wrangler.jsonc, names or UUIDs), and they moderate from in game with
   `.mod ...`: the chatroom, the forum, and `.mod web` for a 12-hour key to use on the website.
   Nothing here is a password, and nothing needs to be set as a secret.

   Sockets use the hibernation API, so a room full of idle players costs nothing. */
import { DurableObject } from "cloudflare:workers";
import { clean, digest } from "./util.js";

const HISTORY = 30;
const KEEP = 500;
const MAX_LENGTH = 200;
const GAP_MS = 1500;
const PER_MINUTE = 8;
const NAME = /^[A-Za-z0-9_]{2,16}$/;
const WEB_KEY_MS = 12 * 3600000;
const MOD_HELP = "mod: mute <player> [hours] | unmute <player> | unsay <player> | post <id> | thread <id> lock|unlock|pin|unpin|hide|show | web";
const THREAD_FLAGS = {
	lock: { locked: true }, unlock: { locked: false }, pin: { pinned: true }, unpin: { pinned: false }, hide: { hidden: true }, show: { hidden: false }
};

const randomHex = (bytes) => [...crypto.getRandomValues(new Uint8Array(bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");

export class TrollChat extends DurableObject {
	constructor(ctx, env) {
		super(ctx, env);
		this.sql = ctx.storage.sql;
		this.pending = null;
		ctx.blockConcurrencyWhile(async () => this.migrate());
		ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
	}

	migrate() {
		this.sql.exec(`
			CREATE TABLE IF NOT EXISTS messages (
				id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, verified INTEGER NOT NULL DEFAULT 0,
				text TEXT NOT NULL, at INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, who TEXT NOT NULL DEFAULT ''
			);
			CREATE TABLE IF NOT EXISTS mutes (who TEXT PRIMARY KEY, until INTEGER NOT NULL);
			CREATE TABLE IF NOT EXISTS sessions (hash TEXT PRIMARY KEY, name TEXT NOT NULL, uuid TEXT NOT NULL, until INTEGER NOT NULL);
		`);
	}

	/** True for a proven account that MODERATORS lists, by UUID (dashes or not) or by name. */
	isModerator(uuid, name) {
		const listed = String(this.env.MODERATORS || "").split(",").map((m) => m.trim().toLowerCase().replace(/-/g, "")).filter(Boolean);
		return Boolean(uuid) && (listed.includes(String(uuid).toLowerCase()) || listed.includes(String(name).toLowerCase()));
	}

	/** Players in the room; people reading along on the website don't count. */
	online() {
		return this.ctx.getWebSockets("game").length;
	}

	history(limit = HISTORY) {
		return this.sql.exec("SELECT id, name, verified, text, at FROM messages WHERE hidden = 0 ORDER BY id DESC LIMIT ?", Math.max(1, Math.min(100, limit)))
			.toArray().reverse().map((m) => ({ ...m, verified: Boolean(m.verified) }));
	}

	/** For GET /api/chat/history. */
	room(limit) {
		return { online: this.online(), messages: this.history(limit) };
	}

	broadcast(message) {
		const text = JSON.stringify(message);
		for (const ws of this.ctx.getWebSockets()) {
			try { ws.send(text); } catch (e) { /* closing */ }
		}
	}

	/** The head count goes out at most once a second. */
	pushOnline() {
		if (this.pending) return;
		this.pending = setTimeout(() => {
			this.pending = null;
			this.broadcast({ type: "online", online: this.online() });
		}, 1000);
	}

	async fetch(request) {
		if (request.headers.get("upgrade") !== "websocket") return new Response("expected a websocket", { status: 426 });
		const url = new URL(request.url);
		const role = url.searchParams.get("role") === "game" ? "game" : "web";
		const [client, server] = Object.values(new WebSocketPair());
		this.ctx.acceptWebSocket(server, [role]);
		const challenge = randomHex(16);
		// who: the same daily-salted hash the rest of the site uses, worked out by the Worker; never an IP
		server.serializeAttachment({ who: url.searchParams.get("who") || "", role, challenge, name: "", uuid: "", verified: false, last: 0, recent: [], lastText: "" });
		server.send(JSON.stringify({ type: "welcome", challenge, online: this.online(), history: this.history(), max: MAX_LENGTH }));
		this.pushOnline();
		return new Response(null, { status: 101, webSocket: client });
	}

	async webSocketMessage(ws, raw) {
		if (typeof raw !== "string" || raw.length > 2000) return;
		let msg;
		try { msg = JSON.parse(raw); } catch (e) { return; }
		const state = ws.deserializeAttachment() || {};
		const fail = (error) => { try { ws.send(JSON.stringify({ type: "error", error })); } catch (e) { /* gone */ } };

		if (msg.type === "auth") {
			const name = String(msg.name || "");
			if (!NAME.test(name)) return fail("that's not a minecraft name");
			let verified = false, shown = name, uuid = "";
			if (typeof msg.nonce === "string" && /^[0-9a-f]{16,64}$/.test(msg.nonce)) {
				// the id is a hash over our challenge and their nonce, so it can't be one a real server is waiting on
				const serverId = (await digest("SHA-1", state.challenge + ":" + msg.nonce + ":trollclient-chat"));
				try {
					const res = await fetch("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" + encodeURIComponent(name) + "&serverId=" + serverId);
					if (res.status === 200) {
						const profile = await res.json();
						if (profile && NAME.test(profile.name || "") && /^[0-9a-f]{32}$/.test(profile.id || "")) { verified = true; shown = profile.name; uuid = profile.id; }
					}
				} catch (e) { /* mojang is down: unverified it is */ }
			}
			ws.serializeAttachment({ ...state, name: shown, uuid, verified });
			try { ws.send(JSON.stringify({ type: "ready", name: shown, verified })); } catch (e) { /* gone */ }
			return;
		}

		if (msg.type === "mod") {
			if (!state.name) return fail("say who you are first");
			const args = (Array.isArray(msg.args) ? msg.args : []).slice(0, 4).map((a) => clean(String(a), 40));
			let answer;
			try {
				answer = await this.moderate(state, args);
			} catch (e) {
				console.error(e);
				answer = { text: "that didn't work" };
			}
			try { ws.send(JSON.stringify({ type: "mod", ...answer })); } catch (e) { /* gone */ }
			return;
		}

		if (msg.type === "say") {
			if (!state.name) return fail("say who you are first");
			const text = clean(msg.text, MAX_LENGTH);
			if (!text) return;
			const now = Date.now();
			const mute = this.sql.exec("SELECT until FROM mutes WHERE who = ?", state.who).toArray()[0];
			if (mute && mute.until > now) return fail("you've been muted in the chatroom for a while");
			const recent = (state.recent || []).filter((t) => now - t < 60000);
			if (now - (state.last || 0) < GAP_MS || recent.length >= PER_MINUTE) return fail("slow down");
			if (text === state.lastText) return fail("you just said that");
			recent.push(now);
			ws.serializeAttachment({ ...state, last: now, recent, lastText: text });
			const id = this.sql.exec("INSERT INTO messages (name, verified, text, at, who) VALUES (?, ?, ?, ?, ?) RETURNING id",
				state.name, state.verified ? 1 : 0, text, now, state.who).one().id;
			this.sql.exec("DELETE FROM messages WHERE id <= ?", id - KEEP);
			this.broadcast({ type: "msg", message: { id, name: state.name, verified: Boolean(state.verified), text, at: now } });
		}
	}

	// ------------------------------------------------------------ moderating, from in game

	/** The newest thing `player` said ("~name" for someone unverified), for finding who to act on. */
	lastFrom(player) {
		const unverified = player.startsWith("~");
		return this.sql.exec("SELECT name, who FROM messages WHERE name = ? COLLATE NOCASE AND verified = ? ORDER BY id DESC LIMIT 1",
			unverified ? player.slice(1) : player, unverified ? 0 : 1).toArray()[0] || null;
	}

	async moderate(state, args) {
		if (!state.verified) return { text: "moderating needs an account Mojang vouches for" };
		if (!this.isModerator(state.uuid, state.name)) {
			return { text: "you're not a moderator here. if you should be, the site owner adds this id to MODERATORS: " + state.uuid };
		}
		const [command, target, option] = args;
		const now = Date.now();
		// every action leaves a line in the Worker's logs: who, and what
		console.log("mod", state.name, args.join(" "));
		const hq = this.env.HQ.get(this.env.HQ.idFromName("global"));

		if (command === "mute" || command === "unmute" || command === "unsay") {
			const found = target ? this.lastFrom(target) : null;
			if (!found || !found.who) return { text: "nobody called " + (target || "that") + " has said anything here (unverified names start with ~)" };
			if (command === "mute") {
				const hours = Math.max(1, Math.min(24, parseInt(option, 10) || 24));
				this.sql.exec("INSERT INTO mutes (who, until) VALUES (?, ?) ON CONFLICT(who) DO UPDATE SET until = excluded.until", found.who, now + hours * 3600000);
				this.sql.exec("DELETE FROM mutes WHERE until < ?", now);
				return { text: "muted " + found.name + " for " + hours + " h" };
			}
			if (command === "unmute") {
				this.sql.exec("DELETE FROM mutes WHERE who = ?", found.who);
				return { text: "unmuted " + found.name };
			}
			const ids = this.sql.exec("SELECT id FROM messages WHERE who = ? AND hidden = 0 AND at > ?", found.who, now - 3600000).toArray().map((r) => r.id);
			for (const id of ids) this.hide(id);
			return { text: "took down " + ids.length + " message" + (ids.length === 1 ? "" : "s") + " from " + found.name };
		}
		if (command === "post") {
			const id = parseInt(target, 10);
			if (!(id > 0)) return { text: "which post? the number after the # on the forum" };
			const result = await hq.forum("remove", id, "", true);
			return { text: result.error || "removed post #" + id };
		}
		if (command === "thread") {
			const id = parseInt(target, 10), flags = THREAD_FLAGS[option];
			if (!(id > 0) || !flags) return { text: "thread <id> lock|unlock|pin|unpin|hide|show (the id is in the thread's address)" };
			const result = await hq.forum("moderate", id, flags);
			return { text: result.error || "thread " + id + ": " + option + " done" };
		}
		if (command === "web") {
			const key = randomHex(24);
			this.sql.exec("DELETE FROM sessions WHERE until < ?", now);
			this.sql.exec("INSERT INTO sessions (hash, name, uuid, until) VALUES (?, ?, ?, ?)", await digest("SHA-256", key), state.name, state.uuid, now + WEB_KEY_MS);
			return { text: "a key for the website, good for 12 hours. paste it under \"moderator\" on the forum or the analytics page.", key };
		}
		return { text: MOD_HELP };
	}

	/** RPC: whose website key hashes to this, if it's still good and they're still a moderator. */
	session(hash) {
		const row = this.sql.exec("SELECT name, uuid FROM sessions WHERE hash = ? AND until > ?", hash, Date.now()).toArray()[0];
		return row && this.isModerator(row.uuid, row.name) ? row.name : "";
	}

	webSocketClose(ws, code) {
		const ok = code >= 1000 && code < 5000 && ![1005, 1006, 1015].includes(code);
		try { ws.close(ok ? code : 1000, "bye"); } catch (e) { /* already gone */ }
		this.pushOnline();
	}

	webSocketError() {
		this.pushOnline();
	}

	// ------------------------------------------------------------ RPC, for moderators using a website key (checked in the Worker)
	hide(id) {
		this.sql.exec("UPDATE messages SET hidden = 1 WHERE id = ?", id);
		this.broadcast({ type: "unsay", id });
		return { ok: true };
	}

	/** Mutes whoever wrote message `id`. The hash behind it changes daily, so a mute can't outlast the day. */
	mute(id, hours) {
		const row = this.sql.exec("SELECT who FROM messages WHERE id = ?", id).toArray()[0];
		if (!row || !row.who) return { error: "no such message", status: 404 };
		const until = Date.now() + Math.max(1, Math.min(24, hours || 24)) * 3600000;
		this.sql.exec("INSERT INTO mutes (who, until) VALUES (?, ?) ON CONFLICT(who) DO UPDATE SET until = excluded.until", row.who, until);
		this.sql.exec("DELETE FROM mutes WHERE until < ?", Date.now());
		return { ok: true, until };
	}
}
