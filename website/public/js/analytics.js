/* TROLL CLIENT HOMEPAGE - the analytics page.
   Draws /api/analytics: anonymous usage reports from the client, added up by the Worker.
   Everything is one ink on the page's own surface, so it works in all seven themes. */
(function () {
	"use strict";

	var root = document.getElementById("an-body");
	if (!root) return;

	var DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
	var METRICS = {
		active: { label: "active installs", get: function (d) { return d.active; }, fmt: fmt },
		sessions: { label: "sessions", get: function (d) { return d.sessions; }, fmt: fmt },
		hours: { label: "hours played", get: function (d) { return d.ms / 3600000; }, fmt: function (n) { return n < 10 ? n.toFixed(1) : fmt(Math.round(n)); } },
		newInstalls: { label: "new installs", get: function (d) { return d.newInstalls; }, fmt: fmt }
	};
	var TIME_LABELS = {
		total: "client open", focused: "window focused", ingame: "in a world", singleplayer: "singleplayer",
		multiplayer: "multiplayer", menus: "in menus", paused: "paused"
	};
	var EVENT_LABELS = {
		chat_sent: "chat messages sent", server_commands: "server commands sent", server_joins: "servers joined",
		world_joins: "singleplayer worlds opened", disconnects: "worlds and servers left", deaths: "deaths",
		macro_runs: "macros run", crashes: "crash reports", crashes_with_troll_client: "crash reports mentioning troll client"
	};

	var state = { days: initialDays(), metric: "active", data: null, sort: "users", desc: true, loadedAt: 0 };

	function $(id) { return document.getElementById(id); }
	function esc(s) {
		return String(s).replace(/[&<>"']/g, function (c) {
			return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
		});
	}
	function fmt(n) { return Math.round(Number(n || 0)).toLocaleString("en-US"); }
	function pct(n, of) { return of ? Math.round((n / of) * 100) + "%" : "0%"; }
	function dur(ms) {
		var s = Math.round((ms || 0) / 1000);
		if (s < 60) return s + " s";
		var m = Math.round(s / 60);
		if (m < 60) return m + " min";
		var h = Math.floor(m / 60);
		if (h < 48) return h + " h " + (m % 60 ? (m % 60) + " min" : "");
		return fmt(Math.round(m / 60)) + " h";
	}
	function hours(ms) {
		var h = (ms || 0) / 3600000;
		return h < 10 ? h.toFixed(1) + " h" : fmt(h) + " h";
	}
	function ago(t) {
		var s = Math.max(0, Math.round((Date.now() - t) / 1000));
		return s < 60 ? s + "s ago" : s < 3600 ? Math.round(s / 60) + " min ago" : Math.round(s / 3600) + " h ago";
	}
	function initialDays() {
		var q = new URLSearchParams(location.search).get("days");
		return q === "all" || /^(1|7|30|90)$/.test(q || "") ? q : "30";
	}

	// ------------------------------------------------------------ a tooltip that shows up right away (the site's own one types itself out)
	var tip = document.createElement("div");
	tip.className = "an-tip";
	tip.setAttribute("role", "tooltip");
	tip.hidden = true;
	document.body.appendChild(tip);
	function showTip(html, x, y) {
		tip.innerHTML = html;
		tip.hidden = false;
		var w = tip.offsetWidth, h = tip.offsetHeight;
		tip.style.left = Math.max(6, Math.min(innerWidth - w - 6, x - w / 2)) + "px";
		tip.style.top = (y - h - 12 < 6 ? y + 18 : y - h - 12) + "px";
	}
	function hideTip() { tip.hidden = true; }
	/** Every [data-an] element shows its tooltip on hover and on focus. */
	function hoverable(container) {
		container.addEventListener("pointermove", function (e) {
			// the line chart runs its own crosshair tooltip
			if (e.target.closest(".an-chart")) return;
			var el = e.target.closest("[data-an]");
			if (el && container.contains(el)) showTip(el.getAttribute("data-an"), e.clientX, e.clientY);
			else hideTip();
		});
		container.addEventListener("pointerleave", hideTip);
		container.addEventListener("focusin", function (e) {
			var el = e.target.closest("[data-an]");
			if (!el) return;
			var r = el.getBoundingClientRect();
			showTip(el.getAttribute("data-an"), r.left + r.width / 2, r.top);
		});
		container.addEventListener("focusout", hideTip);
	}

	// ------------------------------------------------------------ pieces
	function kpis(t) {
		var tiles = [
			["playing right now", fmt(t.online), "clients reporting in the last few minutes"],
			["installs", fmt(t.installs), fmt(t.newInstalls) + " new"],
			["sessions", fmt(t.sessions), "avg " + dur(t.avgSessionMs) + ", median " + dur(t.medianSessionMs)],
			["hours played", hours(t.playMs), hours(t.ingameMs) + " of it in a world"],
			["daily / weekly / monthly", fmt(t.dau) + "/" + fmt(t.wau) + "/" + fmt(t.mau), "installs active in the last 1, 7 and 30 days"],
			["average fps", t.avgFps ? fmt(t.avgFps) : "-", "sampled once a second in a world"],
			["module toggles", fmt(t.toggles), "about " + t.avgModulesUsed + " modules used per session"],
			["crashes", fmt(t.crashes), fmt(t.crashesOurs) + " mention troll client, " + fmt(t.moduleErrors) + " module errors"]
		];
		$("an-kpis").innerHTML = tiles.map(function (k) {
			return '<div class="an-kpi bevel"><div class="dim small">' + esc(k[0]) + '</div><div class="an-kpi-n' + (String(k[1]).length > 6 ? " long" : "") + '">' + esc(k[1]) +
				'</div><div class="dim small">' + esc(k[2]) + "</div></div>";
		}).join("") +
			'<p class="dim small an-alltime">all time: ' + fmt(t.installsAllTime) + " installs, " + fmt(t.sessionsAllTime) + " sessions, " + fmt(t.hoursAllTime) + (t.hoursAllTime === 1 ? " hour" : " hours") + " played</p>";
	}

	/** One measure over the days in range: a 2px line over a faint area, crosshair and tooltip on hover. */
	function series(rows) {
		var box = $("an-series");
		var m = METRICS[state.metric];
		var values = rows.map(m.get);
		if (rows.length < 2) {
			box.innerHTML = '<div class="an-single"><div class="an-kpi-n">' + esc(m.fmt(values[0] || 0)) + '</div><div class="dim small">' + esc(m.label) + " today</div></div>";
			return;
		}
		var W = 720, H = 230, L = 44, R = 12, T = 12, B = 28;
		var max = Math.max.apply(null, values.concat([1]));
		var step = niceStep(max / 4);
		var top = Math.ceil(max / step) * step;
		var x = function (i) { return L + (i / (rows.length - 1)) * (W - L - R); };
		var y = function (v) { return T + (1 - v / top) * (H - T - B); };
		var pts = values.map(function (v, i) { return x(i).toFixed(1) + "," + y(v).toFixed(1); });
		var grid = "";
		for (var g = 0; g <= top + 1e-9; g += step) {
			grid += '<line class="an-grid-line" x1="' + L + '" x2="' + (W - R) + '" y1="' + y(g) + '" y2="' + y(g) + '"/>' +
				'<text class="an-axis" x="' + (L - 6) + '" y="' + (y(g) + 4) + '" text-anchor="end">' + esc(m.fmt(g)) + "</text>";
		}
		var ticks = [0, Math.floor((rows.length - 1) / 2), rows.length - 1].map(function (i) {
			return '<text class="an-axis" x="' + x(i) + '" y="' + (H - 8) + '" text-anchor="' + (i === 0 ? "start" : i === rows.length - 1 ? "end" : "middle") + '">' + esc(shortDay(rows[i].day)) + "</text>";
		}).join("");
		box.innerHTML = '<svg viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="' + esc(m.label) + ' per day">' + grid + ticks +
			'<path class="an-area" d="M' + x(0) + "," + y(0) + " L" + pts.join(" L") + " L" + x(rows.length - 1) + "," + y(0) + ' Z"/>' +
			'<polyline class="an-line" points="' + pts.join(" ") + '"/>' +
			'<line class="an-cross" y1="' + T + '" y2="' + (H - B) + '" x1="-10" x2="-10"/><circle class="an-dot" r="4" cx="-10" cy="-10"/>' +
			'<rect class="an-hit" x="' + L + '" y="0" width="' + (W - L - R) + '" height="' + H + '"/></svg>' +
			'<table class="an-sr"><caption>' + esc(m.label) + " per day</caption>" + rows.map(function (r, i) {
				return "<tr><th>" + esc(r.day) + "</th><td>" + esc(m.fmt(values[i])) + "</td></tr>";
			}).join("") + "</table>";
		var svg = box.querySelector("svg"), cross = svg.querySelector(".an-cross"), dot = svg.querySelector(".an-dot");
		svg.addEventListener("pointermove", function (e) {
			var r = svg.getBoundingClientRect();
			var sx = (e.clientX - r.left) / r.width * W;
			var i = Math.max(0, Math.min(rows.length - 1, Math.round((sx - L) / (W - L - R) * (rows.length - 1))));
			cross.setAttribute("x1", x(i)); cross.setAttribute("x2", x(i));
			dot.setAttribute("cx", x(i)); dot.setAttribute("cy", y(values[i]));
			var d = rows[i];
			showTip("<b>" + esc(longDay(d.day)) + "</b><br>" + esc(m.fmt(values[i])) + " " + esc(m.label) +
				'<br><span class="dim">' + fmt(d.sessions) + " sessions &middot; " + hours(d.ms) + "</span>", r.left + x(i) / W * r.width, r.top + y(values[i]) / H * r.height);
		});
		svg.addEventListener("pointerleave", function () {
			hideTip();
			cross.setAttribute("x1", -10); cross.setAttribute("x2", -10); dot.setAttribute("cx", -10);
		});
	}

	function niceStep(raw) {
		if (raw <= 0) return 1;
		var p = Math.pow(10, Math.floor(Math.log10(raw)));
		var f = raw / p;
		return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
	}
	function shortDay(d) { var p = d.split("-"); return parseInt(p[2], 10) + " " + ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][parseInt(p[1], 10) - 1]; }
	function longDay(d) { return DAYS[new Date(d + "T12:00:00Z").getUTCDay()] + " " + shortDay(d); }

	/** Horizontal bars: label and value above a thin bar, longest first. */
	function bars(el, rows, opts) {
		opts = opts || {};
		if (!rows.length) { el.innerHTML = '<p class="dim small">nothing yet</p>'; return; }
		var max = Math.max.apply(null, rows.map(function (r) { return r.value; }).concat([1]));
		el.innerHTML = '<ul class="an-bars">' + rows.map(function (r) {
			return '<li tabindex="0" data-an="' + esc("<b>" + esc(r.label) + "</b><br>" + (r.tip || esc(r.text))) + '"><span class="an-bar-label">' + esc(r.label) +
				'</span><span class="an-bar-num">' + esc(r.text) + '</span><span class="an-bar"><i style="width:' + Math.max(0.5, r.value / max * 100) + '%"></i></span></li>';
		}).join("") + "</ul>";
	}

	/** Columns for a distribution, with the value over each one. */
	function columns(title, rows, unit) {
		var max = Math.max.apply(null, rows.map(function (r) { return r.n; }).concat([1]));
		var total = rows.reduce(function (a, r) { return a + r.n; }, 0);
		return '<div class="an-dist"><h4>' + esc(title) + '</h4><div class="an-cols-chart">' + rows.map(function (r) {
			return '<div class="an-col" tabindex="0" data-an="' + esc("<b>" + esc(r.key) + "</b><br>" + fmt(r.n) + " " + unit + " (" + pct(r.n, total) + ")") + '">' +
				'<span class="an-col-n">' + (r.n ? fmt(r.n) : "") + '</span><span class="an-col-bar" style="height:' + (r.n ? Math.max(2, r.n / max * 100) : 0) + '%"></span>' +
				'<span class="an-col-k">' + esc(r.key) + "</span></div>";
		}).join("") + "</div></div>";
	}

	function heat(d) {
		var max = 1;
		d.heat.forEach(function (row) { row.forEach(function (n) { max = Math.max(max, n); }); });
		var busiest = d.hours.indexOf(Math.max.apply(null, d.hours));
		var html = '<div class="an-heat" role="table" aria-label="sessions started by weekday and hour"><span></span>';
		for (var h = 0; h < 24; h++) html += '<span class="an-heat-h">' + (h % 3 === 0 ? h : "") + "</span>";
		// Monday first, like a calendar
		[1, 2, 3, 4, 5, 6, 0].forEach(function (wd) {
			html += '<span class="an-heat-d">' + DAYS[wd] + "</span>";
			for (var h = 0; h < 24; h++) {
				var n = d.heat[wd][h];
				html += '<span class="an-cell' + (n ? "" : " zero") + '" tabindex="-1" style="--a:' + (n ? (0.15 + 0.85 * n / max).toFixed(2) : 0) + '" data-an="' +
					esc("<b>" + DAYS[wd] + " " + pad(h) + ":00-" + pad((h + 1) % 24) + ":00</b><br>" + fmt(n) + " sessions started") + '"></span>';
			}
		});
		html += "</div>";
		var total = d.hours.reduce(function (a, b) { return a + b; }, 0);
		html += '<p class="dim small">' + (total ? "busiest hour: " + pad(busiest) + ":00 to " + pad((busiest + 1) % 24) + ":00, when " + pct(d.hours[busiest], total) +
			" of sessions start. darker = more sessions." : "no sessions in this range yet.") + "</p>";
		$("an-heat").innerHTML = html;
	}
	function pad(n) { return (n < 10 ? "0" : "") + n; }

	function moduleTable(d) {
		var installs = d.totals.installs || 0;
		var cols = [
			["name", "module", false], ["users", "users", true], ["pct", "% of installs", true], ["on", "turned on", true],
			["ms", "time on", true], ["startup", "on at launch", true], ["key", "by key", true], ["cmd", "by command", true], ["err", "errors", true]
		];
		var rows = d.modules.map(function (m) { var o = Object.assign({}, m); o.pct = installs ? m.users / installs : 0; return o; });
		rows.sort(function (a, b) {
			var k = state.sort, v = k === "name" ? a.name.localeCompare(b.name) : (a[k] - b[k]);
			return state.desc ? -v : v;
		});
		var maxUsers = Math.max.apply(null, rows.map(function (r) { return r.users; }).concat([1]));
		var table = $("an-modules");
		if (!rows.length) { table.innerHTML = '<tbody><tr><td class="dim">no module has been switched on in this range yet</td></tr></tbody>'; return; }
		table.innerHTML = "<thead><tr>" + cols.map(function (c) {
			var on = state.sort === c[0];
			return '<th scope="col"' + (c[2] ? ' class="num"' : "") + ' aria-sort="' + (on ? (state.desc ? "descending" : "ascending") : "none") + '"><button type="button" data-sort="' + c[0] + '">' +
				esc(c[1]) + (on ? (state.desc ? " &darr;" : " &uarr;") : "") + "</button></th>";
		}).join("") + "</tr></thead><tbody>" + rows.map(function (m) {
			return '<tr><td class="name"><a href="/modules?q=' + encodeURIComponent(m.name) + '">' + esc(m.name) + "</a></td>" +
				'<td class="num"><span class="an-inline"><i style="width:' + (m.users / maxUsers * 100) + '%"></i></span>' + fmt(m.users) + "</td>" +
				'<td class="num">' + pct(m.users, installs) + '</td><td class="num">' + fmt(m.on) + '</td><td class="num">' + hours(m.ms) +
				'</td><td class="num">' + fmt(m.startup) + '</td><td class="num">' + fmt(m.key) + '</td><td class="num">' + fmt(m.cmd) +
				'</td><td class="num">' + fmt(m.err) + "</td></tr>";
		}).join("") + "</tbody>";
	}

	function render(d) {
		state.data = d;
		var t = d.totals;
		$("an-empty").textContent = t.sessions ? "" : "no reports in this range yet. once someone launches the client with telemetry on, it shows up here within a minute.";
		$("an-empty").hidden = !!t.sessions;
		kpis(t);
		series(d.series);
		moduleTable(d);

		var time = d.time.filter(function (r) { return r.key !== "total"; });
		bars($("an-time"), time.map(function (r) {
			return { label: TIME_LABELS[r.key] || r.key, value: r.ms, text: hours(r.ms), tip: hours(r.ms) + " (" + pct(r.ms, t.playMs) + " of the time the client was open)" };
		}));
		bars($("an-events"), d.events.map(function (r) {
			return { label: EVENT_LABELS[r.key] || r.key.replace(/_/g, " "), value: r.n, text: fmt(r.n) };
		}));
		heat(d);

		var dd = d.distributions;
		$("an-dists").innerHTML = columns("session length", dd.sessionLength, "sessions") + columns("average fps", dd.fps, "sessions") +
			columns("modules used per session", dd.modulesUsed, "sessions") + columns("times launched, per install", dd.launches, "installs");

		var installs = t.installs || 0;
		$("an-setups").innerHTML = d.breakdowns.map(function (b) { return '<div class="an-setup"><h4>' + esc(b.label) + '</h4><div data-b="' + esc(b.id) + '"></div></div>'; }).join("");
		d.breakdowns.forEach(function (b) {
			bars($("an-setups").querySelector('[data-b="' + b.id + '"]'), b.rows.map(function (r) {
				return { label: r.key, value: r.n, text: pct(r.n, installs), tip: fmt(r.n) + " of " + fmt(installs) + " installs" };
			}));
		});

		bars($("an-servers"), d.servers.map(function (r) {
			return { label: r.key, value: r.ms, text: hours(r.ms), tip: fmt(r.n) + " joins by " + fmt(r.users) + " installs, " + hours(r.ms) + " played" };
		}));
		bars($("an-mods"), d.mods.slice().sort(function (a, b) { return b.users - a.users; }).map(function (r) { return { label: r.key, value: r.users, text: pct(r.users, installs), tip: fmt(r.users) + " installs" }; }));
		bars($("an-commands"), d.commands.map(function (r) { return { label: "." + r.key, value: r.n, text: fmt(r.n), tip: fmt(r.n) + " times by " + fmt(r.users) + " installs" }; }));
		bars($("an-settings"), d.settings.map(function (r) { return { label: r.key.replace("/", " > "), value: r.n, text: fmt(r.n), tip: fmt(r.n) + " changes" }; }));
		bars($("an-screens"), d.screens.map(function (r) { return { label: r.key, value: r.n, text: fmt(r.n), tip: fmt(r.n) + " opens, " + dur(r.ms) + " spent there" }; }));
		bars($("an-errors"), d.errors.map(function (r) { return { label: r.key, value: r.n, text: fmt(r.n) }; }));
		root.setAttribute("aria-busy", "false");
	}

	// ------------------------------------------------------------ loading
	function load() {
		var days = state.days;
		$("an-updated").textContent = "loading...";
		return fetch("/api/analytics?days=" + days).then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); }).then(function (d) {
			if (days !== state.days) return;
			state.loadedAt = Date.now();
			render(d);
			stamp();
			$("an-live").classList.remove("off");
		}).catch(function () {
			$("an-updated").textContent = "couldn't reach the server";
			$("an-live").classList.add("off");
			if (!state.data) $("an-empty").textContent = "The analytics server is taking a nap (this copy of the site might not have one). Try again later.";
		});
	}
	function stamp() { if (state.loadedAt) $("an-updated").textContent = "updated " + ago(state.loadedAt); }

	document.querySelectorAll("[data-days]").forEach(function (b) {
		b.classList.toggle("on", b.getAttribute("data-days") === state.days);
		b.setAttribute("aria-pressed", b.getAttribute("data-days") === state.days);
		b.addEventListener("click", function () {
			state.days = b.getAttribute("data-days");
			document.querySelectorAll("[data-days]").forEach(function (o) { o.classList.toggle("on", o === b); o.setAttribute("aria-pressed", o === b); });
			var u = new URL(location.href);
			if (state.days === "30") u.searchParams.delete("days"); else u.searchParams.set("days", state.days);
			history.replaceState(null, "", u);
			load();
		});
	});
	document.querySelectorAll("[data-metric]").forEach(function (b) {
		b.setAttribute("aria-pressed", b.getAttribute("data-metric") === state.metric);
		b.addEventListener("click", function () {
			state.metric = b.getAttribute("data-metric");
			document.querySelectorAll("[data-metric]").forEach(function (o) { o.classList.toggle("on", o === b); o.setAttribute("aria-pressed", o === b); });
			if (state.data) series(state.data.series);
		});
	});
	$("an-modules").addEventListener("click", function (e) {
		var b = e.target.closest("[data-sort]");
		if (!b || !state.data) return;
		var k = b.getAttribute("data-sort");
		state.desc = state.sort === k ? !state.desc : k !== "name";
		state.sort = k;
		moduleTable(state.data);
	});
	hoverable(root);

	setInterval(function () { if (!document.hidden) load(); }, 60000);
	setInterval(stamp, 5000);
	document.addEventListener("visibilitychange", function () { if (!document.hidden && Date.now() - state.loadedAt > 60000) load(); });
	load();

	// ------------------------------------------------------------ raw sessions, for whoever holds ADMIN_TOKEN
	var form = $("an-admin-form");
	function token(v) {
		try { if (v === undefined) return sessionStorage.getItem("troll-admin") || ""; sessionStorage.setItem("troll-admin", v); } catch (e) { /* private mode */ }
		return "";
	}
	function raw() {
		var t = token();
		if (!t) return;
		$("an-admin-msg").textContent = "loading...";
		fetch("/api/analytics/sessions?limit=100", { headers: { authorization: "Bearer " + t } })
			.then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
			.then(function (res) {
				if (!res.ok) { $("an-admin-msg").textContent = res.j.error === "nope" ? "wrong token" : (res.j.error || "that didn't work"); token(""); return; }
				$("an-admin-msg").textContent = res.j.sessions.length + " newest sessions";
				$("an-raw").innerHTML = '<div class="an-scroll"><table class="data an-table an-raw"><thead><tr><th>last seen</th><th>install</th><th>where</th><th>setup</th>' +
					'<th class="num">length</th><th class="num">fps</th><th>modules used</th><th>everything</th></tr></thead><tbody>' +
					res.j.sessions.map(function (s) {
						var e = s.env || {};
						var mods = Object.keys(s.modules || {});
						return "<tr><td>" + esc(new Date(s.last_seen).toLocaleString()) + (s.ended ? "" : ' <b class="dim">live</b>') + "</td>" +
							'<td><code>' + esc(s.install.slice(0, 8)) + "</code></td><td>" + esc([s.region, s.country].filter(Boolean).join(", ") || "?") + "</td>" +
							"<td>" + esc([e.client && "v" + e.client, e.minecraft, e.os, e.gpu].filter(Boolean).join(" / ")) + "</td>" +
							'<td class="num">' + dur(s.ms) + '</td><td class="num">' + (s.fps_avg || "-") + "</td><td>" + esc(mods.join(", ") || "-") + "</td>" +
							"<td><details><summary>json</summary><pre>" + esc(JSON.stringify(s, null, 1)) + "</pre></details></td></tr>";
					}).join("") + "</tbody></table></div>";
			})
			.catch(function () { $("an-admin-msg").textContent = "couldn't reach the server"; });
	}
	form.addEventListener("submit", function (e) {
		e.preventDefault();
		token(form.querySelector("input").value.trim());
		form.querySelector("input").value = "";
		raw();
	});
	raw();
})();
