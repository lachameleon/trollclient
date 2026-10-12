/* TROLL CLIENT HOMEPAGE - the forum.
   One page, four views (the board list, a board, a thread, search results), switched by the query
   string so every view has a link: /forum, ?board=help, ?thread=12&page=2, ?post=45, ?q=twerk.
   New threads and replies arrive over the site's live WebSocket. There are no accounts; the key for
   each post you write stays in this browser, which is what lets you edit or remove it later.
   Moderators are Minecraft accounts, not passwords: one of them types `.mod web` in game, pastes
   the key it gets under "moderator?" here, and for 12 hours every post has a remove button and
   every thread can be locked, pinned or hidden. The board list also listens in on the in-game chatroom. */
(function () {
	"use strict";
	var root = document.getElementById("forum");
	var T = window.TrollSite;
	if (!root || !T) return;

	var esc = T.esc, ago = T.ago, fmt = T.fmt, store = T.store, session = T.session;
	var crumbs = document.getElementById("forum-crumbs"), searchForm = document.getElementById("forum-search"), intro = document.getElementById("forum-intro");
	var AVATARS = window.TrollSkin ? window.TrollSkin.SKINS : ["troll"];
	var EDIT_MS = 30 * 60000, MAX = 4000;
	var view = { kind: "" };
	var refreshTimer = 0;

	// ------------------------------------------------------------ helpers
	function el(tag, cls, html) {
		var e = document.createElement(tag);
		if (cls) e.className = cls;
		if (html != null) e.innerHTML = html;
		return e;
	}

	function api(path, opts) {
		return fetch("/api/forum" + path, opts).then(function (r) {
			return r.json().then(function (j) {
				if (!r.ok || j.error) { var err = new Error(j.error || "that didn't work"); err.status = r.status; throw err; }
				return j;
			}, function () { throw new Error("the forum server is taking a nap (this copy of the site might not have one)"); });
		});
	}

	function send(path, method, data, key) {
		var headers = { "content-type": "application/json" };
		if (key) headers["x-post-key"] = key;
		if (modKey()) headers.authorization = "Bearer " + modKey();
		return api(path, { method: method, headers: headers, body: data ? JSON.stringify(data) : undefined });
	}

	/** A moderator's website key, for this tab only. */
	function modKey(value) {
		if (value !== undefined) session("troll-mod", value);
		return session("troll-mod") || "";
	}

	/** The keys for the posts written in this browser: { postId: key }. */
	function keys() {
		try { return JSON.parse(store("troll-post-keys") || "{}") || {}; } catch (e) { return {}; }
	}
	function remember(post, key) {
		if (!post || !post.id || !key) return;
		var k = keys();
		k[post.id] = key;
		store("troll-post-keys", JSON.stringify(k));
		session("troll-signed", ((session("troll-signed") || "") + "," + post.id).replace(/^,/, ""));
	}

	function head(canvas, avatar) {
		if (window.TrollSkin) window.TrollSkin.head(AVATARS.indexOf(avatar) >= 0 ? avatar : "troll", canvas);
	}

	function who(p) {
		return "<b>" + esc(p.name) + "</b>" + (p.trip ? '<span class="trip" data-tip="tripcode: the same secret always makes the same one">&#9670;' + esc(p.trip) + "</span>" : "");
	}

	/** Post text to HTML: everything escaped, then >>123 links, bare links, and > quotes. */
	function format(msg) {
		return String(msg).split("\n").map(function (line) {
			var html = esc(line)
				.replace(/&gt;&gt;(\d{1,9})/g, '<a class="ref" href="/forum?post=$1" data-forum>&gt;&gt;$1</a>')
				.replace(/\bhttps?:\/\/[^\s<]+[^\s<.,;:!?)\]]/g, function (u) { return '<a href="' + u + '" rel="nofollow noopener ugc" target="_blank">' + u + "</a>"; });
			return /^&gt;/.test(html) && !/^<a class="ref"/.test(html) ? '<span class="quote">' + html + "</span>" : html;
		}).join("<br>");
	}

	function pager(page, pages, href) {
		if (pages <= 1) return "";
		var out = '<nav class="pager" aria-label="pages">';
		var link = function (n, label, cls) {
			return n === page && !cls ? '<b aria-current="page">' + n + "</b>" : '<a href="' + href(n) + '" data-forum' + (cls ? ' class="' + cls + '"' : "") + ">" + label + "</a>";
		};
		if (page > 1) out += link(page - 1, "&laquo; prev", "step");
		for (var n = 1; n <= pages; n++) {
			if (n === 1 || n === pages || Math.abs(n - page) <= 2) out += link(n, n);
			else if (n === 2 || n === pages - 1) out += "<i>..</i>";
		}
		if (page < pages) out += link(page + 1, "next &raquo;", "step");
		return out + "</nav>";
	}

	function setCrumbs(parts) {
		crumbs.innerHTML = '<a href="/forum" data-forum>forum</a>' + parts.map(function (p) {
			return ' <span class="sep">&raquo;</span> ' + (p.href ? '<a href="' + p.href + '" data-forum>' + esc(p.label) + "</a>" : "<span>" + esc(p.label) + "</span>");
		}).join("");
		if (intro) intro.hidden = parts.length > 0;
		document.title = "Troll Client :: " + (parts.length ? parts[parts.length - 1].label + " :: " : "") + "forum";
	}

	function fail(err) {
		root.innerHTML = "";
		root.appendChild(el("p", "dim", esc(err && err.message ? err.message : "that didn't work") + '. <a href="/forum" data-forum>back to the forum</a>'));
	}

	function loading() {
		root.classList.add("busy");
	}
	function show(node) {
		root.classList.remove("busy");
		root.innerHTML = "";
		root.appendChild(node);
	}

	// ------------------------------------------------------------ the post form (new thread, reply)
	function postForm(opts) {
		var form = el("form", "group post-form");
		form.noValidate = true;
		form.innerHTML = '<span class="legend"><img class="icon" src="img/icons/chat.png" alt=""> ' + esc(opts.legend) + "</span>" +
			(opts.title ? '<div class="full"><label>thread title</label><input name="title" maxlength="80" placeholder="what\'s this about?" required></div>' : "") +
			'<div><label>your name <span class="dim" data-tip="write name#secret and the secret becomes a tripcode next to your name, so people know it\'s still you. the secret itself is never shown.">(name#secret = tripcode)</span></label>' +
			'<input name="name" maxlength="96" placeholder="anonymous coward" autocomplete="nickname"></div>' +
			'<div><label>your homepage <span class="dim">(optional)</span></label><input name="site" maxlength="120" placeholder="http://www.geocities.com/..." inputmode="url"></div>' +
			'<div class="full"><label>pick a face</label><div class="avatars" role="radiogroup" aria-label="avatar"></div></div>' +
			'<div class="full"><label>message <span class="lcd-count" aria-live="off">' + MAX + "</span></label>" +
			'<textarea name="msg" maxlength="' + MAX + '" required placeholder="' + esc(opts.placeholder || "cool site!!") + '"></textarea>' +
			'<div class="dim small">&gt; at the start of a line quotes it &middot; &gt;&gt;12 links to post #12 &middot; links turn into links</div></div>' +
			'<div class="hp" aria-hidden="true"><label>leave this empty</label><input name="homepage2" tabindex="-1" autocomplete="off"></div>' +
			'<div class="full form-foot"><button class="button primary" type="submit">' + esc(opts.submit) + "</button>" +
			'<span class="form-msg" role="status"></span></div>';
		var avatars = form.querySelector(".avatars"), status = form.querySelector(".form-msg"), left = form.querySelector(".lcd-count"), msg = form.msg;
		var pick = store("troll-avatar") || "troll";
		AVATARS.forEach(function (name) {
			var label = el("label");
			label.setAttribute("data-tip", name);
			label.innerHTML = '<input type="radio" name="avatar" value="' + name + '"' + (name === pick ? " checked" : "") + ' aria-label="' + name + '"><canvas></canvas>';
			avatars.appendChild(label);
			head(label.querySelector("canvas"), name);
		});
		form.name.value = store("troll-forum-name") || "";
		form.site.value = store("troll-forum-site") || "";

		function count() {
			var n = MAX - Array.from(msg.value).length;
			left.textContent = n;
			left.classList.toggle("low", n < 50);
		}
		function say(text, err) {
			status.textContent = text;
			status.classList.toggle("err", Boolean(err));
			if (err) {
				form.animate([{ transform: "translateX(-6px)" }, { transform: "translateX(6px)" }, { transform: "none" }], { duration: 240 });
				if (T.sound) T.sound.error();
			}
		}
		msg.addEventListener("input", count);
		form.addEventListener("submit", function (e) {
			e.preventDefault();
			var data = {
				name: form.name.value, site: form.site.value, msg: msg.value, homepage2: form.homepage2.value,
				avatar: (form.querySelector("input[name=avatar]:checked") || {}).value || "troll"
			};
			if (opts.title) {
				data.title = form.title.value;
				if (data.title.trim().length < 3) { say("give the thread a title", true); return; }
			}
			if (msg.value.trim().length < 2) { say("say a bit more than that", true); return; }
			store("troll-avatar", data.avatar);
			store("troll-forum-name", data.name);
			store("troll-forum-site", data.site);
			var btn = form.querySelector("button[type=submit]");
			btn.disabled = true;
			say("sending...", false);
			opts.send(data).then(function (res) {
				btn.disabled = false;
				msg.value = "";
				if (opts.title) form.title.value = "";
				count();
				say("posted!", false);
				if (T.sound) T.sound.chime();
				remember(res.post, res.key);
				opts.done(res);
			}).catch(function (err) {
				btn.disabled = false;
				say(err.message, true);
			});
		});
		form.quote = function (text) {
			msg.value = (msg.value ? msg.value.replace(/\n*$/, "\n") : "") + text + "\n";
			count();
			form.scrollIntoView({ behavior: "smooth", block: "center" });
			msg.focus();
		};
		count();
		return form;
	}

	// ------------------------------------------------------------ view: the board list
	function showIndex() {
		loading();
		api("").then(function (d) {
			view = { kind: "index" };
			setCrumbs([]);
			var box = el("div");
			var rows = d.boards.map(function (b) {
				var last = b.last ? '<a href="/forum?thread=' + b.last.id + '&amp;page=last" data-forum>' + esc(b.last.title) + '</a><div class="dim small">by ' + esc(b.last.name) + ", " + esc(ago(b.last.at)) + "</div>" : '<span class="dim small">nothing yet. be first.</span>';
				return '<tr><td class="f-ico"><img class="icon" src="img/icons/' + esc(b.icon) + '.png" alt=""></td>' +
					'<td><a class="f-title" href="/forum?board=' + esc(b.id) + '" data-forum>' + esc(b.name) + '</a><div class="dim small">' + esc(b.blurb) + "</div></td>" +
					'<td class="f-num">' + fmt(b.threads) + '</td><td class="f-num">' + fmt(b.posts) + '</td><td class="f-last">' + last + "</td></tr>";
			}).join("");
			box.appendChild(el("table", "data forum-table", '<tr><th colspan="2">board</th><th class="f-num">threads</th><th class="f-num">posts</th><th>last post</th></tr>' + rows));
			box.appendChild(el("p", "dim small forum-totals", fmt(d.threads) + " threads &middot; " + fmt(d.posts) + " posts &middot; no accounts, no email, no captcha (yet)"));

			box.appendChild(el("h3", "", "latest posts"));
			var recent = el("div", "recent");
			if (!d.recent.length) recent.appendChild(el("p", "dim", "nobody has said anything yet."));
			d.recent.forEach(function (p) {
				var row = el("a", "recent-row bevel", '<canvas aria-hidden="true"></canvas><span class="r-body"><span class="r-head">' + who(p) + ' <span class="dim">in</span> ' + esc(p.title) +
					'</span><span class="r-msg dim">' + esc(p.msg) + '</span></span><span class="r-when dim small">' + esc(ago(p.at)) + "</span>");
				row.href = "/forum?post=" + p.id;
				row.setAttribute("data-forum", "");
				head(row.querySelector("canvas"), p.avatar);
				recent.appendChild(row);
			});
			box.appendChild(recent);

			box.appendChild(el("h3", "", "the chatroom"));
			// the room keeps its connection (and its scrollback) when the list redraws for a new post
			box.appendChild(Room.box || Room.element());
			show(box);
			Room.start();
		}).catch(fail);
	}

	// ------------------------------------------------------------ view: a board
	function showBoard(id, page) {
		loading();
		api("/board/" + encodeURIComponent(id) + "?page=" + page).then(function (d) {
			view = { kind: "board", board: d.board.id, page: d.page };
			setCrumbs([{ label: d.board.name }]);
			var box = el("div");
			var href = function (n) { return "/forum?board=" + d.board.id + (n > 1 ? "&amp;page=" + n : ""); };
			var bar = el("div", "forum-tools", '<span class="dim">' + esc(d.board.blurb) + " &middot; " + fmt(d.total) + " thread" + (d.total === 1 ? "" : "s") + "</span>");
			var open = el("button", "button primary", "new thread");
			open.type = "button";
			bar.appendChild(open);
			box.appendChild(bar);

			var form = postForm({
				legend: "new thread in " + d.board.name, title: true, submit: "post thread", placeholder: "say something worth a thread",
				send: function (data) { data.board = d.board.id; return send("/thread", "POST", data); },
				done: function (res) { go("/forum?thread=" + res.thread.id); }
			});
			form.hidden = true;
			open.addEventListener("click", function () {
				form.hidden = !form.hidden;
				if (!form.hidden) { form.scrollIntoView({ behavior: "smooth", block: "center" }); form.title.focus(); }
			});
			box.appendChild(form);

			if (!d.threads.length) {
				box.appendChild(el("p", "dim", "no threads here yet. the button's right there."));
			} else {
				var rows = d.threads.map(function (t) {
					var lastPage = Math.max(1, Math.ceil((t.replies + 1) / 20));
					var flags = (t.pinned ? '<span class="flag" data-tip="pinned">pinned</span>' : "") + (t.locked ? '<span class="flag" data-tip="locked: no more replies">locked</span>' : "");
					return '<tr><td><a class="f-title" href="/forum?thread=' + t.id + '" data-forum>' + esc(t.title) + "</a>" + flags +
						'<div class="dim small">by ' + esc(t.name) + (t.trip ? " &#9670;" + esc(t.trip) : "") + ", " + esc(ago(t.at)) +
						(lastPage > 1 ? ' &middot; <a href="/forum?thread=' + t.id + '&amp;page=last" data-forum>last page</a>' : "") + "</div></td>" +
						'<td class="f-num">' + fmt(t.replies) + '</td><td class="f-num">' + fmt(t.views) + "</td>" +
						'<td class="f-last"><span class="small">' + esc(t.last_name) + '</span><div class="dim small">' + esc(ago(t.last_at)) + "</div></td></tr>";
				}).join("");
				box.appendChild(el("table", "data forum-table", '<tr><th>thread</th><th class="f-num">replies</th><th class="f-num">views</th><th>last post</th></tr>' + rows));
			}
			box.insertAdjacentHTML("beforeend", pager(d.page, d.pages, href));
			show(box);
		}).catch(fail);
	}

	// ------------------------------------------------------------ view: a thread
	function postElement(p, opts) {
		var mine = keys()[p.id];
		var node = el("article", "post bevel" + (opts.fresh ? " fresh" : "") + (p.removed ? " removed" : ""));
		node.id = "p" + p.id;
		node.setAttribute("data-id", p.id);
		var name = p.site ? '<a href="' + esc(p.site) + '" rel="nofollow noopener ugc" target="_blank">' + who(p) + "</a>" : who(p);
		var acts = "";
		if (!p.removed) {
			acts += '<button type="button" data-act="quote">quote</button>';
			if (mine && Date.now() - p.at < EDIT_MS) acts += '<button type="button" data-act="edit">edit</button>';
			if (mine || modKey()) acts += '<button type="button" data-act="remove">remove</button>';
		}
		node.innerHTML = '<div class="p-who"><canvas aria-hidden="true"></canvas>' + name + (opts.first ? '<span class="op" data-tip="started the thread">OP</span>' : "") + "</div>" +
			'<div class="p-body"><div class="meta"><span title="' + esc(new Date(p.at).toLocaleString()) + '">' + esc(ago(p.at)) + "</span>" +
			'<span class="edited">' + (p.edited ? "(edited " + esc(ago(p.edited)) + ")" : "") + "</span>" +
			'<span class="acts">' + acts + '</span><a class="num" href="/forum?post=' + p.id + '" data-forum data-tip="link to this post">#' + p.id + "</a></div>" +
			'<div class="msg">' + (p.removed ? '<span class="dim">(removed by whoever wrote it)</span>' : format(p.msg)) + "</div></div>";
		head(node.querySelector("canvas"), p.avatar);
		node.post = p;
		return node;
	}

	function showThread(id, page, focus) {
		loading();
		api("/thread/" + id + "?page=" + encodeURIComponent(page)).then(function (d) {
			var t = d.thread;
			view = { kind: "thread", thread: t.id, page: d.page, pages: d.pages, seen: {} };
			setCrumbs([{ label: d.board.name, href: "/forum?board=" + d.board.id }, { label: t.title }]);
			var href = function (n) { return "/forum?thread=" + t.id + (n > 1 ? "&amp;page=" + n : ""); };
			var box = el("div");
			box.appendChild(el("h3", "thread-title", esc(t.title) + (t.pinned ? '<span class="flag">pinned</span>' : "") + (t.locked ? '<span class="flag">locked</span>' : "")));
			box.appendChild(el("p", "dim small thread-meta", "started by " + esc(t.name) + ", " + esc(ago(t.at)) + " &middot; " + fmt(t.replies) + " repl" + (t.replies === 1 ? "y" : "ies") +
				" &middot; " + fmt(t.views) + " view" + (t.views === 1 ? "" : "s")));
			if (modKey()) {
				var tools = el("div", "mod-tools", '<span class="flag">moderating</span>');
				[[t.locked ? "unlock" : "lock", { locked: !t.locked }], [t.pinned ? "unpin" : "pin", { pinned: !t.pinned }], ["hide thread", { hidden: true }]].forEach(function (tool) {
					var b = el("button", "button small", tool[0]);
					b.type = "button";
					b.addEventListener("click", function () {
						send("/thread/" + t.id, "PATCH", tool[1]).then(function () {
							if (tool[1].hidden) go("/forum?board=" + d.board.id); else route();
						}).catch(modFailed);
					});
					tools.appendChild(b);
				});
				box.appendChild(tools);
			}
			box.insertAdjacentHTML("beforeend", pager(d.page, d.pages, href));
			var list = el("div", "posts");
			d.posts.forEach(function (p) {
				view.seen[p.id] = true;
				list.appendChild(postElement(p, { first: p.id === d.first }));
			});
			box.appendChild(list);
			var bottom = el("div", "pager-slot", pager(d.page, d.pages, href));
			box.appendChild(bottom);

			var form = null;
			if (t.locked) {
				box.appendChild(el("p", "dim", "this thread is locked. nothing more to add."));
			} else {
				form = postForm({
					legend: "reply", submit: "post reply", placeholder: "well, actually...",
					send: function (data) { return send("/thread/" + t.id + "/reply", "POST", data); },
					done: function (res) {
						if (res.page !== view.page) { go("/forum?thread=" + t.id + "&page=" + res.page + "#p" + res.post.id); return; }
						addPost(res.post);
					}
				});
				box.appendChild(form);
			}
			view.list = list;
			view.form = form;
			view.first = d.first;

			list.addEventListener("click", function (e) {
				var b = e.target.closest && e.target.closest("button[data-act]");
				if (!b) return;
				var node = b.closest(".post"), p = node.post, act = b.getAttribute("data-act");
				if (act === "quote" && form) {
					form.quote(">>" + p.id + "\n" + p.msg.split("\n").filter(function (l) { return l.trim() && l.charAt(0) !== ">"; }).slice(0, 4).map(function (l) { return "> " + l; }).join("\n"));
				} else if (act === "edit") {
					editPost(node);
				} else if (act === "remove") {
					T.dialog("Remove post", "<div>Remove post <b>#" + p.id + "</b>? There's no undo.</div>", [
						{ label: "Cancel" },
						{ label: "Remove", primary: true, action: function () {
							send("/post/" + p.id, "DELETE", null, keys()[p.id]).then(function () {
								if (p.id === view.first) go("/forum?board=" + d.board.id);
							}).catch(function (err) { if (!keys()[p.id]) modFailed(err); else T.notify("Couldn't remove that", err.message, "close"); });
						} }
					]);
				}
			});
			show(box);
			var target = focus && document.getElementById(focus.replace(/^#/, ""));
			if (target) {
				target.classList.add("target");
				target.scrollIntoView({ block: "center" });
			}
		}).catch(fail);
	}

	function addPost(p) {
		if (view.kind !== "thread" || view.seen[p.id]) return;
		view.seen[p.id] = true;
		var node = postElement(p, { fresh: true });
		view.list.appendChild(node);
	}

	function editPost(node) {
		var p = node.post, box = node.querySelector(".msg");
		if (node.classList.contains("editing")) return;
		node.classList.add("editing");
		var area = el("textarea");
		area.maxLength = MAX;
		area.value = p.msg;
		var row = el("div", "form-foot", '<button class="button primary small" type="button">save</button><button class="button small" type="button">cancel</button><span class="form-msg" role="status"></span>');
		var done = function () {
			node.classList.remove("editing");
			box.innerHTML = format(p.msg);
		};
		box.innerHTML = "";
		box.appendChild(area);
		box.appendChild(row);
		area.focus();
		row.children[1].addEventListener("click", done);
		row.children[0].addEventListener("click", function () {
			send("/post/" + p.id, "PATCH", { msg: area.value }, keys()[p.id]).then(function (res) {
				p.msg = res.post.msg;
				p.edited = res.post.edited;
				node.querySelector(".edited").textContent = "(edited just now)";
				done();
			}).catch(function (err) {
				row.children[2].textContent = err.message;
				row.children[2].classList.add("err");
			});
		});
	}

	// ------------------------------------------------------------ view: search
	function showSearch(q) {
		loading();
		searchForm.q.value = q;
		api("/search?q=" + encodeURIComponent(q)).then(function (d) {
			view = { kind: "search" };
			setCrumbs([{ label: "search: " + d.query }]);
			var box = el("div");
			box.appendChild(el("p", "dim", d.results.length ? fmt(d.results.length) + (d.results.length === 30 ? "+" : "") + " post" + (d.results.length === 1 ? "" : "s") + " mention it." : "nothing found. it's a small forum."));
			var list = el("div", "recent");
			d.results.forEach(function (p) {
				var row = el("a", "recent-row bevel", '<canvas aria-hidden="true"></canvas><span class="r-body"><span class="r-head">' + who(p) + ' <span class="dim">in</span> ' + esc(p.title) +
					'</span><span class="r-msg dim">' + esc(p.msg) + '</span></span><span class="r-when dim small">' + esc(ago(p.at)) + "</span>");
				row.href = "/forum?post=" + p.id;
				row.setAttribute("data-forum", "");
				head(row.querySelector("canvas"), p.avatar);
				list.appendChild(row);
			});
			box.appendChild(list);
			show(box);
		}).catch(fail);
	}

	// ------------------------------------------------------------ moderators
	var modButton = document.getElementById("forum-mod");

	function modFailed(err) {
		// a key that stopped working: forget it rather than keep offering buttons that do nothing
		if (err && err.status === 403) { modKey(""); modLabel(); T.notify("Moderator key", "that key isn't good any more (they last 12 hours). get a new one with .mod web", "close"); route(); return; }
		T.notify("That didn't work", err && err.message ? err.message : "", "close");
	}

	function modLabel() {
		if (modButton) modButton.textContent = modKey() ? "moderating (stop)" : "moderator?";
	}

	if (modButton) {
		modButton.addEventListener("click", function () {
			if (modKey()) { modKey(""); modLabel(); route(); return; }
			var d = T.dialog("Moderator", '<div>Moderators are Minecraft accounts the site owner has listed. In game, type <code>.mod web</code>, click the key it gives you to copy it, and paste it here. It works for 12 hours, in this tab.</div>' +
				'<input class="mod-key" type="password" autocomplete="off" placeholder="paste the key" aria-label="moderator key"><div class="form-msg" role="status"></div>', [
				{ label: "Cancel" },
				{ label: "Use key", primary: true, action: function (close) {
					var input = d.el.querySelector(".mod-key"), status = d.el.querySelector(".form-msg"), key = input.value.trim();
					fetch("/api/mod", { headers: { authorization: "Bearer " + key } }).then(function (r) { return r.json().then(function (j) { return r.ok ? j : null; }); }).then(function (who) {
						if (!who) { status.textContent = "that key isn't good (they last 12 hours)"; status.classList.add("err"); return; }
						modKey(key);
						modLabel();
						close();
						T.notify("Moderating", "hello, " + who.moderator + ". posts and threads have extra buttons now.", "check");
						route();
					}).catch(function () { status.textContent = "couldn't reach the server"; status.classList.add("err"); });
					return false;
				} }
			]);
		});
		modLabel();
	}

	// ------------------------------------------------------------ routing
	function route() {
		var q = new URLSearchParams(location.search);
		if (q.get("thread") || q.get("post") || q.get("board") || q.get("q")) Room.stop();
		if (q.get("thread")) return showThread(parseInt(q.get("thread"), 10) || 0, q.get("page") || "1", location.hash);
		if (q.get("post")) {
			var id = parseInt(q.get("post"), 10) || 0;
			loading();
			return api("/find/" + id).then(function (f) {
				history.replaceState(null, "", "/forum?thread=" + f.thread + (f.page > 1 ? "&page=" + f.page : "") + "#p" + f.post);
				route();
			}).catch(fail);
		}
		if (q.get("board")) return showBoard(q.get("board"), parseInt(q.get("page"), 10) || 1);
		if (q.get("q")) return showSearch(q.get("q"));
		return showIndex();
	}

	function go(url) {
		history.pushState(null, "", url);
		route();
		var top = document.getElementById("content");
		if (top && top.getBoundingClientRect().top < 0) top.scrollIntoView({ block: "start" });
	}

	// before site.js's own link handler, which would otherwise start its "loading the next page" act
	document.addEventListener("click", function (e) {
		var a = e.target.closest && e.target.closest("a[data-forum]");
		if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
		e.preventDefault();
		go(a.getAttribute("href"));
	});
	window.addEventListener("popstate", route);
	searchForm.addEventListener("submit", function (e) {
		e.preventDefault();
		var q = searchForm.q.value.trim();
		if (q.length >= 2) go("/forum?q=" + encodeURIComponent(q));
	});

	// ------------------------------------------------------------ live: other people's posts
	T.live.on(function (m) {
		if (m.type !== "forum") return;
		if (view.kind === "thread") {
			if (m.event === "reply" && m.thread && m.thread.id === view.thread && view.page === view.pages) addPost(m.post);
			if (m.event === "edit" || m.event === "remove") {
				var node = document.getElementById("p" + m.post.id);
				if (!node) return;
				if (m.event === "remove") {
					if (m.post.id === view.first) node.querySelector(".msg").innerHTML = '<span class="dim">(removed by whoever wrote it)</span>';
					else node.remove();
				} else if (!node.classList.contains("editing")) {
					node.post.msg = m.post.msg;
					node.querySelector(".msg").innerHTML = format(m.post.msg);
					node.querySelector(".edited").textContent = "(edited just now)";
				}
			}
			if (m.event === "moderate" && m.thread.id === view.thread) route();
			return;
		}
		// lists: redraw, but not once per post when it's busy
		if (view.kind === "index" || view.kind === "board") {
			clearTimeout(refreshTimer);
			refreshTimer = setTimeout(function () {
				var typing = document.activeElement && /^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName) && root.contains(document.activeElement);
				if (!typing && (view.kind === "index" || view.kind === "board")) route();
			}, 1200);
		}
	});

	// ------------------------------------------------------------ the chatroom, listening in from the web
	var Room = {
		ws: null, box: null, log: null, online: null, retry: 1500, wanted: false, ping: 0,

		element: function () {
			this.box = el("div", "group room", '<span class="legend"><img class="icon" src="img/icons/world.png" alt=""> live from the game</span>' +
				'<div class="room-log" role="log" aria-live="polite"><p class="dim small">tuning in...</p></div>' +
				'<p class="dim small room-foot"><i class="led"></i><span class="room-online">0</span> in the room right now. It\'s every player running the client, on any server: type <code>.chat hello</code> in game to join in. You can only read it from here.</p>');
			this.log = this.box.querySelector(".room-log");
			this.online = this.box.querySelector(".room-online");
			return this.box;
		},

		line: function (m) {
			var time = new Date(m.at), hh = ("0" + time.getHours()).slice(-2) + ":" + ("0" + time.getMinutes()).slice(-2);
			var row = el("div", "room-line", '<span class="dim">' + hh + "</span> <b>" + esc(m.name) + "</b>" +
				(m.verified ? "" : '<span class="dim" data-tip="this name wasn\'t checked with Mojang">?</span>') + ": " + esc(m.text));
			row.setAttribute("data-id", m.id);
			return row;
		},

		start: function () {
			if (this.wanted && this.ws) return;
			this.wanted = true;
			this.connect();
		},

		stop: function () {
			this.wanted = false;
			clearInterval(this.ping);
			if (this.ws) { try { this.ws.close(1000, "bye"); } catch (e) { /* closed */ } }
			this.ws = null;
			this.box = null;
		},

		connect: function () {
			var self = this;
			if (!this.wanted || !("WebSocket" in window)) return;
			var ws;
			try { ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/api/chat?role=web"); } catch (e) { return; }
			this.ws = ws;
			ws.onopen = function () {
				self.retry = 1500;
				clearInterval(self.ping);
				self.ping = setInterval(function () { try { ws.send("ping"); } catch (e) { /* closed */ } }, 25000);
			};
			ws.onmessage = function (e) {
				if (e.data === "pong" || ws !== self.ws) return;
				var m;
				try { m = JSON.parse(e.data); } catch (err) { return; }
				if (m.type === "welcome") {
					self.log.innerHTML = "";
					if (!m.history.length) self.log.appendChild(el("p", "dim small", "quiet in here. nobody has said anything yet."));
					m.history.forEach(function (h) { self.log.appendChild(self.line(h)); });
					self.online.textContent = fmt(m.online);
					self.log.scrollTop = self.log.scrollHeight;
				} else if (m.type === "msg") {
					var quiet = self.log.querySelector("p");
					if (quiet) quiet.remove();
					var stick = self.log.scrollHeight - self.log.scrollTop - self.log.clientHeight < 30;
					self.log.appendChild(self.line(m.message));
					while (self.log.children.length > 100) self.log.firstChild.remove();
					if (stick) self.log.scrollTop = self.log.scrollHeight;
				} else if (m.type === "online") {
					self.online.textContent = fmt(m.online);
				} else if (m.type === "unsay") {
					var gone = self.log.querySelector('[data-id="' + m.id + '"]');
					if (gone) gone.remove();
				}
			};
			ws.onclose = function () {
				clearInterval(self.ping);
				if (ws !== self.ws || !self.wanted) return;
				setTimeout(function () { self.connect(); }, self.retry);
				self.retry = Math.min(30000, self.retry * 2);
			};
		}
	};

	route();
})();
