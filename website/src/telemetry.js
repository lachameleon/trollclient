/* TROLL CLIENT HOMEPAGE - client telemetry.
   The client posts running totals for its session (on start, every five minutes, on quit). This
   object keeps the last totals it saw for each session and adds only the difference to the daily
   tables, so lost, late or repeated reports never count twice. Everything the analytics page shows
   is built from those tables.

   Identity is a random install id the client made up. Location is the country, continent and
   region Cloudflare already worked out; the IP itself is never stored. */
import { DurableObject } from "cloudflare:workers";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const DAY = 86400000;
const ONLINE_MS = 6.5 * 60000; // a little over one heartbeat
const MAX_KEYS = 200;
const MAX_DELTA_MS = DAY;

// [section in the report, kind in the usage table, fields]
const TIMED = [["screens", "screen", ["n", "ms"]], ["servers", "server", ["n", "ms"]]];
const COUNTED = [["settings", "setting"], ["commands", "command"], ["counts", "count"], ["errors", "error"]];
const MODULE_FIELDS = ["on", "off", "ms", "key", "cmd", "err"];
const TIME_FIELDS = ["total", "focused", "ingame", "singleplayer", "multiplayer", "menus", "paused"];

/** Everything about the setup the page can break down by: [id, label, SQL over sessions]. */
const DIMENSIONS = [
	["client", "client version", "json_extract(env, '$.client')"],
	["minecraft", "minecraft version", "json_extract(env, '$.minecraft')"],
	["loader", "fabric loader", "json_extract(env, '$.loader')"],
	["fabricApi", "fabric api", "json_extract(env, '$.fabricApi')"],
	["os", "operating system", "json_extract(env, '$.os')"],
	["osVersion", "os version", "json_extract(env, '$.os') || ' ' || json_extract(env, '$.osVersion')"],
	["arch", "cpu architecture", "json_extract(env, '$.arch')"],
	["cpus", "cpu threads", "json_extract(env, '$.cpus')"],
	["ram", "system ram", "CAST(ROUND(json_extract(env, '$.ramMb') / 1024.0) AS INT) || ' GB'"],
	["heap", "memory given to minecraft", "CAST(ROUND(json_extract(env, '$.heapMb') / 1024.0) AS INT) || ' GB'"],
	["java", "java", "json_extract(env, '$.java')"],
	["javaVendor", "java vendor", "json_extract(env, '$.javaVendor')"],
	["gpuVendor", "gpu vendor", "json_extract(env, '$.gpuVendor')"],
	["gpu", "gpu", "json_extract(env, '$.gpu')"],
	["gpuBackend", "graphics backend", "json_extract(env, '$.gpuBackend')"],
	["gpuType", "gpu type", "json_extract(env, '$.gpuType')"],
	["window", "window size", "json_extract(env, '$.window')"],
	["fullscreen", "fullscreen", "CASE json_extract(env, '$.fullscreen') WHEN 1 THEN 'fullscreen' ELSE 'windowed' END"],
	["refreshRate", "refresh rate", "json_extract(env, '$.refreshRate') || ' Hz'"],
	["guiScale", "gui scale", "json_extract(env, '$.guiScale')"],
	["renderDistance", "render distance", "json_extract(env, '$.renderDistance') || ' chunks'"],
	["simulationDistance", "simulation distance", "json_extract(env, '$.simulationDistance') || ' chunks'"],
	["graphics", "graphics preset", "json_extract(env, '$.graphics')"],
	["fpsLimit", "fps limit", "json_extract(env, '$.fpsLimit')"],
	["vsync", "vsync", "CASE json_extract(env, '$.vsync') WHEN 1 THEN 'on' ELSE 'off' END"],
	["fov", "fov", "json_extract(env, '$.fov')"],
	["language", "game language", "json_extract(env, '$.language')"],
	["locale", "system locale", "json_extract(env, '$.locale')"],
	["timezone", "timezone", "json_extract(env, '$.timezone')"],
	["country", "country", "country"],
	["continent", "continent", "continent"],
	["region", "region", "CASE WHEN region = '' THEN country ELSE region || ', ' || country END"],
	["theme", "client theme", "json_extract(env, '$.theme')"],
	["font", "client font", "json_extract(env, '$.font')"],
	["prefix", "command prefix", "json_extract(env, '$.prefix')"],
	["macros", "macros saved", "json_extract(env, '$.macros')"],
	["friends", "friends added", "json_extract(env, '$.friends')"],
	["boundModules", "modules with a keybind", "json_extract(env, '$.boundModules')"],
	["modifiedSettings", "settings changed from default", "CASE WHEN json_extract(env, '$.modifiedSettings') >= 50 THEN '50+' WHEN json_extract(env, '$.modifiedSettings') >= 20 THEN '20-49' WHEN json_extract(env, '$.modifiedSettings') >= 5 THEN '5-19' ELSE json_extract(env, '$.modifiedSettings') END"],
	["modCount", "other mods installed", "CASE WHEN json_array_length(env, '$.mods') >= 20 THEN '20+' WHEN json_array_length(env, '$.mods') >= 10 THEN '10-19' ELSE json_array_length(env, '$.mods') END"]
];

const today = (t = Date.now()) => new Date(t).toISOString().slice(0, 10);

function cleanKey(k) {
	return String(k).replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 80);
}

function num(v) {
	const n = Number(v);
	return Number.isFinite(n) && n > 0 ? Math.min(Math.floor(n), 1e12) : 0;
}

/** A report's counters as one flat map: "kind\u0001key\u0001field" -> running total. */
function flatten(r) {
	const out = {};
	const put = (kind, key, field, v) => {
		const n = num(v);
		if (n) out[kind + "\u0001" + key + "\u0001" + field] = n;
	};
	const each = (obj, fn) => {
		if (!obj || typeof obj !== "object" || Array.isArray(obj)) return;
		Object.keys(obj).slice(0, MAX_KEYS).forEach((k) => {
			const key = cleanKey(k);
			if (key) fn(key, obj[k]);
		});
	};
	const time = r.time || {};
	for (const f of TIME_FIELDS) put("time", f, "ms", time[f]);
	put("fps", "all", "sum", (r.fps || {}).sum);
	put("fps", "all", "n", (r.fps || {}).n);
	each(r.modules, (k, v) => { for (const f of MODULE_FIELDS) put("module", k, f, v && v[f]); });
	for (const [section, kind] of COUNTED) each(r[section], (k, v) => put(kind, k, "n", v));
	for (const [section, kind, fields] of TIMED) each(r[section], (k, v) => { for (const f of fields) put(kind, k, f, v && v[f]); });
	return out;
}

/** Env values the client sent, trimmed to sane sizes. Arrays of strings stay arrays. */
function cleanEnv(env) {
	const out = {};
	if (!env || typeof env !== "object") return out;
	for (const k of Object.keys(env).slice(0, 80)) {
		const v = env[k];
		const key = cleanKey(k).slice(0, 40);
		if (typeof v === "string") out[key] = cleanKey(v).slice(0, 120);
		else if (typeof v === "number" && Number.isFinite(v)) out[key] = v;
		else if (typeof v === "boolean") out[key] = v;
		else if (Array.isArray(v)) out[key] = v.filter((x) => typeof x === "string").slice(0, 100).map((x) => cleanKey(x).slice(0, 64));
		else if (v && typeof v === "object") out[key] = cleanEnv(v);
	}
	return out;
}

/** The hour and weekday where the player is, from the timezone their machine reports. */
function localTime(t, tz) {
	try {
		const parts = new Intl.DateTimeFormat("en-US", { timeZone: tz || "UTC", hour: "numeric", hourCycle: "h23", weekday: "short" }).formatToParts(new Date(t));
		const hour = parseInt(parts.find((p) => p.type === "hour").value, 10) % 24;
		const wd = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].indexOf(parts.find((p) => p.type === "weekday").value);
		return { hour, wd };
	} catch (e) {
		const d = new Date(t);
		return { hour: d.getUTCHours(), wd: d.getUTCDay() };
	}
}

function bucket(value, edges, labels) {
	for (let i = 0; i < edges.length; i++) if (value < edges[i]) return labels[i];
	return labels[labels.length - 1];
}

export class TrollTelemetry extends DurableObject {
	constructor(ctx, env) {
		super(ctx, env);
		this.sql = ctx.storage.sql;
		this.recent = new Map();
		this.cache = new Map();
		ctx.blockConcurrencyWhile(async () => this.migrate());
	}

	migrate() {
		this.sql.exec(`
			CREATE TABLE IF NOT EXISTS installs (
				id TEXT PRIMARY KEY, first_day TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
				sessions INTEGER NOT NULL DEFAULT 0, ms INTEGER NOT NULL DEFAULT 0
			);
			CREATE TABLE IF NOT EXISTS sessions (
				id TEXT PRIMARY KEY, install TEXT NOT NULL, day TEXT NOT NULL, hour INTEGER NOT NULL, wd INTEGER NOT NULL,
				started INTEGER NOT NULL, last_seen INTEGER NOT NULL, ended INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL,
				ms INTEGER NOT NULL DEFAULT 0, ingame_ms INTEGER NOT NULL DEFAULT 0, fps_avg INTEGER, fps_min INTEGER, fps_max INTEGER,
				modules_used INTEGER NOT NULL DEFAULT 0, reports INTEGER NOT NULL DEFAULT 0,
				country TEXT NOT NULL DEFAULT '', continent TEXT NOT NULL DEFAULT '', region TEXT NOT NULL DEFAULT '',
				env TEXT NOT NULL DEFAULT '{}', state TEXT NOT NULL DEFAULT '{}'
			);
			CREATE INDEX IF NOT EXISTS sessions_day ON sessions (day);
			CREATE INDEX IF NOT EXISTS sessions_live ON sessions (ended, last_seen);
			CREATE TABLE IF NOT EXISTS usage (
				day TEXT NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL, field TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0,
				PRIMARY KEY (day, kind, key, field)
			) WITHOUT ROWID;
			CREATE TABLE IF NOT EXISTS users (
				day TEXT NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL, install TEXT NOT NULL,
				PRIMARY KEY (day, kind, key, install)
			) WITHOUT ROWID;
			CREATE TABLE IF NOT EXISTS active (day TEXT NOT NULL, install TEXT NOT NULL, PRIMARY KEY (day, install)) WITHOUT ROWID;
		`);
	}

	allow(key, ms) {
		const now = Date.now();
		const last = this.recent.get(key);
		if (last && now - last < ms) return false;
		this.recent.set(key, now);
		if (this.recent.size > 20000) {
			for (const [k, t] of this.recent) if (now - t > 600000) this.recent.delete(k);
		}
		return true;
	}

	usage(day, kind, key, field, n) {
		if (n > 0) {
			this.sql.exec("INSERT INTO usage (day, kind, key, field, n) VALUES (?, ?, ?, ?, ?) ON CONFLICT(day, kind, key, field) DO UPDATE SET n = n + excluded.n",
				day, kind, key, field, n);
		}
	}

	user(day, kind, key, install) {
		this.sql.exec("INSERT OR IGNORE INTO users (day, kind, key, install) VALUES (?, ?, ?, ?)", day, kind, key, install);
	}

	// ------------------------------------------------------------ RPC: a report from a client
	ingest(report, geo, who) {
		if (!report || report.v !== 1) return { error: "unknown report version", status: 400 };
		const install = String(report.install || "").toLowerCase();
		const id = String(report.session || "").toLowerCase();
		const seq = Math.floor(Number(report.seq));
		const kind = ["start", "beat", "end"].includes(report.kind) ? report.kind : "beat";
		if (!UUID.test(install) || !UUID.test(id) || !(seq > 0)) return { error: "bad ids", status: 400 };
		// a real client reports every five minutes; anything much faster is someone poking at it
		if (kind !== "end" && !this.allow("s:" + id, 10000)) return { error: "slow down", status: 429 };
		if (!this.allow("ip:" + who, 1000)) return { error: "slow down", status: 429 };

		const now = Date.now();
		const day = today(now);
		const env = cleanEnv(report.env);
		const row = this.sql.exec("SELECT install, seq, state, last_seen FROM sessions WHERE id = ?", id).toArray()[0];
		if (row && row.install !== install) return { error: "that session belongs to someone else", status: 409 };
		if (row && seq <= row.seq) return { ok: true, stale: true };

		const next = flatten(report);
		const prev = row ? JSON.parse(row.state) : {};
		const sinceLast = row ? now - row.last_seen + 120000 : MAX_DELTA_MS;
		const usedModules = new Set();
		for (const k in next) {
			let delta = next[k] - (prev[k] || 0);
			if (delta <= 0) continue;
			const [dkind, key, field] = k.split("\u0001");
			// time can't pass faster than the wall clock between two reports
			if (field === "ms") delta = Math.min(delta, sinceLast, MAX_DELTA_MS);
			this.usage(day, dkind, key, field, delta);
			if (dkind === "module" && (field === "on" || field === "ms")) this.user(day, "module", key, install);
			if (dkind === "server" && field === "n") this.user(day, "server", key, install);
			if (dkind === "command") this.user(day, "command", key, install);
		}
		for (const k in next) {
			const [dkind, key] = k.split("\u0001");
			if (dkind === "module") usedModules.add(key);
		}

		const totalMs = next["time\u0001total\u0001ms"] || 0;
		const ingameMs = next["time\u0001ingame\u0001ms"] || 0;
		const prevTotal = prev["time\u0001total\u0001ms"] || 0;
		const fpsN = next["fps\u0001all\u0001n"] || 0;
		const fpsAvg = fpsN ? Math.round((next["fps\u0001all\u0001sum"] || 0) / fpsN) : null;
		const fps = report.fps || {};

		this.sql.exec("INSERT OR IGNORE INTO active (day, install) VALUES (?, ?)", day, install);
		const known = this.sql.exec("SELECT 1 FROM installs WHERE id = ?", install).toArray().length > 0;
		if (!known) {
			this.sql.exec("INSERT INTO installs (id, first_day, first_seen, last_seen, sessions, ms) VALUES (?, ?, ?, ?, 0, 0)", install, day, now, now);
		}
		this.sql.exec("UPDATE installs SET last_seen = ?, ms = ms + ?, sessions = sessions + ? WHERE id = ?",
			now, Math.max(0, Math.min(totalMs - prevTotal, sinceLast)), row ? 0 : 1, install);

		const envJson = JSON.stringify(env);
		const stateJson = JSON.stringify(next);
		if (row) {
			this.sql.exec(`UPDATE sessions SET last_seen = ?, seq = ?, ended = ?, ms = ?, ingame_ms = ?, fps_avg = ?, fps_min = ?, fps_max = ?,
				modules_used = ?, reports = reports + 1, env = ?, state = ? WHERE id = ?`,
				now, seq, kind === "end" ? 1 : 0, totalMs, ingameMs, fpsAvg, num(fps.min) || null, num(fps.max) || null,
				usedModules.size, envJson, stateJson, id);
		} else {
			const { hour, wd } = localTime(now, env.timezone);
			this.sql.exec(`INSERT INTO sessions (id, install, day, hour, wd, started, last_seen, ended, seq, ms, ingame_ms, fps_avg, fps_min, fps_max,
				modules_used, reports, country, continent, region, env, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)`,
				id, install, day, hour, wd, now - Math.min(totalMs, DAY), now, kind === "end" ? 1 : 0, seq, totalMs, ingameMs, fpsAvg,
				num(fps.min) || null, num(fps.max) || null, usedModules.size,
				geo.country, geo.continent, geo.region, envJson, stateJson);
			// once per session: what was switched on at launch, what else is installed, crashes since last time
			for (const name of (env.enabled || [])) this.usage(day, "startup", name, "n", 1);
			for (const mod of (env.mods || [])) {
				this.usage(day, "mod", mod, "n", 1);
				this.user(day, "mod", mod, install);
			}
			const crashes = env.crashes || {};
			this.usage(day, "count", "crashes", "n", num(crashes.total));
			this.usage(day, "count", "crashes_with_troll_client", "n", num(crashes.ours));
			this.usage(day, "count", "sessions", "n", 1);
			if (!known) this.usage(day, "count", "new_installs", "n", 1);
		}
		this.cache.clear();
		return { ok: true };
	}

	// ------------------------------------------------------------ RPC: the analytics page
	analytics(days) {
		days = Math.max(1, Math.min(3650, Math.floor(days) || 30));
		const hit = this.cache.get(days);
		if (hit && Date.now() - hit.at < 30000) return hit.data;
		const data = this.build(days);
		this.cache.set(days, { at: Date.now(), data });
		return data;
	}

	build(days) {
		const now = Date.now();
		const from = today(now - (days - 1) * DAY);
		const q = (sql, ...args) => this.sql.exec(sql, ...args).toArray();
		const one = (sql, ...args) => q(sql, ...args)[0] || {};

		// usage rows for the window, grouped by kind/key/field
		const usage = {};
		for (const r of q("SELECT kind, key, field, SUM(n) AS n FROM usage WHERE day >= ? GROUP BY kind, key, field", from)) {
			((usage[r.kind] = usage[r.kind] || {})[r.key] = usage[r.kind][r.key] || {})[r.field] = r.n;
		}
		const users = {};
		for (const r of q("SELECT kind, key, COUNT(DISTINCT install) AS n FROM users WHERE day >= ? GROUP BY kind, key", from)) {
			(users[r.kind] = users[r.kind] || {})[r.key] = r.n;
		}
		const get = (kind, key, field) => ((usage[kind] || {})[key] || {})[field] || 0;
		const list = (kind, field, limit) => Object.entries(usage[kind] || {})
			.map(([k, v]) => ({ key: k, n: v[field] || 0, ms: v.ms || 0, users: (users[kind] || {})[k] || 0 }))
			.filter((r) => r.n > 0 || r.ms > 0)
			.sort((a, b) => b.n - a.n || b.ms - a.ms)
			.slice(0, limit);

		const s = one(`SELECT COUNT(*) AS sessions, COUNT(DISTINCT install) AS installs, SUM(ms) AS ms, SUM(ingame_ms) AS ingame,
			AVG(fps_avg) AS fps, AVG(modules_used) AS modules FROM sessions WHERE day >= ?`, from);
		const lengths = q("SELECT ms FROM sessions WHERE day >= ? ORDER BY ms", from).map((r) => r.ms);
		const median = lengths.length ? lengths[Math.floor(lengths.length / 2)] : 0;
		const activeIn = (n) => one("SELECT COUNT(DISTINCT install) AS n FROM active WHERE day >= ?", today(now - (n - 1) * DAY)).n || 0;
		const totals = {
			installsAllTime: one("SELECT COUNT(*) AS n FROM installs").n || 0,
			sessionsAllTime: one("SELECT COUNT(*) AS n FROM sessions").n || 0,
			hoursAllTime: Math.round((one("SELECT SUM(ms) AS n FROM installs").n || 0) / 3600000),
			installs: s.installs || 0,
			newInstalls: one("SELECT COUNT(*) AS n FROM installs WHERE first_day >= ?", from).n || 0,
			sessions: s.sessions || 0,
			playMs: get("time", "total", "ms"),
			ingameMs: get("time", "ingame", "ms"),
			singleplayerMs: get("time", "singleplayer", "ms"),
			multiplayerMs: get("time", "multiplayer", "ms"),
			menusMs: get("time", "menus", "ms"),
			pausedMs: get("time", "paused", "ms"),
			focusedMs: get("time", "focused", "ms"),
			avgSessionMs: s.sessions ? Math.round((s.ms || 0) / s.sessions) : 0,
			medianSessionMs: median,
			avgFps: get("fps", "all", "n") ? Math.round(get("fps", "all", "sum") / get("fps", "all", "n")) : 0,
			avgModulesUsed: Math.round((s.modules || 0) * 10) / 10,
			online: one("SELECT COUNT(*) AS n FROM sessions WHERE ended = 0 AND last_seen > ?", now - ONLINE_MS).n || 0,
			dau: activeIn(1),
			wau: activeIn(7),
			mau: activeIn(30),
			toggles: Object.values(usage.module || {}).reduce((a, m) => a + (m.on || 0) + (m.off || 0), 0),
			moduleErrors: Object.values(usage.module || {}).reduce((a, m) => a + (m.err || 0), 0),
			crashes: get("count", "crashes", "n"),
			crashesOurs: get("count", "crashes_with_troll_client", "n")
		};

		// one entry per day in the window, zeros included, so the line has no gaps
		const byDay = {};
		for (let i = days - 1; i >= 0 && i < 400; i--) byDay[today(now - i * DAY)] = { day: today(now - i * DAY), sessions: 0, active: 0, newInstalls: 0, ms: 0, ingame: 0 };
		const into = (rows, field) => rows.forEach((r) => { if (byDay[r.day]) byDay[r.day][field] = r.n || 0; });
		into(q("SELECT day, COUNT(*) AS n FROM sessions WHERE day >= ? GROUP BY day", from), "sessions");
		into(q("SELECT day, COUNT(*) AS n FROM active WHERE day >= ? GROUP BY day", from), "active");
		into(q("SELECT first_day AS day, COUNT(*) AS n FROM installs WHERE first_day >= ? GROUP BY first_day", from), "newInstalls");
		into(q("SELECT day, n FROM usage WHERE day >= ? AND kind = 'time' AND key = 'total' AND field = 'ms'", from), "ms");
		into(q("SELECT day, n FROM usage WHERE day >= ? AND kind = 'time' AND key = 'ingame' AND field = 'ms'", from), "ingame");

		const modules = Object.entries(usage.module || {}).map(([name, m]) => ({
			name, on: m.on || 0, off: m.off || 0, ms: m.ms || 0, key: m.key || 0, cmd: m.cmd || 0, err: m.err || 0,
			users: (users.module || {})[name] || 0, startup: get("startup", name, "n")
		}));
		for (const [name, v] of Object.entries(usage.startup || {})) {
			if (!modules.some((m) => m.name === name)) modules.push({ name, on: 0, off: 0, ms: 0, key: 0, cmd: 0, err: 0, users: 0, startup: v.n || 0 });
		}
		modules.sort((a, b) => b.users - a.users || b.ms - a.ms || b.startup - a.startup);

		const breakdowns = DIMENSIONS.map(([id, label, expr]) => ({
			id, label,
			rows: q(`SELECT ${expr} AS k, COUNT(DISTINCT install) AS n FROM sessions WHERE day >= ? GROUP BY k ORDER BY n DESC LIMIT 12`, from)
				.map((r) => ({ key: r.k == null || r.k === "" ? "(unknown)" : String(r.k), n: r.n }))
		}));

		const hours = new Array(24).fill(0);
		const heat = Array.from({ length: 7 }, () => new Array(24).fill(0));
		for (const r of q("SELECT wd, hour, COUNT(*) AS n FROM sessions WHERE day >= ? GROUP BY wd, hour", from)) {
			if (r.wd >= 0 && r.wd < 7 && r.hour >= 0 && r.hour < 24) { heat[r.wd][r.hour] = r.n; hours[r.hour] += r.n; }
		}
		const dist = (values, edges, labels) => {
			const counts = labels.map((l) => ({ key: l, n: 0 }));
			values.forEach((v) => { counts[labels.indexOf(bucket(v, edges, labels))].n++; });
			return counts;
		};
		const fpsValues = q("SELECT fps_avg AS v FROM sessions WHERE day >= ? AND fps_avg IS NOT NULL", from).map((r) => r.v);
		const usedValues = q("SELECT modules_used AS v FROM sessions WHERE day >= ?", from).map((r) => r.v);
		const launchValues = q(`SELECT MAX(json_extract(env, '$.launches')) AS v FROM sessions WHERE day >= ? GROUP BY install`, from).map((r) => r.v || 0);

		return {
			generated: now, days, from,
			totals,
			series: Object.values(byDay),
			modules,
			settings: list("setting", "n", 30),
			commands: list("command", "n", 30),
			events: list("count", "n", 40).filter((r) => r.key !== "sessions" && r.key !== "new_installs"),
			errors: list("error", "n", 20),
			screens: list("screen", "n", 25),
			servers: list("server", "n", 200).sort((a, b) => b.ms - a.ms).slice(0, 25),
			mods: list("mod", "n", 30),
			time: TIME_FIELDS.map((f) => ({ key: f, ms: get("time", f, "ms") })),
			breakdowns,
			hours, heat,
			distributions: {
				sessionLength: dist(lengths, [60000, 300000, 900000, 1800000, 3600000, 7200000, 14400000], ["<1 min", "1-5 min", "5-15 min", "15-30 min", "30-60 min", "1-2 h", "2-4 h", "4 h+"]),
				fps: dist(fpsValues, [30, 60, 90, 120, 165, 240, 500], ["<30", "30-59", "60-89", "90-119", "120-164", "165-239", "240-499", "500+"]),
				modulesUsed: dist(usedValues, [1, 2, 4, 7, 11, 16], ["0", "1", "2-3", "4-6", "7-10", "11-15", "16+"]),
				launches: dist(launchValues, [2, 3, 6, 11, 26, 51, 101], ["1", "2", "3-5", "6-10", "11-25", "26-50", "51-100", "100+"])
			}
		};
	}

	/** The newest sessions, everything included. Only for the site owner (ADMIN_TOKEN). */
	latestSessions(limit) {
		return this.sql.exec(`SELECT id, install, day, started, last_seen, ended, ms, ingame_ms, fps_avg, fps_min, fps_max, modules_used, reports,
			country, continent, region, env, state FROM sessions ORDER BY last_seen DESC LIMIT ?`, Math.max(1, Math.min(200, limit || 50)))
			.toArray().map((r) => {
				const state = JSON.parse(r.state);
				const modules = {};
				for (const k in state) {
					const [kind, key, field] = k.split("\u0001");
					if (kind === "module") (modules[key] = modules[key] || {})[field] = state[k];
				}
				return { ...r, env: JSON.parse(r.env), state: undefined, modules };
			});
	}
}
