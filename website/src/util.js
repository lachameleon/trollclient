/* TROLL CLIENT HOMEPAGE - the bits every part of the Worker shares. */

export const AVATARS = ["blink", "troll", "mime", "referee", "ghost", "hacker"];

// x-robots-tag: pages fetch these to draw themselves, so crawlers may read them, but raw JSON is never a search result
export const json = (data, status = 200, headers = {}) =>
	new Response(JSON.stringify(data), {
		status,
		headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-robots-tag": "noindex", ...headers }
	});

export const today = () => new Date().toISOString().slice(0, 10);

const hex = (buffer) => [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, "0")).join("");

export async function digest(algorithm, text) {
	return hex(await crypto.subtle.digest(algorithm, new TextEncoder().encode(text)));
}

let cachedSalt = null;

/**
 * The site's salt. TrollHQ makes it up the first time it runs and keeps it in its own storage, so
 * there is nothing to configure and nothing in any config file that could give it away.
 */
export async function salt(env) {
	if (!cachedSalt) cachedSalt = await env.HQ.get(env.HQ.idFromName("global")).salt();
	return cachedSalt;
}

/** A visitor fingerprint that can't be turned back into an IP: salted, and the date in it changes it daily. */
export async function visitorHash(request, env) {
	const ip = request.headers.get("cf-connecting-ip") || "local";
	return (await digest("SHA-256", (await salt(env)) + "|" + today() + "|" + ip)).slice(0, 24);
}

/** Strips control characters (keeps newlines where asked) and trims to a length. */
export function clean(value, max, multiline = false) {
	if (typeof value !== "string") return "";
	const pattern = multiline ? /[\u0000-\u0009\u000b-\u001f\u007f‪-‮⁦-⁩]/g : /[\u0000-\u001f\u007f‪-‮⁦-⁩]/g;
	let out = value.replace(pattern, "").trim();
	if (multiline) out = out.replace(/\n{3,}/g, "\n\n");
	return [...out].slice(0, max).join("");
}

export async function body(request, max = 4096) {
	if (!(request.headers.get("content-type") || "").includes("application/json")) return null;
	const text = await request.text();
	if (text.length > max) return null;
	try { return JSON.parse(text); } catch (e) { return null; }
}

/**
 * The moderator making this request, by name, or "". There is no admin password: moderators are
 * Minecraft accounts listed in MODERATORS (wrangler.jsonc), proven in the chatroom the way a server
 * login is. One of them gets a key for the website by typing `.mod web` in game; it lasts 12 hours.
 */
export async function moderator(request, env) {
	const key = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "").trim();
	if (!/^[0-9a-f]{48}$/.test(key)) return "";
	return env.CHAT.get(env.CHAT.idFromName("global")).session(await digest("SHA-256", key));
}

/** A homepage link people typed, as a real http(s) URL or nothing. */
export function cleanSite(value) {
	let site = clean(value, 120);
	if (!site) return "";
	if (!/^https?:\/\//i.test(site)) site = "http://" + site;
	try {
		const u = new URL(site);
		return u.protocol === "http:" || u.protocol === "https:" ? u.href : "";
	} catch (e) { return ""; }
}

/**
 * Compares dotted versions ("1.10.0" > "1.9.3"). A pre-release ("1.2.0-beta") sorts before its release.
 * The client checks again with Fabric's own parser before installing anything.
 */
export function compareVersions(a, b) {
	const split = (v) => {
		const [core, ...pre] = String(v).split("+")[0].split("-");
		return { nums: core.split(".").map((n) => parseInt(n, 10) || 0), pre: pre.join("-") };
	};
	const x = split(a), y = split(b);
	for (let i = 0; i < Math.max(x.nums.length, y.nums.length); i++) {
		const d = (x.nums[i] || 0) - (y.nums[i] || 0);
		if (d) return d < 0 ? -1 : 1;
	}
	if (x.pre === y.pre) return 0;
	if (!x.pre) return 1;
	if (!y.pre) return -1;
	return x.pre < y.pre ? -1 : 1;
}
