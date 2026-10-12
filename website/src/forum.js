/* TROLL CLIENT HOMEPAGE - the forum (it used to be a guestbook).
   Boards are fixed; threads and posts live in TrollHQ's SQLite next to the counters, so new posts can
   go out over the same live WebSocket as everything else. There are no accounts: a post has a name,
   and "name#secret" turns the secret into a tripcode so a regular can prove it's still them. Whoever
   writes a post gets a random key back (kept in their browser) that lets them edit or remove it.

   The functions here that take `hq` run inside the Durable Object; `forumApi` runs in the Worker. */
import { AVATARS, json, body, clean, cleanSite, digest, moderator, salt, visitorHash } from "./util.js";

export const BOARDS = [
	{ id: "general", name: "General", icon: "troll", blurb: "anything troll client" },
	{ id: "help", name: "Help", icon: "gear", blurb: "something broke? ask here" },
	{ id: "ideas", name: "Module Ideas", icon: "star", blurb: "what should annoy people next" },
	{ id: "show", name: "Show & Tell", icon: "radar", blurb: "macros, configs, skins, clips" },
	{ id: "guestbook", name: "Guestbook", icon: "chat", blurb: "just saying hi. the old guestbook lives here" }
];
const THREADS_PER_PAGE = 25;
const POSTS_PER_PAGE = 20;
const EDIT_WINDOW_MS = 30 * 60000;

const POST_COLUMNS = "id, thread, name, trip, site, avatar, msg, at, edited, hidden";
const THREAD_COLUMNS = "id, board, title, name, trip, avatar, at, bumped, replies, views, last_name, last_at, pinned, locked";

const isBoard = (id) => BOARDS.some((b) => b.id === id);
const pages = (count, size) => Math.max(1, Math.ceil(count / size));

/** What a client gets to see of a post: a removed one keeps its place but loses its words. */
function publicPost(p) {
	if (!p.hidden) return { id: p.id, thread: p.thread, name: p.name, trip: p.trip, site: p.site, avatar: p.avatar, msg: p.msg, at: p.at, edited: p.edited };
	return { id: p.id, thread: p.thread, name: p.name, trip: p.trip, site: "", avatar: p.avatar, msg: "", at: p.at, edited: 0, removed: true };
}

export function migrate(hq) {
	hq.sql.exec(`
		CREATE TABLE IF NOT EXISTS threads (
			id INTEGER PRIMARY KEY AUTOINCREMENT, board TEXT NOT NULL, title TEXT NOT NULL,
			name TEXT NOT NULL, trip TEXT NOT NULL DEFAULT '', avatar TEXT NOT NULL DEFAULT 'troll',
			at INTEGER NOT NULL, bumped INTEGER NOT NULL, replies INTEGER NOT NULL DEFAULT 0, views INTEGER NOT NULL DEFAULT 0,
			last_name TEXT NOT NULL DEFAULT '', last_at INTEGER NOT NULL DEFAULT 0,
			pinned INTEGER NOT NULL DEFAULT 0, locked INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0
		);
		CREATE INDEX IF NOT EXISTS threads_board ON threads (board, hidden, pinned, bumped);
		CREATE TABLE IF NOT EXISTS posts (
			id INTEGER PRIMARY KEY AUTOINCREMENT, thread INTEGER NOT NULL,
			name TEXT NOT NULL, trip TEXT NOT NULL DEFAULT '', site TEXT NOT NULL DEFAULT '', avatar TEXT NOT NULL DEFAULT 'troll',
			msg TEXT NOT NULL, at INTEGER NOT NULL, edited INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0,
			key TEXT NOT NULL DEFAULT ''
		);
		CREATE INDEX IF NOT EXISTS posts_thread ON posts (thread, id);
	`);
	if (hq.counter("forum_seeded")) return;

	const opened = Date.now();
	const seed = (board, title, msg, locked) => {
		const id = hq.sql.exec("INSERT INTO threads (board, title, name, at, bumped, last_name, last_at, pinned, locked) VALUES (?, ?, 'webmaster', ?, ?, 'webmaster', ?, 1, ?) RETURNING id",
			board, title, opened, opened, opened, locked ? 1 : 0).one().id;
		hq.sql.exec("INSERT INTO posts (thread, name, msg, at) VALUES (?, 'webmaster', ?, ?)", id, msg, opened);
		return id;
	};
	seed("general", "welcome to the forum (read me)",
		"the guestbook grew up. it's a forum now: boards, threads, replies, the lot.\n\n" +
		"no accounts. pick a name and post. want people to know it's really you next time? write your name as name#secret " +
		"and the secret turns into a tripcode next to your name (the secret itself is never shown or stored).\n\n" +
		"> lines that start with > are quotes\n>>1 links to a post by its number\n\n" +
		"you can edit your own posts for half an hour and remove them whenever, from the browser you wrote them in. be nice. ish.", true);

	// the old guestbook: every entry becomes a reply in one pinned thread, so signing it still works
	const book = seed("guestbook", "the guestbook",
		"this is the old guestbook, moved in as it was. everything people wrote is below. reply to sign it.", false);
	let replies = 0, last = null;
	for (const e of hq.sql.exec("SELECT name, site, msg, avatar, at FROM guestbook WHERE hidden = 0 ORDER BY id").toArray()) {
		hq.sql.exec("INSERT INTO posts (thread, name, site, avatar, msg, at) VALUES (?, ?, ?, ?, ?, ?)", book, e.name, e.site, e.avatar, e.msg, e.at);
		replies++;
		last = e;
	}
	if (last) {
		hq.sql.exec("UPDATE threads SET replies = ?, last_name = ?, last_at = ?, bumped = ? WHERE id = ?", replies, last.name, last.at, Math.max(opened, last.at), book);
	}
	hq.add("forum_seeded");
}

/** Numbers for the sidebar and the ticker. */
export function counts(hq) {
	const posts = hq.sql.exec("SELECT COUNT(*) AS n FROM posts p JOIN threads t ON t.id = p.thread WHERE p.hidden = 0 AND t.hidden = 0").one().n;
	const threads = hq.sql.exec("SELECT COUNT(*) AS n FROM threads WHERE hidden = 0").one().n;
	const latest = hq.sql.exec(`SELECT p.name, substr(p.msg, 1, 80) AS msg, p.at, t.id AS thread, t.title FROM posts p JOIN threads t ON t.id = p.thread
		WHERE p.hidden = 0 AND t.hidden = 0 ORDER BY p.id DESC LIMIT 1`).toArray()[0] || null;
	return { posts, threads, latest };
}

export function index(hq) {
	const boards = BOARDS.map((b) => {
		const n = hq.sql.exec("SELECT COUNT(*) AS threads, COALESCE(SUM(replies), 0) + COUNT(*) AS posts FROM threads WHERE board = ? AND hidden = 0", b.id).one();
		const last = hq.sql.exec("SELECT id, title, last_name AS name, last_at AS at FROM threads WHERE board = ? AND hidden = 0 ORDER BY last_at DESC LIMIT 1", b.id).toArray()[0] || null;
		return { ...b, threads: n.threads, posts: n.posts, last };
	});
	const recent = hq.sql.exec(`SELECT p.id, p.name, p.trip, p.avatar, substr(p.msg, 1, 120) AS msg, p.at, t.id AS thread, t.title, t.board
		FROM posts p JOIN threads t ON t.id = p.thread WHERE p.hidden = 0 AND t.hidden = 0 ORDER BY p.id DESC LIMIT 8`).toArray();
	return { boards, recent, ...counts(hq) };
}

export function board(hq, id, page) {
	const b = BOARDS.find((x) => x.id === id);
	if (!b) return { error: "no such board", status: 404 };
	const total = hq.sql.exec("SELECT COUNT(*) AS n FROM threads WHERE board = ? AND hidden = 0", id).one().n;
	const last = pages(total, THREADS_PER_PAGE);
	page = Math.max(1, Math.min(last, Math.floor(page) || 1));
	const threads = hq.sql.exec(`SELECT ${THREAD_COLUMNS} FROM threads WHERE board = ? AND hidden = 0 ORDER BY pinned DESC, bumped DESC LIMIT ? OFFSET ?`,
		id, THREADS_PER_PAGE, (page - 1) * THREADS_PER_PAGE).toArray();
	return { board: b, threads, page, pages: last, total };
}

export function thread(hq, id, page, who) {
	const t = hq.sql.exec(`SELECT ${THREAD_COLUMNS} FROM threads WHERE id = ? AND hidden = 0`, id).toArray()[0];
	if (!t) return { error: "that thread doesn't exist (any more)", status: 404 };
	const total = t.replies + 1;
	const last = pages(total, POSTS_PER_PAGE);
	page = page === "last" ? last : Math.max(1, Math.min(last, Math.floor(page) || 1));
	// the first post always shows, removed or not; removed replies are skipped
	const first = hq.sql.exec("SELECT MIN(id) AS id FROM posts WHERE thread = ?", id).one().id;
	const posts = hq.sql.exec(`SELECT ${POST_COLUMNS} FROM posts WHERE thread = ? AND (hidden = 0 OR id = ?) ORDER BY id LIMIT ? OFFSET ?`,
		id, first, POSTS_PER_PAGE, (page - 1) * POSTS_PER_PAGE).toArray().map(publicPost);
	if (who && hq.allow("fview:" + id + ":" + who, 600000)) {
		hq.sql.exec("UPDATE threads SET views = views + 1 WHERE id = ?", id);
		t.views++;
	}
	return { thread: t, board: BOARDS.find((b) => b.id === t.board), posts, first, page, pages: last, total };
}

/** Where a post lives, for >>123 links. */
export function find(hq, postId) {
	const p = hq.sql.exec("SELECT p.id, p.thread FROM posts p JOIN threads t ON t.id = p.thread WHERE p.id = ? AND p.hidden = 0 AND t.hidden = 0", postId).toArray()[0];
	if (!p) return { error: "no such post", status: 404 };
	const first = hq.sql.exec("SELECT MIN(id) AS id FROM posts WHERE thread = ?", p.thread).one().id;
	const before = hq.sql.exec("SELECT COUNT(*) AS n FROM posts WHERE thread = ? AND id < ? AND (hidden = 0 OR id = ?)", p.thread, p.id, first).one().n;
	return { thread: p.thread, post: p.id, page: Math.floor(before / POSTS_PER_PAGE) + 1 };
}

export function create(hq, who, data) {
	if (!isBoard(data.board)) return { error: "no such board", status: 404 };
	if (!hq.allow("fthread:" + who, 120000)) return { error: "slow down! one new thread every two minutes.", status: 429 };
	const dupe = hq.sql.exec("SELECT 1 FROM threads WHERE board = ? AND title = ? AND hidden = 0 AND at > ?", data.board, data.title, Date.now() - 3600000).toArray().length;
	if (dupe) return { error: "there's already a thread called that.", status: 409 };
	const at = Date.now();
	const id = hq.sql.exec(`INSERT INTO threads (board, title, name, trip, avatar, at, bumped, last_name, last_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`,
		data.board, data.title, data.name, data.trip, data.avatar, at, at, data.name, at).one().id;
	const post = hq.sql.exec(`INSERT INTO posts (thread, name, trip, site, avatar, msg, at, key) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING ${POST_COLUMNS}`,
		id, data.name, data.trip, data.site, data.avatar, data.msg, at, data.keyHash).one();
	const t = hq.sql.exec(`SELECT ${THREAD_COLUMNS} FROM threads WHERE id = ?`, id).one();
	hq.broadcast({ type: "forum", event: "thread", thread: t, post: publicPost(post) });
	hq.pushStats();
	return { thread: t, post: publicPost(post) };
}

export function reply(hq, who, threadId, data) {
	const t = hq.sql.exec("SELECT id, locked, replies FROM threads WHERE id = ? AND hidden = 0", threadId).toArray()[0];
	if (!t) return { error: "that thread doesn't exist (any more)", status: 404 };
	if (t.locked) return { error: "that thread is locked.", status: 403 };
	if (!hq.allow("freply:" + who, 20000)) return { error: "slow down! one reply every 20 seconds.", status: 429 };
	const last = hq.sql.exec("SELECT msg FROM posts WHERE thread = ? ORDER BY id DESC LIMIT 1", threadId).toArray()[0];
	if (last && last.msg === data.msg) return { error: "someone just said exactly that.", status: 409 };
	const at = Date.now();
	const post = hq.sql.exec(`INSERT INTO posts (thread, name, trip, site, avatar, msg, at, key) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING ${POST_COLUMNS}`,
		threadId, data.name, data.trip, data.site, data.avatar, data.msg, at, data.keyHash).one();
	// "sage" in the homepage box is not a thing here: every reply bumps
	hq.sql.exec("UPDATE threads SET replies = replies + 1, bumped = ?, last_name = ?, last_at = ? WHERE id = ?", at, data.name, at, threadId);
	const now = hq.sql.exec(`SELECT ${THREAD_COLUMNS} FROM threads WHERE id = ?`, threadId).one();
	hq.broadcast({ type: "forum", event: "reply", thread: now, post: publicPost(post) });
	hq.pushStats();
	return { thread: now, post: publicPost(post), page: pages(now.replies + 1, POSTS_PER_PAGE) };
}

function ownPost(hq, postId, keyHash, admin) {
	const p = hq.sql.exec(`SELECT ${POST_COLUMNS}, key FROM posts WHERE id = ?`, postId).toArray()[0];
	if (!p || p.hidden) return { error: "no such post", status: 404 };
	if (!admin && (!p.key || !keyHash || p.key !== keyHash)) return { error: "that's not your post (or not from this browser).", status: 403 };
	return { post: p };
}

export function edit(hq, postId, keyHash, msg) {
	const { post, ...problem } = ownPost(hq, postId, keyHash, false);
	if (!post) return problem;
	if (Date.now() - post.at > EDIT_WINDOW_MS) return { error: "too late to edit that one (30 minutes).", status: 403 };
	const at = Date.now();
	hq.sql.exec("UPDATE posts SET msg = ?, edited = ? WHERE id = ?", msg, at, postId);
	const saved = publicPost({ ...post, msg, edited: at });
	hq.broadcast({ type: "forum", event: "edit", post: saved });
	return { post: saved };
}

export function remove(hq, postId, keyHash, admin) {
	const { post, ...problem } = ownPost(hq, postId, keyHash, admin);
	if (!post) return problem;
	const first = hq.sql.exec("SELECT MIN(id) AS id FROM posts WHERE thread = ?", post.thread).one().id;
	hq.sql.exec("UPDATE posts SET hidden = 1 WHERE id = ?", postId);
	if (postId === first) {
		// a thread nobody answered goes with its first post; one with replies keeps them
		const t = hq.sql.exec("SELECT replies FROM threads WHERE id = ?", post.thread).one();
		if (!t.replies) hq.sql.exec("UPDATE threads SET hidden = 1 WHERE id = ?", post.thread);
	} else {
		const last = hq.sql.exec("SELECT name, at FROM posts WHERE thread = ? AND (hidden = 0 OR id = ?) ORDER BY id DESC LIMIT 1", post.thread, first).one();
		hq.sql.exec("UPDATE threads SET replies = MAX(0, replies - 1), last_name = ?, last_at = ? WHERE id = ?", last.name, last.at, post.thread);
	}
	hq.broadcast({ type: "forum", event: "remove", post: { id: postId, thread: post.thread } });
	hq.pushStats();
	return { ok: true };
}

/** Pin, lock or hide a whole thread. Moderators only (checked by whoever calls this). */
export function moderate(hq, threadId, flags) {
	const t = hq.sql.exec("SELECT id FROM threads WHERE id = ?", threadId).toArray()[0];
	if (!t) return { error: "no such thread", status: 404 };
	for (const field of ["pinned", "locked", "hidden"]) {
		if (typeof flags[field] === "boolean") hq.sql.exec(`UPDATE threads SET ${field} = ? WHERE id = ?`, flags[field] ? 1 : 0, threadId);
	}
	hq.broadcast({ type: "forum", event: "moderate", thread: { id: threadId } });
	hq.pushStats();
	return { thread: hq.sql.exec(`SELECT ${THREAD_COLUMNS}, hidden FROM threads WHERE id = ?`, threadId).one() };
}

export function search(hq, query) {
	// LIKE with the wildcards people type taken literally
	const like = "%" + query.replace(/[\\%_]/g, "\\$&") + "%";
	const results = hq.sql.exec(`SELECT p.id, p.name, p.trip, p.avatar, substr(p.msg, 1, 200) AS msg, p.at, t.id AS thread, t.title, t.board
		FROM posts p JOIN threads t ON t.id = p.thread
		WHERE p.hidden = 0 AND t.hidden = 0 AND (p.msg LIKE ? ESCAPE '\\' OR t.title LIKE ? ESCAPE '\\' OR p.name LIKE ? ESCAPE '\\')
		ORDER BY p.id DESC LIMIT 30`, like, like, like).toArray();
	return { query, results };
}

// ------------------------------------------------------------ the Worker side: /api/forum/*

/** "name#secret" -> the name, and a tripcode only that secret makes. The secret goes no further than this. */
async function identity(raw, env) {
	const text = typeof raw === "string" ? raw : "";
	const hash = text.indexOf("#");
	const name = clean(hash >= 0 ? text.slice(0, hash) : text, 32) || "anonymous coward";
	const secret = hash >= 0 ? text.slice(hash + 1).trim().slice(0, 64) : "";
	const trip = secret ? (await digest("SHA-256", (await salt(env)) + "|trip|" + secret)).slice(0, 8) : "";
	return { name, trip };
}

async function draft(data, env) {
	// bots fill in every field, including the one people can't see
	if (data.homepage2) return { bot: true };
	const msg = clean(data.msg, 4000, true);
	if (msg.length < 2) return { error: "say a bit more than that" };
	const key = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
	return {
		...(await identity(data.name, env)), msg, key, keyHash: await digest("SHA-256", key),
		site: cleanSite(data.site), avatar: AVATARS.includes(data.avatar) ? data.avatar : "troll"
	};
}

const reply200 = ({ status, ...result }) => json(result, status || 200);

export async function forumApi(request, env, hq, path, url) {
	const method = request.method;
	const page = url.searchParams.get("page");
	let m;

	if (path === "/api/forum" && method === "GET") return json(await hq.forum("index"));
	if (path === "/api/forum/search" && method === "GET") {
		const q = clean(url.searchParams.get("q") || "", 60);
		if (q.length < 2) return json({ error: "search for at least two letters" }, 400);
		return json(await hq.forum("search", q));
	}
	if ((m = path.match(/^\/api\/forum\/board\/([a-z]+)$/)) && method === "GET") return reply200(await hq.forum("board", m[1], parseInt(page || "1", 10)));
	if ((m = path.match(/^\/api\/forum\/thread\/(\d+)$/)) && method === "GET") {
		return reply200(await hq.forum("thread", parseInt(m[1], 10), page === "last" ? "last" : parseInt(page || "1", 10), await visitorHash(request, env)));
	}
	if ((m = path.match(/^\/api\/forum\/find\/(\d+)$/)) && method === "GET") return reply200(await hq.forum("find", parseInt(m[1], 10)));

	if (path === "/api/forum/thread" && method === "POST") {
		const data = await body(request, 20000);
		if (!data) return json({ error: "that didn't look like a post" }, 400);
		const d = await draft(data, env);
		if (d.bot) return json({ thread: { id: 0 }, post: { id: 0 } });
		if (d.error) return json({ error: d.error }, 400);
		const title = clean(data.title, 80);
		if (title.length < 3) return json({ error: "give the thread a title" }, 400);
		const { key, ...rest } = d;
		const { status, ...result } = await hq.forum("create", await visitorHash(request, env), { ...rest, title, board: String(data.board) });
		return json(result.error ? result : { ...result, key }, status || 200);
	}
	if ((m = path.match(/^\/api\/forum\/thread\/(\d+)\/reply$/)) && method === "POST") {
		const data = await body(request, 20000);
		if (!data) return json({ error: "that didn't look like a post" }, 400);
		const d = await draft(data, env);
		if (d.bot) return json({ post: { id: 0 } });
		if (d.error) return json({ error: d.error }, 400);
		const { key, ...rest } = d;
		const { status, ...result } = await hq.forum("reply", await visitorHash(request, env), parseInt(m[1], 10), rest);
		return json(result.error ? result : { ...result, key }, status || 200);
	}
	if ((m = path.match(/^\/api\/forum\/thread\/(\d+)$/)) && method === "PATCH") {
		if (!(await moderator(request, env))) return json({ error: "nope" }, 403);
		return reply200(await hq.forum("moderate", parseInt(m[1], 10), (await body(request)) || {}));
	}
	if ((m = path.match(/^\/api\/forum\/post\/(\d+)$/)) && (method === "DELETE" || method === "PATCH")) {
		const admin = Boolean(await moderator(request, env));
		const key = request.headers.get("x-post-key") || "";
		const keyHash = key ? await digest("SHA-256", key) : "";
		if (method === "DELETE") return reply200(await hq.forum("remove", parseInt(m[1], 10), keyHash, admin));
		const data = (await body(request, 20000)) || {};
		const msg = clean(data.msg, 4000, true);
		if (msg.length < 2) return json({ error: "say a bit more than that" }, 400);
		return reply200(await hq.forum("edit", parseInt(m[1], 10), keyHash, msg));
	}
	return json({ error: "no such endpoint" }, 404);
}
