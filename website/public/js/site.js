/* TROLL CLIENT HOMEPAGE - shared scripts.
   Themes, the window and its desktop, the taskbar, live stats over a WebSocket (hit counter,
   who's online, downloads, guestbook, poll), tooltips, reveals, sounds, and a few things that
   happen if you know where to look. */
(function () {
	"use strict";

	var THEMES = [
		{ id: "noir", name: "Noir", light: false, c: ["#090909", "#101010", "#ededed", "#ffffff", "#3c3c3c"] },
		{ id: "paper", name: "Paper", light: true, c: ["#e9e9e9", "#f7f7f7", "#101010", "#000000", "#9c9c9c"] },
		{ id: "graphite", name: "Graphite", light: false, c: ["#19191b", "#212123", "#d8d8d8", "#c4c4c4", "#56565b"] },
		{ id: "terminal", name: "Terminal", light: false, c: ["#000000", "#000000", "#ffffff", "#ffffff", "#ffffff"] },
		{ id: "newsprint", name: "Newsprint", light: true, c: ["#e4dfd3", "#f1ede4", "#1a1a1a", "#2a2a2a", "#7c766b"] },
		{ id: "ink", name: "Ink", light: true, c: ["#ffffff", "#ffffff", "#000000", "#000000", "#000000"] },
		{ id: "fog", name: "Fog", light: true, c: ["#7e7e7e", "#8b8b8b", "#101010", "#0a0a0a", "#555555"] }
	];
	var PAGES = [["/", "home"], ["/skin-animation", "skin animation"], ["/modules", "modules"], ["/gallery", "gallery"], ["/download", "download"], ["/guestbook", "guestbook"], ["/analytics", "analytics"]];
	var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
	var fine = window.matchMedia && window.matchMedia("(pointer: fine)").matches;

	function $(sel, root) { return (root || document).querySelector(sel); }
	function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

	function store(key, value) {
		try {
			if (value === undefined) return localStorage.getItem(key);
			if (value === null) localStorage.removeItem(key);
			else localStorage.setItem(key, value);
		} catch (e) { /* private mode: no memory, no problem */ }
		return null;
	}

	function session(key, value) {
		try {
			if (value === undefined) return sessionStorage.getItem(key);
			sessionStorage.setItem(key, value);
		} catch (e) { /* ignore */ }
		return null;
	}

	function esc(s) {
		return String(s).replace(/[&<>"']/g, function (c) {
			return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
		});
	}

	function fmt(n) { return Number(n || 0).toLocaleString("en-US"); }

	function ago(ms) {
		var s = Math.max(1, Math.round((Date.now() - ms) / 1000));
		if (s < 60) return s + "s ago";
		if (s < 3600) return Math.round(s / 60) + " min ago";
		if (s < 86400) return Math.round(s / 3600) + " h ago";
		var d = Math.round(s / 86400);
		return d < 30 ? d + " day" + (d === 1 ? "" : "s") + " ago" : new Date(ms).toISOString().slice(0, 10);
	}

	// ------------------------------------------------------------ sound: opt-in chiptune bleeps (square waves, like the video's soundtrack)
	var Sound = {
		on: store("troll-sound") === "1",
		ctx: null,
		blip: function (freq, dur, type, vol, slide) {
			if (!this.on) return;
			try {
				if (!this.ctx) this.ctx = new (window.AudioContext || window.webkitAudioContext)();
				var c = this.ctx, t = c.currentTime, o = c.createOscillator(), g = c.createGain();
				o.type = type || "square";
				o.frequency.setValueAtTime(freq, t);
				if (slide) o.frequency.exponentialRampToValueAtTime(slide, t + dur);
				g.gain.setValueAtTime(vol || 0.04, t);
				g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
				o.connect(g);
				g.connect(c.destination);
				o.start(t);
				o.stop(t + dur + 0.02);
			} catch (e) { /* no audio, no problem */ }
		},
		click: function () { this.blip(1400, 0.035, "square", 0.025); },
		tab: function () { this.blip(880, 0.05, "square", 0.025, 1320); },
		notify: function () { var s = this; s.blip(988, 0.08); setTimeout(function () { s.blip(1319, 0.12); }, 90); },
		error: function () { this.blip(140, 0.25, "square", 0.05, 90); },
		chime: function () {
			var s = this;
			[523, 659, 784, 1047].forEach(function (f, i) { setTimeout(function () { s.blip(f, 0.22, "triangle", 0.06); }, i * 110); });
		},
		down: function () { this.blip(660, 0.6, "triangle", 0.06, 80); }
	};

	function setSound(on, quiet) {
		Sound.on = on;
		store("troll-sound", on ? "1" : "0");
		var b = $("#sound-toggle");
		if (b) b.textContent = "sound: " + (on ? "on" : "off");
		var t = $("#tray-sound");
		if (t) {
			t.classList.toggle("on", on);
			t.setAttribute("data-tip", "sound: " + (on ? "on" : "off"));
		}
		if (on && !quiet) Sound.notify();
	}

	// ------------------------------------------------------------ themes
	function themeIndex(id) {
		for (var i = 0; i < THEMES.length; i++) if (THEMES[i].id === id) return i;
		return 0;
	}

	function applyTheme(t) {
		var body = document.body;
		THEMES.forEach(function (x) { body.classList.remove("theme-" + x.id); });
		body.classList.add("theme-" + t.id);
		body.classList.toggle("light", t.light);
		$$("[data-theme]").forEach(function (b) { b.classList.toggle("pressed", b.getAttribute("data-theme") === t.id); });
		var label = $("#theme-name");
		if (label) label.textContent = t.name;
		var meta = $('meta[name="theme-color"]');
		if (meta) meta.setAttribute("content", t.c[0]);
		document.dispatchEvent(new CustomEvent("troll-theme", { detail: t }));
	}

	/** Switches theme; with an origin point it wipes the new palette in as a circle from there. */
	function setTheme(id, from) {
		var t = THEMES[themeIndex(id)];
		store("troll-theme", t.id);
		var root = document.documentElement;
		if (from && document.startViewTransition && !reduced) {
			var x = from.x, y = from.y;
			root.style.setProperty("--tx", x + "px");
			root.style.setProperty("--ty", y + "px");
			root.style.setProperty("--tr", Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y)) + "px");
			root.classList.add("theme-swap");
			var vt = document.startViewTransition(function () { applyTheme(t); });
			vt.finished.then(function () { root.classList.remove("theme-swap"); }, function () { root.classList.remove("theme-swap"); });
		} else {
			applyTheme(t);
			if (from && !reduced) {
				document.body.classList.remove("flash");
				void document.body.offsetWidth;
				document.body.classList.add("flash");
			}
		}
		Sound.tab();
	}

	function cycleTheme(dir, from) {
		var cur = themeIndex(store("troll-theme") || "noir");
		setTheme(THEMES[(cur + dir + THEMES.length) % THEMES.length].id, from || { x: innerWidth - 120, y: 110 });
	}

	function pointOf(e, el) {
		if (e && e.clientX) return { x: e.clientX, y: e.clientY };
		var r = el.getBoundingClientRect();
		return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
	}

	function themeCards() {
		$$(".themes").forEach(function (box) {
			THEMES.forEach(function (t) {
				var c = t.c;
				var b = document.createElement("button");
				b.type = "button";
				b.className = "theme-card";
				b.setAttribute("data-theme", t.id);
				// a tiny window in that palette
				b.innerHTML = '<span class="mini" style="background:' + c[0] + '">' +
					'<i style="left:8px;right:8px;top:8px;height:8px;background:' + c[3] + '"></i>' +
					'<i style="left:8px;top:20px;width:26px;bottom:8px;background:' + c[1] + ';border:1px solid ' + c[4] + '"></i>' +
					'<i style="left:40px;right:8px;top:22px;height:3px;background:' + c[2] + '"></i>' +
					'<i style="left:40px;right:28px;top:30px;height:3px;background:' + c[2] + ';opacity:.6"></i>' +
					'<i style="left:40px;right:18px;top:38px;height:3px;background:' + c[2] + ';opacity:.6"></i>' +
					'</span><span class="name">' + t.name + "</span>";
				b.addEventListener("click", function (e) { setTheme(t.id, pointOf(e, b)); });
				box.appendChild(b);
			});
		});
	}

	// ------------------------------------------------------------ CRT overlay
	function setCrt(on) {
		document.body.classList.toggle("crt", on);
		store("troll-crt", on ? "1" : "0");
		var b = $("#crt-toggle");
		if (b) b.textContent = "CRT: " + (on ? "ON" : "OFF");
	}

	// ------------------------------------------------------------ tooltips: XP balloons that type themselves out
	function tooltips() {
		if (!fine) return;
		var tip = document.createElement("div");
		tip.className = "tip";
		tip.setAttribute("role", "tooltip");
		document.body.appendChild(tip);
		var timer, typer, current;
		document.addEventListener("pointerover", function (e) {
			var el = e.target.closest ? e.target.closest("[data-tip]") : null;
			if (el === current) return;
			hide();
			if (!el) return;
			current = el;
			timer = setTimeout(function () {
				var text = el.getAttribute("data-tip");
				var r = el.getBoundingClientRect();
				tip.textContent = text;
				var up = r.bottom + 60 > innerHeight;
				var left = Math.max(6, Math.min(innerWidth - 270, r.left + Math.min(r.width / 2, 24) - 16));
				tip.style.left = left + "px";
				tip.classList.toggle("up", up);
				tip.textContent = "";
				tip.classList.add("show");
				tip.style.top = (up ? r.top - 10 - 30 : r.bottom + 10) + "px";
				var i = 0;
				typer = setInterval(function () {
					i += 3;
					tip.textContent = text.slice(0, i);
					if (up) tip.style.top = (r.top - 10 - tip.offsetHeight) + "px";
					if (i >= text.length) clearInterval(typer);
				}, 14);
			}, 380);
		});
		function hide() {
			clearTimeout(timer);
			clearInterval(typer);
			current = null;
			tip.classList.remove("show");
		}
		document.addEventListener("pointerdown", hide);
		window.addEventListener("scroll", hide, { passive: true });
	}

	// ------------------------------------------------------------ balloons from the tray
	function notify(title, text, icon, ms) {
		var box = $("#balloons");
		if (!box) return;
		var b = document.createElement("div");
		b.className = "balloon";
		b.innerHTML = "<b>" + (icon ? '<img src="img/icons/' + icon + '.png" alt="">' : "") + esc(title) + "</b>" +
			(text ? "<p>" + esc(text) + "</p>" : "") + '<button class="x" type="button" aria-label="close">x</button>';
		box.appendChild(b);
		while (box.children.length > 3) box.removeChild(box.firstChild);
		Sound.notify();
		var gone = function () {
			if (!b.parentNode) return;
			b.classList.add("out");
			setTimeout(function () { b.remove(); }, 260);
		};
		b.querySelector(".x").addEventListener("click", gone);
		setTimeout(gone, ms || 6500);
		return b;
	}

	// ------------------------------------------------------------ dialogs
	function dialog(title, html, buttons) {
		var layer = document.createElement("div");
		layer.className = "dialog-layer";
		layer.innerHTML = '<div class="dialog" role="dialog" aria-modal="true" aria-label="' + esc(title) + '">' +
			'<div class="titlebar"><img class="app-icon" src="img/logo-small.png" alt=""><span class="title">' + esc(title) + "</span>" +
			'<span class="winbtns"><button class="winbtn close" type="button" data-close aria-label="Close"><svg viewBox="0 0 10 10" shape-rendering="crispEdges"><path fill="currentColor" d="M1 1h2v1h1v1h2V2h1V1h2v2H8v1H7v2h1v1h1v2H7V8H6V7H4v1H3v1H1V7h1V6h1V4H2V3H1z"/></svg></button></span></div>' +
			'<div class="body">' + html + '<div class="btns"></div></div></div>';
		var btns = layer.querySelector(".btns");
		var close = function () {
			layer.remove();
			document.removeEventListener("keydown", onKey);
		};
		(buttons || [{ label: "OK" }]).forEach(function (spec, i) {
			var b = document.createElement("button");
			b.type = "button";
			b.className = "button" + (spec.primary ? " primary" : "") + (spec.cls ? " " + spec.cls : "");
			b.textContent = spec.label;
			b.addEventListener("click", function () {
				if (spec.action && spec.action(close, b) === false) return;
				close();
			});
			btns.appendChild(b);
			if (i === 0) setTimeout(function () { b.focus(); }, 30);
		});
		layer.querySelector("[data-close]").addEventListener("click", close);
		layer.addEventListener("pointerdown", function (e) {
			if (e.target !== layer) return;
			var d = layer.querySelector(".dialog");
			d.classList.remove("shake");
			void d.offsetWidth;
			d.classList.add("shake");
			Sound.error();
		});
		function onKey(e) { if (e.key === "Escape") close(); }
		document.addEventListener("keydown", onKey);
		document.body.appendChild(layer);
		return { layer: layer, close: close, el: layer.querySelector(".dialog") };
	}

	// ------------------------------------------------------------ the browser window: drag (it springs back), min, max, close
	function windowControls() {
		var win = $("#browser"), bar = $("#titlebar");
		if (!win || !bar) return;
		var drag = null, pos = { x: 0, y: 0, vx: 0, vy: 0 }, springing = false;

		function place(tilt) {
			win.style.transform = pos.x || pos.y || tilt ? "translate(" + pos.x.toFixed(1) + "px," + pos.y.toFixed(1) + "px) rotate(" + (tilt || 0).toFixed(2) + "deg)" : "";
		}
		bar.addEventListener("pointerdown", function (e) {
			if (e.target.closest("button") || innerWidth < 900 || e.button !== 0) return;
			drag = { x: e.clientX - pos.x, y: e.clientY - pos.y, lx: e.clientX, t: performance.now() };
			springing = false;
			bar.setPointerCapture(e.pointerId);
		});
		bar.addEventListener("pointermove", function (e) {
			if (!drag) return;
			var now = performance.now();
			pos.vx = (e.clientX - drag.lx) / Math.max(1, now - drag.t) * 16;
			drag.lx = e.clientX;
			drag.t = now;
			// rubber band: the further you pull, the harder it pulls back
			var dx = e.clientX - drag.x, dy = e.clientY - drag.y;
			pos.x = dx / (1 + Math.abs(dx) / 500);
			pos.y = dy / (1 + Math.abs(dy) / 400);
			place(Math.max(-3, Math.min(3, pos.vx * 0.25)));
		});
		function release() {
			if (!drag) return;
			drag = null;
			// and back it goes, wobbling like it's 2007 and you just installed Compiz
			springing = true;
			var last = performance.now();
			(function step(now) {
				if (!springing) return;
				var dt = Math.min(0.032, (now - last) / 1000);
				last = now;
				pos.vx += (-pos.x * 180 - pos.vx * 12) * dt;
				pos.vy += (-pos.y * 180 - pos.vy * 12) * dt;
				pos.x += pos.vx * dt;
				pos.y += pos.vy * dt;
				if (Math.abs(pos.x) < 0.3 && Math.abs(pos.y) < 0.3 && Math.abs(pos.vx) < 2 && Math.abs(pos.vy) < 2) {
					pos.x = pos.y = pos.vx = pos.vy = 0;
					springing = false;
					place(0);
					return;
				}
				place(Math.max(-2, Math.min(2, pos.vx * 0.01)));
				requestAnimationFrame(step);
			})(last);
		}
		bar.addEventListener("pointerup", release);
		bar.addEventListener("pointercancel", release);
		bar.addEventListener("dblclick", function (e) {
			if (e.target.closest("button")) return;
			toggleMax();
		});

		function toggleMax() {
			win.classList.toggle("maxed");
			store("troll-max", win.classList.contains("maxed") ? "1" : "0");
		}
		if (store("troll-max") === "1") win.classList.add("maxed");

		var task = $("#task");
		function minimize() {
			if (!task || getComputedStyle($("#taskbar")).display === "none") {
				win.animate([{ transform: "translateY(0)" }, { transform: "translateY(12px)" }, { transform: "translateY(0)" }], { duration: 300, easing: "ease-out" });
				notify("Nowhere to minimize to", "This screen doesn't have a taskbar. You're stuck with us.", "close");
				return;
			}
			var w = win.getBoundingClientRect(), t = task.getBoundingClientRect();
			var dx = t.left + t.width / 2 - (w.left + w.width / 2), dy = t.top + t.height / 2 - (w.top + Math.min(w.height, innerHeight) / 2);
			win.style.transformOrigin = "50% " + Math.min(w.height, innerHeight) / 2 + "px";
			win.animate([
				{ transform: "none", opacity: 1, filter: "none" },
				{ transform: "translate(" + dx * 0.4 + "px," + dy * 0.6 + "px) scale(0.6, 0.35) skewX(" + (dx > 0 ? -8 : 8) + "deg)", opacity: 0.8, filter: "blur(1px)" },
				{ transform: "translate(" + dx + "px," + dy + "px) scale(0.04)", opacity: 0, filter: "blur(2px)" }
			], { duration: reduced ? 1 : 420, easing: "cubic-bezier(.5,0,.7,.4)", fill: "forwards" });
			win.classList.add("minimized");
			task.classList.remove("on");
			Sound.blip(600, 0.15, "triangle", 0.05, 200);
		}
		function restore() {
			win.getAnimations().forEach(function (a) { a.cancel(); });
			win.classList.remove("minimized");
			var w = win.getBoundingClientRect(), t = task.getBoundingClientRect();
			var dx = t.left + t.width / 2 - (w.left + w.width / 2), dy = t.top + t.height / 2 - (w.top + Math.min(w.height, innerHeight) / 2);
			win.animate([
				{ transform: "translate(" + dx + "px," + dy + "px) scale(0.04)", opacity: 0 },
				{ transform: "none", opacity: 1 }
			], { duration: reduced ? 1 : 380, easing: "cubic-bezier(.2,.9,.3,1.1)" });
			task.classList.add("on");
			Sound.blip(200, 0.15, "triangle", 0.05, 600);
		}
		if (task) task.addEventListener("click", function () {
			if (win.classList.contains("minimized")) restore();
			else minimize();
		});

		function closeDialog() {
			var d = dialog("Troll Navigator", '<div class="row"><img src="img/icons/skull.png" alt=""><span>Are you sure you want to leave Troll Client?</span></div>', [
				{ label: "Yes", cls: "runaway", action: function () { shutdown(); } },
				{ label: "No", primary: true }
			]);
			Sound.error();
			// the Yes button would really rather you didn't
			var yes = d.el.querySelector(".runaway"), dodges = 0;
			d.el.addEventListener("pointermove", function (e) {
				var r = yes.getBoundingClientRect();
				var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
				if (Math.hypot(e.clientX - cx, e.clientY - cy) > 70 || dodges > 12) return;
				dodges++;
				var box = d.el.getBoundingClientRect();
				var nx = (Math.random() - 0.5) * (box.width - 120), ny = -Math.random() * (box.height - 80);
				yes.style.transform = "translate(" + nx + "px," + ny + "px)";
				if (dodges === 12) yes.textContent = "fine. yes.";
			});
		}
		$$("[data-win]").forEach(function (b) {
			b.addEventListener("click", function () {
				var what = b.getAttribute("data-win");
				if (what === "min") minimize();
				else if (what === "max") toggleMax();
				else closeDialog();
			});
		});
	}

	// ------------------------------------------------------------ shut down / power on (CRT style)
	function crt(kind, done) {
		var el = document.createElement("div");
		el.className = "crt-power " + kind;
		el.innerHTML = "<i></i>";
		document.body.appendChild(el);
		setTimeout(function () {
			if (kind === "on") el.remove();
			if (done) done(el);
		}, kind === "off" ? 760 : 900);
	}

	function shutdown() {
		var menu = $("#startmenu");
		if (menu) menu.hidden = true;
		Sound.down();
		crt("off", function (overlay) {
			var screen = document.createElement("div");
			screen.className = "safe-off";
			screen.innerHTML = "<div>It's now safe to turn off<br>your computer.<small>(click anywhere to turn it back on)</small></div>";
			document.body.appendChild(screen);
			overlay.remove();
			var wake = function () {
				screen.remove();
				document.removeEventListener("keydown", wake);
				crt("on");
				Sound.chime();
			};
			screen.addEventListener("click", wake);
			document.addEventListener("keydown", wake);
		});
	}

	// ------------------------------------------------------------ boot screen, once per visit
	function boot(then) {
		var done = session("troll-booted") === "1";
		if (done || reduced) { then(false); return; }
		session("troll-booted", "1");
		var el = document.createElement("div");
		el.className = "boot";
		el.setAttribute("aria-hidden", "true");
		document.body.appendChild(el);
		var lines = [
			"TROLL BIOS v2.0  (C) NOBODY IN PARTICULAR",
			"",
			"MEMORY TEST ........ 524288K OK",
			"DETECTING TROLL NAVIGATOR ... OK",
			"LOADING 35 MODULES ......... OK",
			"LOADING SKIN ANIMATION ..... OK  (NEW!)",
			"CONNECTING TO WORKER ....... OK",
			""
		];
		var text = "", li = 0, ci = 0, finished = false;
		var timer = setInterval(function () {
			if (li >= lines.length) {
				clearInterval(timer);
				el.innerHTML = esc(text) + 'STARTING <span class="bar"><i></i></span>\n\n(click to skip)';
				var bar = el.querySelector(".bar i"), p = 0;
				timer = setInterval(function () {
					p += 9;
					bar.style.width = Math.min(100, p) + "%";
					if (p >= 100) finish();
				}, 30);
				return;
			}
			var line = lines[li];
			ci += 5;
			el.textContent = text + line.slice(0, ci) + "_";
			if (ci >= line.length) { text += line + "\n"; li++; ci = 0; }
		}, 14);
		function finish() {
			if (finished) return;
			finished = true;
			clearInterval(timer);
			el.classList.add("done");
			setTimeout(function () { el.remove(); }, 300);
			crt("on");
			Sound.chime();
			then(true);
		}
		el.addEventListener("click", finish);
		document.addEventListener("keydown", function k() { finish(); document.removeEventListener("keydown", k); });
	}

	// ------------------------------------------------------------ the address bar, status bar and loading bits
	function chromeBits() {
		var url = $("#url"), address = $("#address"), status = $("#status"), throbber = $("#throbber");
		if (url) {
			var full = location.host + location.pathname + location.search;
			var proto = location.protocol + "//";
			if (location.protocol === "https:" && $("#lock")) $("#lock").hidden = false;
			if (reduced) {
				url.innerHTML = '<span class="proto">' + esc(proto) + "</span>" + esc(full);
			} else {
				address.classList.add("typing");
				var i = 0;
				var t = setInterval(function () {
					i++;
					url.innerHTML = '<span class="proto">' + esc(proto) + "</span>" + esc(full.slice(0, i));
					if (i >= full.length) { clearInterval(t); setTimeout(function () { address.classList.remove("typing"); }, 900); }
				}, 22);
			}
		}
		if (status) {
			var steps = ["Opening page " + location.href + "...", "Waiting for " + location.host + "...", "Transferring data from " + location.host + "...", "Done"];
			steps.forEach(function (s, i) { setTimeout(function () { if (!status.dataset.hover) status.textContent = s; }, reduced ? 0 : i * 260); });
			// like a real browser: hover a link, see where it goes
			document.addEventListener("mouseover", function (e) {
				var a = e.target.closest && e.target.closest("a[href]");
				if (!a) return;
				status.dataset.hover = "1";
				status.textContent = a.href.indexOf("javascript:") === 0 ? "Back" : a.href;
			});
			document.addEventListener("mouseout", function (e) {
				var a = e.target.closest && e.target.closest("a[href]");
				if (!a) return;
				delete status.dataset.hover;
				status.textContent = "Done";
			});
		}
		// clicking a link: the loading bar fills, the throbber spins up
		document.addEventListener("click", function (e) {
			var a = e.target.closest && e.target.closest("a[href]");
			if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
			if (a.target === "_blank" || a.hasAttribute("download") || a.origin !== location.origin) return;
			if (a.pathname === location.pathname && a.hash) return;
			if (address) {
				address.classList.add("loading");
				var load = address.querySelector(".load");
				load.style.width = "35%";
				setTimeout(function () { load.style.width = "85%"; }, 120);
			}
			if (throbber) throbber.classList.add("fast");
			if (status) status.textContent = "Opening page " + a.href + "...";
		});
		window.addEventListener("pageshow", function () {
			if (address) { address.classList.remove("loading"); address.querySelector(".load").style.width = "0"; }
			if (throbber) throbber.classList.remove("fast");
		});
		var star = $(".address .star");
		if (star) star.addEventListener("click", function () {
			star.classList.toggle("on");
			star.innerHTML = star.classList.contains("on") ? "&#9733;" : "&#9734;";
			if (star.classList.contains("on")) notify("Bookmarked! (sort of)", "Press " + (/Mac/.test(navigator.platform) ? "Cmd" : "Ctrl") + "+D to really bookmark it. Browsers stopped letting websites do that around 2006.", "star");
		});
		$$("[data-nav]").forEach(function (b) {
			b.addEventListener("click", function () {
				var what = b.getAttribute("data-nav");
				if (what === "back") history.back();
				else if (what === "forward") history.forward();
				else {
					b.classList.add("spinning");
					setTimeout(function () { location.reload(); }, reduced ? 0 : 350);
				}
			});
		});
		var plus = $(".tab-new");
		if (plus) plus.addEventListener("click", function () {
			var others = PAGES.filter(function (p) { return p[0] !== location.pathname; });
			location.href = others[Math.floor(Math.random() * others.length)][0];
		});
		// the tab labels decrypt out of noise when you hover them, like the title screen's menu
		$$(".tab .lbl").forEach(function (lbl) {
			var text = lbl.textContent, timer;
			lbl.parentNode.addEventListener("mouseenter", function () {
				if (reduced) return;
				var n = 0;
				clearInterval(timer);
				timer = setInterval(function () {
					n++;
					lbl.textContent = text.split("").map(function (c, i) {
						return c === " " || i < n / 2 ? c : "#%&*+=?01<>/"[Math.floor(Math.random() * 12)];
					}).join("");
					if (n / 2 >= text.length) { clearInterval(timer); lbl.textContent = text; }
				}, 22);
			});
		});
		var meter = $("#scroll-meter"), top = $("#to-top"), ticking = false;
		function onScroll() {
			ticking = false;
			var max = document.documentElement.scrollHeight - innerHeight;
			var p = max > 0 ? scrollY / max : 0;
			if (meter) meter.style.width = (p * 100).toFixed(1) + "%";
			if (top) top.classList.toggle("show", scrollY > 700);
		}
		window.addEventListener("scroll", function () {
			if (!ticking) { ticking = true; requestAnimationFrame(onScroll); }
		}, { passive: true });
		onScroll();
		if (top) top.addEventListener("click", function () {
			top.classList.add("launch");
			Sound.blip(200, 0.7, "sawtooth", 0.03, 1600);
			scrollTo({ top: 0, behavior: reduced ? "auto" : "smooth" });
			setTimeout(function () { top.classList.remove("launch", "show"); }, 1000);
		});
		// the ticker needs a second copy of itself to loop without a seam
		var track = $(".ticker-track");
		if (track && track.children.length === 1) {
			var copy = track.firstElementChild.cloneNode(true);
			copy.setAttribute("aria-hidden", "true");
			track.appendChild(copy);
		}
	}

	// ------------------------------------------------------------ the taskbar
	function taskbar() {
		var clock = $("#clock");
		if (clock) {
			var tick = function () {
				var d = new Date();
				clock.innerHTML = String(d.getHours()).padStart(2, "0") + '<span class="colon">:</span>' + String(d.getMinutes()).padStart(2, "0");
				clock.setAttribute("data-tip", d.toDateString());
			};
			tick();
			setInterval(tick, 10000);
		}
		var start = $("#start"), menu = $("#startmenu");
		if (start && menu) {
			var head = $(".sm-avatar", menu);
			if (head && window.TrollSkin) {
				var c = document.createElement("canvas");
				head.appendChild(c);
				window.TrollSkin.head("troll", c);
			}
			var toggle = function (open) {
				menu.hidden = !open;
				start.setAttribute("aria-expanded", open ? "true" : "false");
				if (open) Sound.tab();
			};
			start.addEventListener("click", function (e) { e.stopPropagation(); toggle(menu.hidden); });
			document.addEventListener("click", function (e) { if (!menu.hidden && !menu.contains(e.target)) toggle(false); });
			document.addEventListener("keydown", function (e) { if (e.key === "Escape" && !menu.hidden) toggle(false); });
			$$("[data-sm]", menu).forEach(function (b) {
				b.addEventListener("click", function (e) {
					var what = b.getAttribute("data-sm");
					if (what === "theme") { cycleTheme(1, pointOf(e, b)); return; }
					toggle(false);
					if (what === "crt") setCrt(!document.body.classList.contains("crt"));
					else if (what === "sound") setSound(!Sound.on);
					else if (what === "trail") {
						var on = store("troll-trail") === "0";
						store("troll-trail", on ? "1" : "0");
						notify("Cursor trail " + (on ? "on" : "off"), on ? "Sparkles! Very 2004." : "Fine. No sparkles.", "star");
					} else if (what === "twerk") twerk();
					else if (what === "logoff") notify("Can't log off", "You live here now.", "close");
					else if (what === "shutdown") shutdown();
				});
			});
		}
		var traySound = $("#tray-sound");
		if (traySound) traySound.addEventListener("click", function () { setSound(!Sound.on); });
	}

	function twerk() {
		var on = document.body.classList.toggle("twerk");
		if (on) notify("twerk.exe is running", "Run it again to make it stop. Or don't.", "troll");
	}

	// ------------------------------------------------------------ desktop: a 1-bit dithered hill, clouds drifting by
	function wallpaper() {
		var c = $("#wallpaper");
		if (!c) return;
		var g = c.getContext("2d");
		var BAYER = [0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26, 12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
			3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25, 15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21];
		var PX = 3, W = 0, H = 0, img, buf, hill, cloud, paper = 0, ink = 0, light = false, last = 0;
		var clouds = [];
		for (var i = 0; i < 6; i++) {
			clouds.push({ x: Math.random(), y: 0.08 + Math.random() * 0.34, s: 0.6 + Math.random() * 0.8, v: 0.004 + Math.random() * 0.006, seed: Math.random() * 10 });
		}
		function rgba(hex) {
			hex = hex.trim().replace("#", "");
			if (hex.length === 3) hex = hex.replace(/./g, "$&$&");
			var n = parseInt(hex, 16);
			return (255 << 24 | (n & 255) << 16 | (n >> 8 & 255) << 8 | n >> 16) >>> 0;
		}
		function colors() {
			var cs = getComputedStyle(document.body);
			paper = rgba(cs.getPropertyValue("--desk") || "#060606");
			ink = rgba(cs.getPropertyValue("--desk-ink") || "#222222");
			light = document.body.classList.contains("light");
			draw(performance.now(), true);
		}
		function resize() {
			W = Math.max(1, Math.ceil(innerWidth / PX));
			H = Math.max(1, Math.ceil(innerHeight / PX));
			c.width = W;
			c.height = H;
			img = g.createImageData(W, H);
			buf = new Uint32Array(img.data.buffer);
			cloud = new Float32Array(W * H);
			hill = new Float32Array(W);
			for (var x = 0; x < W; x++) {
				var u = x / W;
				hill[x] = H * (0.64 + 0.075 * Math.sin(u * 3.0 + 0.5) + 0.03 * Math.sin(u * 8.2 + 1.7));
			}
			draw(performance.now(), true);
		}
		function draw(now, force) {
			if (!W || (!force && now - last < 125)) return;
			last = now;
			var t = now / 1000;
			cloud.fill(0);
			clouds.forEach(function (cl) {
				var cx = (((cl.x + t * cl.v * (reduced ? 0 : 1)) % 1.3) - 0.15) * W, cy = cl.y * H;
				for (var k = 0; k < 5; k++) {
					var bx = cx + (k - 2) * 14 * cl.s, by = cy - Math.sin(k * 1.3 + cl.seed) * 5 * cl.s - (k === 2 ? 5 * cl.s : 0);
					var rx = (16 + 6 * Math.sin(k + cl.seed)) * cl.s, ry = rx * 0.55;
					var x0 = Math.max(0, Math.floor(bx - rx)), x1 = Math.min(W - 1, Math.ceil(bx + rx));
					var y0 = Math.max(0, Math.floor(by - ry)), y1 = Math.min(H - 1, Math.ceil(by + ry));
					for (var y = y0; y <= y1; y++) {
						var dy = (y - by) / ry;
						for (var x = x0; x <= x1; x++) {
							var dx = (x - bx) / rx, d = 1 - (dx * dx + dy * dy);
							if (d > 0) { var o = y * W + x; if (d > cloud[o]) cloud[o] = d; }
						}
					}
				}
			});
			for (var y = 0; y < H; y++) {
				var row = (y & 7) << 3, off = y * W;
				for (var x = 0; x < W; x++) {
					var hy = hill[x], L;
					if (y > hy) {
						var depth = (y - hy) / (H - hy);
						L = y - hy < 1.5 ? 0.7 : 0.5 - depth * 0.32 + 0.06 * Math.sin(x * 0.05 + depth * 6);
					} else {
						L = 0.3 + 0.42 * Math.pow(y / hy, 1.4) + Math.min(1, cloud[off + x] * 1.6) * 0.55;
					}
					var thr = (BAYER[row + (x & 7)] + 0.5) / 64;
					buf[off + x] = (light ? L < thr : L > thr) ? ink : paper;
				}
			}
			g.putImageData(img, 0, 0);
		}
		function loop(now) {
			if (!document.hidden && innerWidth >= 900) draw(now);
			requestAnimationFrame(loop);
		}
		window.addEventListener("resize", resize);
		document.addEventListener("troll-theme", colors);
		resize();
		colors();
		if (!reduced) requestAnimationFrame(loop);
	}

	function desktopIcons() {
		var icons = $$(".desk-icon");
		if (!icons.length) return;
		var GX = 96, GY = 92;
		function open(icon) {
			var where = icon.getAttribute("data-open"), act = icon.getAttribute("data-act");
			Sound.click();
			if (where) location.href = where;
			else if (act === "twerk") twerk();
			else if (act === "bin") recycleBin();
		}
		icons.forEach(function (icon) {
			var drag = null;
			icon.addEventListener("pointerdown", function (e) {
				icons.forEach(function (o) { o.classList.toggle("sel", o === icon); });
				var r = icon.getBoundingClientRect(), p = icon.offsetParent.getBoundingClientRect();
				drag = { dx: e.clientX - r.left, dy: e.clientY - r.top, px: p.left, py: p.top, sx: e.clientX, sy: e.clientY, moved: false };
				icon.setPointerCapture(e.pointerId);
			});
			icon.addEventListener("pointermove", function (e) {
				if (!drag) return;
				if (!drag.moved && Math.hypot(e.clientX - drag.sx, e.clientY - drag.sy) < 5) return;
				drag.moved = true;
				icon.classList.add("dragging");
				icon.classList.remove("snap");
				icon.style.left = e.clientX - drag.px - drag.dx + "px";
				icon.style.top = e.clientY - drag.py - drag.dy + "px";
			});
			icon.addEventListener("pointerup", function () {
				if (!drag) return;
				if (drag.moved) {
					// snap to the icon grid
					var gx = Math.max(0, Math.round(parseFloat(icon.style.left) / GX)), gy = Math.max(0, Math.round(parseFloat(icon.style.top) / GY));
					icon.classList.remove("dragging");
					icon.classList.add("snap");
					icon.style.left = gx * GX + "px";
					icon.style.top = gy * GY + "px";
				}
				drag = null;
			});
			icon.addEventListener("dblclick", function () { open(icon); });
			icon.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); open(icon); } });
		});
		document.addEventListener("pointerdown", function (e) {
			if (!e.target.closest(".desk-icon")) icons.forEach(function (o) { o.classList.remove("sel"); });
		});
	}

	function recycleBin() {
		var items = ["anticheat.dll", "server_rules.txt", "your opponents' hopes and dreams", "colour.png"];
		var d = dialog("Recycle Bin", '<div class="row"><svg viewBox="0 0 16 16" width="32" height="32" shape-rendering="crispEdges"><path fill="currentColor" d="M5 1h6v1h4v2H1V2h4zM2 5h12v10H2zm2 2v6h1V7zm3 0v6h2V7zm4 0v6h1V7z"/></svg><span>' + items.length + " items</span></div>" +
			'<ul class="bin-list" style="margin:0;padding-left:20px;font:11px/1.8 Verdana,sans-serif">' + items.map(function (i) { return "<li>" + esc(i) + "</li>"; }).join("") + "</ul>", [
			{ label: "Empty Recycle Bin", primary: true, action: function (close, btn) {
				var lis = $$(".bin-list li", d.el);
				if (!lis.length) return true;
				lis.forEach(function (li, i) {
					li.animate([{ opacity: 1, transform: "none" }, { opacity: 0, transform: "translateX(40px) rotate(8deg) scale(0.6)" }], { duration: 300, delay: i * 90, fill: "forwards" });
				});
				Sound.blip(300, 0.4, "sawtooth", 0.03, 60);
				setTimeout(function () { $(".row span", d.el).textContent = "0 items. it's empty. like a server after twerk turns on."; btn.textContent = "OK"; }, 300 + lis.length * 90);
				lis.forEach(function (li) { setTimeout(function () { li.remove(); }, 300 + lis.length * 90); });
				return false;
			} },
			{ label: "Close" }
		]);
	}

	// ------------------------------------------------------------ reveals, counters, typing, tilt
	function reveals() {
		var els = $$("[data-reveal]");
		$$("h2").forEach(function (h) { if (!h.hasAttribute("data-reveal")) els.push(h); });
		if (!("IntersectionObserver" in window) || reduced) {
			els.forEach(function (el) { el.classList.add("in"); });
			$$(".changelog .line").forEach(function (l) { l.classList.add("on"); });
			return;
		}
		var io = new IntersectionObserver(function (entries) {
			entries.forEach(function (en) {
				if (!en.isIntersecting) return;
				var el = en.target;
				io.unobserve(el);
				el.classList.add("in");
				$$("[data-count]", el).forEach(countUp);
				if (el.classList.contains("changelog")) typeLines(el);
			});
		}, { rootMargin: "0px 0px -40px 0px" });
		els.forEach(function (el) { io.observe(el); });
	}

	function countUp(el) {
		var to = parseInt(el.getAttribute("data-count"), 10), t0 = performance.now();
		(function step(now) {
			var p = Math.min(1, (now - t0) / 900);
			el.textContent = Math.round(to * (1 - Math.pow(1 - p, 3)));
			if (p < 1) requestAnimationFrame(step);
		})(t0);
	}

	function typeLines(box) {
		$$(".line", box).forEach(function (l, i) { setTimeout(function () { l.classList.add("on"); Sound.blip(2200, 0.01, "square", 0.01); }, 140 * i); });
	}

	function typewriters() {
		$$("[data-type]").forEach(function (el) {
			var text = el.getAttribute("data-type");
			if (reduced) { el.textContent = text; return; }
			var i = 0;
			el.textContent = "";
			var t = setInterval(function () {
				i += 2;
				el.textContent = text.slice(0, i);
				if (i >= text.length) clearInterval(t);
			}, 24);
		});
	}

	function letters() {
		$$("[data-letters]").forEach(function (el) {
			var text = el.textContent;
			el.innerHTML = text.split("").map(function (c, i) {
				return c === " " ? " " : '<span style="--i:' + i + '" aria-hidden="true">' + esc(c) + "</span>";
			}).join("");
			if (!el.getAttribute("aria-label")) el.setAttribute("aria-label", text);
		});
	}

	function tilt() {
		if (!fine || reduced) return;
		$$("[data-tilt]").forEach(function (card) {
			card.addEventListener("pointermove", function (e) {
				var r = card.getBoundingClientRect();
				var px = (e.clientX - r.left) / r.width, py = (e.clientY - r.top) / r.height;
				card.classList.add("tilting");
				card.style.setProperty("--ry", ((px - 0.5) * 10).toFixed(2) + "deg");
				card.style.setProperty("--rx", ((0.5 - py) * 10).toFixed(2) + "deg");
				card.style.setProperty("--gx", (px * 100).toFixed(1) + "%");
				card.style.setProperty("--gy", (py * 100).toFixed(1) + "%");
			});
			card.addEventListener("pointerleave", function () {
				card.classList.remove("tilting");
				card.style.setProperty("--rx", "0deg");
				card.style.setProperty("--ry", "0deg");
			});
		});
	}

	// ------------------------------------------------------------ cursor sparkle trail
	function trail() {
		if (!fine || reduced) return;
		var last = 0;
		document.addEventListener("mousemove", function (e) {
			var now = performance.now();
			if (store("troll-trail") === "0" || now - last < 45 || e.clientX > innerWidth - 24 || e.clientY > innerHeight - 40) return;
			last = now;
			var s = document.createElement("i");
			s.className = "trail";
			s.style.left = e.clientX + 8 + "px";
			s.style.top = e.clientY + 14 + "px";
			s.style.setProperty("--dx", (Math.random() * 18 - 9) + "px");
			document.body.appendChild(s);
			setTimeout(function () { s.remove(); }, 900);
		});
	}

	// ------------------------------------------------------------ live: counter, online, downloads, guestbook, poll
	var Live = {
		stats: null,
		ws: null,
		ok: false,
		ownHit: 0,
		ownDownload: 0,
		retry: 1000,
		listeners: [],
		pending: {},

		start: function () {
			var self = this;
			var fresh = session("troll-visit") !== "1";
			session("troll-visit", "1");
			this.ownHit = Date.now();
			fetch("/api/hit", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ fresh: fresh }) })
				.then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
				.then(function (d) {
					self.ok = true;
					if (d.counted && !store("troll-visitor")) store("troll-visitor", String(d.stats.visits));
					self.render(d.stats);
					self.you(d.counted);
					self.connect();
				})
				.catch(function () { self.offline(); });
			setInterval(function () { self.flush(false); }, 8000);
			window.addEventListener("pagehide", function () { self.flush(true); });
		},

		/** No server (a plain static host): fall back to a counter that only knows about you. */
		offline: function () {
			var hits = parseInt(store("troll-hits") || "0", 10) + 1;
			store("troll-hits", String(hits));
			this.render({ visits: 1337 + hits, views: 4200 + hits, online: 1, today: 1, downloads: 0, guestbook: 1, toggles: 0, blinks: 0, days: [] });
			$$(".live-dot").forEach(function (d) { d.classList.add("off"); d.setAttribute("data-tip", "offline: this copy of the site has no server"); });
		},

		connect: function () {
			var self = this;
			if (!("WebSocket" in window)) return;
			try {
				this.ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/api/live");
			} catch (e) { return; }
			var ping;
			this.ws.onopen = function () {
				self.retry = 1000;
				$$(".live-dot").forEach(function (d) { d.classList.remove("off"); });
				ping = setInterval(function () { try { self.ws.send("ping"); } catch (e) { /* closed */ } }, 25000);
			};
			this.ws.onmessage = function (e) {
				if (e.data === "pong") return;
				var msg;
				try { msg = JSON.parse(e.data); } catch (err) { return; }
				self.handle(msg);
			};
			this.ws.onclose = function () {
				clearInterval(ping);
				$$(".live-dot").forEach(function (d) { d.classList.add("off"); });
				// come back when the tab does, backing off while the server's away
				var again = function () {
					if (document.hidden) { document.addEventListener("visibilitychange", function v() { document.removeEventListener("visibilitychange", v); again(); }); return; }
					setTimeout(function () { self.connect(); }, self.retry);
					self.retry = Math.min(30000, self.retry * 2);
				};
				again();
			};
		},

		handle: function (msg) {
			if (msg.type === "stats") this.render(msg.stats);
			else if (msg.type === "visit" && Date.now() - this.ownHit > 3000) this.plusOne();
			else if (msg.type === "download" && Date.now() - this.ownDownload > 10000) notify("Someone just downloaded Troll Client", "That's " + fmt(msg.downloads) + " downloads now.", "cube");
			else if (msg.type === "guestbook" && msg.entry) {
				if (!this.mine(msg.entry.id)) notify("New guestbook entry", msg.entry.name + ": " + msg.entry.msg.slice(0, 70), "chat");
				this.latest(msg.entry);
			}
			this.listeners.forEach(function (fn) { fn(msg); });
		},

		mine: function (id) { return (session("troll-signed") || "").split(",").indexOf(String(id)) >= 0; },

		render: function (s) {
			if (!s) return;
			var prev = this.stats;
			this.stats = s;
			$$("[data-stat]").forEach(function (el) {
				var key = el.getAttribute("data-stat");
				if (!(key in s)) return;
				if (el.classList.contains("odometer")) { odometer(el, s[key]); return; }
				var from = prev ? prev[key] : null;
				if (from === s[key] && el.textContent) return;
				if (from != null && from !== s[key] && !reduced && !document.hidden) {
					animateNumber(el, from, s[key]);
					if (el.tagName === "DD") { el.classList.remove("flash"); void el.offsetWidth; el.classList.add("flash"); }
				} else {
					el.textContent = fmt(s[key]);
				}
			});
			spark(s.days || []);
			if (s.latest) this.latest(s.latest);
		},

		latest: function (e) {
			var box = $("#latest-entry");
			if (box && e && e.name) box.textContent = "last signed by " + e.name + ", " + ago(e.at);
		},

		you: function (counted) {
			var box = $("#you-were");
			if (!box) return;
			var n = store("troll-visitor");
			box.textContent = counted && n && parseInt(n, 10) === this.stats.visits ? "that's you! welcome." : n ? "you were visitor #" + fmt(n) : "";
		},

		plusOne: function () {
			var odo = $(".odometer");
			if (!odo || reduced) return;
			odo.classList.remove("bump");
			void odo.offsetWidth;
			odo.classList.add("bump");
			var p = document.createElement("span");
			p.className = "plus-one";
			p.textContent = "+1";
			var r = odo.getBoundingClientRect();
			p.style.left = r.right - 18 + scrollX + "px";
			p.style.top = r.top - 4 + scrollY + "px";
			p.style.position = "absolute";
			document.body.appendChild(p);
			setTimeout(function () { p.remove(); }, 1400);
		},

		on: function (fn) { this.listeners.push(fn); },

		/** Little global tallies (menu toggles, skin blinks), batched so they cost one request every few seconds. */
		bump: function (key, n) { this.pending[key] = (this.pending[key] || 0) + (n || 1); },

		flush: function (leaving) {
			if (!this.ok) return;
			for (var key in this.pending) {
				var n = this.pending[key];
				if (!n) continue;
				this.pending[key] = 0;
				fetch("/api/bump", { method: "POST", keepalive: leaving, headers: { "content-type": "application/json" }, body: JSON.stringify({ key: key, n: n }) }).catch(function () { /* oh well */ });
			}
		}
	};

	function animateNumber(el, from, to) {
		var t0 = performance.now();
		(function step(now) {
			var p = Math.min(1, (now - t0) / 700);
			el.textContent = fmt(Math.round(from + (to - from) * (1 - Math.pow(1 - p, 3))));
			if (p < 1) requestAnimationFrame(step);
		})(t0);
	}

	/** A mechanical counter: every digit is a strip that rolls (forwards, always) to its new number. */
	function odometer(el, value) {
		var digits = parseInt(el.getAttribute("data-digits") || "7", 10);
		var text = String(Math.max(0, value)).padStart(digits, "0");
		if (!el.odo || el.odo.length !== text.length) {
			el.innerHTML = "";
			el.odo = text.split("").map(function () {
				var d = document.createElement("span");
				d.className = "odo-digit";
				var strip = document.createElement("span");
				strip.className = "odo-strip";
				strip.innerHTML = "0123456789012345678901234567890".split("").map(function (n) { return "<span>" + n + "</span>"; }).join("");
				d.appendChild(strip);
				el.appendChild(d);
				return { strip: strip, pos: 0 };
			});
		}
		el.setAttribute("aria-label", "visitor counter: " + value);
		text.split("").forEach(function (ch, i) {
			var o = el.odo[i], want = parseInt(ch, 10);
			var cur = o.pos % 10, steps = (want - cur + 10) % 10;
			if (!steps) return;
			if (o.pos + steps > 29) {
				// updates came faster than the strip could wind back: jump to the first ten
				o.strip.style.transition = "none";
				o.pos = cur;
				o.strip.style.transform = "translateY(" + -o.pos * 26 + "px)";
				void o.strip.offsetWidth;
				o.strip.style.transition = "";
			}
			o.pos += steps;
			o.strip.style.transitionDelay = (text.length - 1 - i) * 60 + "ms";
			o.strip.style.transform = "translateY(" + -o.pos * 26 + "px)";
			if (o.pos >= 20) {
				// quietly wind the strip back to the first ten once it's done rolling
				setTimeout(function () {
					o.strip.style.transition = "none";
					o.pos = o.pos % 10;
					o.strip.style.transform = "translateY(" + -o.pos * 26 + "px)";
					void o.strip.offsetWidth;
					o.strip.style.transition = "";
				}, 1300 + (text.length - i) * 60);
			}
		});
	}

	function spark(days) {
		var box = $("#spark");
		if (!box) return;
		var map = {};
		days.forEach(function (d) { map[d.day] = d.visits; });
		var out = [], max = 1;
		for (var i = 13; i >= 0; i--) {
			var day = new Date(Date.now() - i * 86400000).toISOString().slice(0, 10);
			var v = map[day] || 0;
			max = Math.max(max, v);
			out.push([day, v]);
		}
		var key = out.map(function (o) { return o[1]; }).join(",");
		if (box.dataset.key === key) return;
		box.dataset.key = key;
		box.innerHTML = out.map(function (o, i) {
			return '<i style="height:' + Math.max(4, o[1] / max * 100) + "%;--i:" + i + '" title="' + o[0] + ": " + o[1] + ' visits"></i>';
		}).join("");
	}

	// ------------------------------------------------------------ the poll
	function poll() {
		var form = $("#poll");
		if (!form) return;
		var box = $(".poll-options", form), btn = $("button", form), total = $(".poll-total", form), loaded = false;
		function results(data, mine) {
			var max = Math.max(1, data.total);
			box.innerHTML = data.options.map(function (o) {
				return '<span class="poll-bar' + (o.id === mine ? " mine" : "") + '"><span class="label">' + esc(o.label) + '</span><span class="num">' + o.votes +
					'</span><span class="bar"><i data-w="' + (o.votes / max * 100).toFixed(1) + '"></i></span></span>';
			}).join("");
			requestAnimationFrame(function () {
				$$(".bar i", box).forEach(function (i) { i.style.width = i.getAttribute("data-w") + "%"; });
			});
			btn.hidden = true;
			total.textContent = fmt(data.total) + " vote" + (data.total === 1 ? "" : "s") + " so far";
		}
		function ballot(data) {
			box.innerHTML = data.options.map(function (o, i) {
				return '<label class="poll-opt"><input type="radio" name="option" value="' + esc(o.id) + '"' + (i === 0 ? " required" : "") + "> " + esc(o.label) + "</label>";
			}).join("");
			btn.disabled = false;
			total.textContent = fmt(data.total) + " votes so far";
		}
		function load() {
			if (loaded) return;
			loaded = true;
			fetch("/api/poll").then(function (r) { if (!r.ok) throw 0; return r.json(); }).then(function (data) {
				var mine = store("troll-voted");
				if (mine) results(data, mine);
				else ballot(data);
			}).catch(function () { box.innerHTML = '<span class="dim small">the ballot box is offline.</span>'; });
		}
		form.addEventListener("submit", function (e) {
			e.preventDefault();
			var choice = form.querySelector("input:checked");
			if (!choice) { notify("Pick one first", "It's a poll. You have to choose.", "check"); return; }
			btn.disabled = true;
			fetch("/api/poll", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ option: choice.value }) })
				.then(function (r) { return r.json(); })
				.then(function (data) {
					store("troll-voted", choice.value);
					if (data.options) results(data, choice.value);
					if (data.error) notify("Hold on", data.error, "check");
					else Sound.chime();
				})
				.catch(function () { btn.disabled = false; });
		});
		Live.on(function (msg) {
			if (msg.type === "poll" && store("troll-voted")) results(msg.poll, store("troll-voted"));
		});
		if ("IntersectionObserver" in window) {
			var io = new IntersectionObserver(function (en) { if (en[0].isIntersecting) { io.disconnect(); load(); } });
			io.observe(form);
		} else load();
	}

	// ------------------------------------------------------------ the guestbook page
	function guestbook() {
		var form = $("#guestbook-form"), list = $("#guestbook-entries");
		if (!form || !list) return;
		var more = $("#gb-more"), status = $("#gb-status"), left = $("#gb-left"), msg = form.msg;
		var oldest = 0, seen = {};
		var avatars = $("#gb-avatars");
		var skins = window.TrollSkin ? window.TrollSkin.SKINS : ["troll"];
		var pick = store("troll-avatar") || "troll";
		skins.forEach(function (name) {
			var label = document.createElement("label");
			label.setAttribute("data-tip", name);
			label.innerHTML = '<input type="radio" name="avatar" value="' + name + '"' + (name === pick ? " checked" : "") + ' aria-label="' + name + '"><canvas></canvas>';
			avatars.appendChild(label);
			if (window.TrollSkin) window.TrollSkin.head(name, label.querySelector("canvas"));
		});

		function entry(e, fresh) {
			var div = document.createElement("div");
			div.className = "guest-entry bevel" + (fresh ? " fresh" : "");
			div.setAttribute("data-id", e.id);
			var who = e.site ? '<a href="' + esc(e.site) + '" rel="nofollow noopener ugc" target="_blank">' + esc(e.name) + "</a>" : esc(e.name);
			div.innerHTML = '<canvas aria-hidden="true"></canvas><div><div class="meta"><b>' + who + '</b><span title="' + esc(new Date(e.at).toLocaleString()) + '">' + esc(ago(e.at)) +
				'</span><span class="num">#' + e.id + '</span></div><div class="msg">' + esc(e.msg).replace(/\n/g, "<br>") + "</div></div>";
			if (window.TrollSkin) window.TrollSkin.head(e.avatar || "troll", div.querySelector("canvas"));
			return div;
		}
		function page(before) {
			var url = "/api/guestbook" + (before ? "?before=" + before : "");
			return fetch(url).then(function (r) { if (!r.ok) throw 0; return r.json(); }).then(function (data) {
				if (!before) list.innerHTML = "";
				data.entries.forEach(function (e) {
					if (seen[e.id]) return;
					seen[e.id] = true;
					list.appendChild(entry(e, false));
					oldest = e.id;
				});
				more.hidden = !data.more;
			}).catch(function () {
				list.innerHTML = '<p class="dim">The guestbook server is taking a nap (this copy of the site might not have one). Try again later.</p>';
			});
		}
		function count() {
			var n = 500 - Array.from(msg.value).length;
			left.textContent = n;
			left.classList.toggle("low", n < 25);
		}
		msg.addEventListener("input", count);
		form.addEventListener("reset", function () { setTimeout(count, 0); });
		more.addEventListener("click", function () { more.disabled = true; page(oldest).then(function () { more.disabled = false; }); });
		form.addEventListener("submit", function (e) {
			e.preventDefault();
			var data = {
				name: form.name.value, site: form.site.value, msg: msg.value,
				avatar: (form.querySelector("input[name=avatar]:checked") || {}).value || "troll",
				homepage2: form.homepage2.value
			};
			if (msg.value.trim().length < 2) { say("say a bit more than that", true); return; }
			store("troll-avatar", data.avatar);
			var btn = form.querySelector("button[type=submit]");
			btn.disabled = true;
			say("sending...", false);
			fetch("/api/guestbook", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(data) })
				.then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
				.then(function (res) {
					btn.disabled = false;
					if (!res.ok || res.j.error) { say(res.j.error || "that didn't work", true); return; }
					var en = res.j.entry;
					session("troll-signed", ((session("troll-signed") || "") + "," + en.id).replace(/^,/, ""));
					if (en.id && !seen[en.id]) {
						seen[en.id] = true;
						list.insertBefore(entry(en, true), list.firstChild);
					}
					form.msg.value = "";
					count();
					say("thanks for signing!", false);
					Sound.chime();
				})
				.catch(function () { btn.disabled = false; say("couldn't reach the guestbook server", true); });
		});
		function say(text, err) {
			status.textContent = text;
			status.classList.toggle("err", err);
			if (err) {
				form.animate([{ transform: "translateX(-6px)" }, { transform: "translateX(6px)" }, { transform: "none" }], { duration: 240 });
				Sound.error();
			}
		}
		Live.on(function (m) {
			if (m.type === "guestbook" && m.entry && !seen[m.entry.id]) {
				seen[m.entry.id] = true;
				list.insertBefore(entry(m.entry, true), list.firstChild);
			}
			if (m.type === "unsign") {
				var gone = list.querySelector('[data-id="' + m.id + '"]');
				if (gone) gone.remove();
			}
		});
		count();
		page(0);
	}

	// ------------------------------------------------------------ downloads: the classic "file download" dialog
	function downloads() {
		$$('a[href$=".jar"]').forEach(function (a) {
			a.addEventListener("click", function () {
				Live.ownDownload = Date.now();
				var name = a.getAttribute("href").split("/").pop();
				var d = dialog("File Download", '<div class="anim" style="--fly:260px"><span class="folder"></span><i class="paper"></i><i class="paper"></i><i class="paper"></i><span class="folder"></span></div>' +
					'<div>Saving: <b>' + esc(name) + "</b><br><span class=\"dim small\">from " + esc(location.host) + "</span></div>" +
					'<div class="progress"><i></i></div><div class="dim small eta">Estimated time left: 3 min 30 sec (at 28.8k)</div>', [
					{ label: "Cancel" }
				]);
				var anim = $(".anim", d.el);
				anim.style.setProperty("--fly", Math.max(120, anim.clientWidth - 80) + "px");
				var bar = $(".progress i", d.el), eta = $(".eta", d.el), btn = $(".btns .button", d.el), p = 0;
				var t = setInterval(function () {
					if (!d.layer.isConnected) { clearInterval(t); return; }
					p = Math.min(100, p + 3 + Math.random() * 7);
					bar.style.width = p + "%";
					var left = Math.round(210 * (1 - p / 100));
					eta.textContent = p < 100 ? "Estimated time left: " + Math.floor(left / 60) + " min " + left % 60 + " sec (at 28.8k)" : "Download complete. Check your downloads folder.";
					if (p >= 100) {
						clearInterval(t);
						$$(".paper", d.el).forEach(function (x) { x.remove(); });
						btn.textContent = "Close";
						Sound.chime();
					}
				}, 110);
			});
		});
	}

	// ------------------------------------------------------------ gallery lightbox (zooms out of the thumbnail)
	function lightbox() {
		var items = $$("[data-full]");
		if (!items.length) return;
		var box = document.createElement("div");
		box.className = "lightbox";
		box.innerHTML = '<img class="lb-img" alt=""><div class="lb-bar bevel"><button class="button small" type="button" data-dir="-1">&lt; prev</button><span></span>' +
			'<button class="button small" type="button" data-dir="1">next &gt;</button><button class="button small" type="button" data-close>close [x]</button></div>';
		document.body.appendChild(box);
		var img = $(".lb-img", box), cap = $(".lb-bar span", box), index = 0;
		function rectOf(el) { var r = el.getBoundingClientRect(); return { l: r.left, t: r.top, w: r.width, h: r.height }; }
		function put(r) { img.style.left = r.l + "px"; img.style.top = r.t + "px"; img.style.width = r.w + "px"; img.style.height = r.h + "px"; }
		function target(thumb) {
			var ratio = thumb.naturalWidth && thumb.naturalHeight ? thumb.naturalWidth / thumb.naturalHeight : 16 / 9;
			var w = Math.min(innerWidth * 0.92, (innerHeight - 110) * ratio), h = w / ratio;
			return { l: (innerWidth - w) / 2, t: (innerHeight - 60 - h) / 2, w: w, h: h };
		}
		function show(i, zoom) {
			index = (i + items.length) % items.length;
			var thumb = items[index];
			img.src = thumb.getAttribute("data-full");
			img.alt = thumb.getAttribute("alt") || "";
			cap.textContent = (index + 1) + "/" + items.length + "  " + (thumb.getAttribute("data-caption") || img.alt);
			if (zoom && !reduced && thumb.getBoundingClientRect().height > 20) {
				img.style.transition = "none";
				put(rectOf(thumb));
				box.classList.add("open");
				void img.offsetWidth;
				img.style.transition = "";
			}
			box.classList.add("open");
			put(target(thumb));
			Sound.click();
		}
		function close() {
			if (!box.classList.contains("open")) return;
			var thumb = items[index];
			if (reduced || thumb.getBoundingClientRect().height < 20) { box.classList.remove("open"); return; }
			put(rectOf(thumb));
			setTimeout(function () { box.classList.remove("open"); }, 330);
		}
		items.forEach(function (el, i) { el.addEventListener("click", function () { show(i, true); }); });
		box.addEventListener("click", function (e) {
			if (e.target === box || e.target.hasAttribute("data-close")) close();
			var dir = e.target.getAttribute("data-dir");
			if (dir) show(index + parseInt(dir, 10), false);
		});
		document.addEventListener("keydown", function (e) {
			if (!box.classList.contains("open")) return;
			if (e.key === "Escape") close();
			if (e.key === "ArrowRight") show(index + 1, false);
			if (e.key === "ArrowLeft") show(index - 1, false);
		});
		window.addEventListener("resize", function () { if (box.classList.contains("open")) put(target(items[index])); });
	}

	// ------------------------------------------------------------ the modules page filter
	function moduleFilter() {
		var input = $("#mod-filter");
		if (!input) return;
		var rows = $$(".mod-row"), groups = $$(".mod-group"), chips = $$(".chip"), count = $("#mod-count"), cat = "all";
		rows.forEach(function (r) {
			var n = $("td.name", r);
			var label = n.querySelector("a") || n;
			r.nameEl = label;
			r.nameText = label.textContent;
		});
		function apply() {
			var q = input.value.trim().toLowerCase(), shown = 0;
			rows.forEach(function (r) {
				var inCat = cat === "all" || r.closest(".mod-group").getAttribute("data-cat") === cat;
				var hit = inCat && (!q || r.getAttribute("data-search").indexOf(q) >= 0);
				r.classList.toggle("hidden", !hit);
				if (hit) shown++;
				var i = q ? r.nameText.toLowerCase().indexOf(q) : -1;
				r.nameEl.innerHTML = i >= 0 ? esc(r.nameText.slice(0, i)) + "<mark>" + esc(r.nameText.slice(i, i + q.length)) + "</mark>" + esc(r.nameText.slice(i + q.length)) : esc(r.nameText);
			});
			groups.forEach(function (g) { g.classList.toggle("hidden", !g.querySelector(".mod-row:not(.hidden)")); });
			count.textContent = shown + " module" + (shown === 1 ? "" : "s");
		}
		input.addEventListener("input", apply);
		chips.forEach(function (c) {
			c.addEventListener("click", function () {
				cat = c.getAttribute("data-cat");
				chips.forEach(function (o) { o.classList.toggle("on", o === c); });
				apply();
				Sound.click();
			});
		});
		var q = new URLSearchParams(location.search).get("q");
		if (q) input.value = q;
		var box = $(".searchbox input");
		if (box && q) box.value = q;
		apply();
	}

	// ------------------------------------------------------------ webring
	function webring() {
		$$("[data-ring]").forEach(function (a) {
			a.addEventListener("click", function (e) {
				e.preventDefault();
				var ring = a.closest(".webring");
				ring.classList.remove("spin");
				void ring.offsetWidth;
				ring.classList.add("spin");
				var cur = 0;
				PAGES.forEach(function (p, i) { if (p[0] === location.pathname) cur = i; });
				var dir = parseInt(a.getAttribute("data-ring"), 10);
				var next = dir ? (cur + dir + PAGES.length) % PAGES.length : Math.floor(Math.random() * PAGES.length);
				setTimeout(function () { location.href = PAGES[next][0]; }, reduced ? 0 : 450);
			});
		});
	}

	// ------------------------------------------------------------ secrets, and things for when you look away
	function secrets() {
		var code = ["ArrowUp", "ArrowUp", "ArrowDown", "ArrowDown", "ArrowLeft", "ArrowRight", "ArrowLeft", "ArrowRight", "b", "a"];
		var pos = 0, typed = "";
		document.addEventListener("keydown", function (e) {
			pos = e.key === code[pos] ? pos + 1 : (e.key === code[0] ? 1 : 0);
			if (pos === code.length) { pos = 0; twerk(); }
			if (/^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) || e.target.isContentEditable) return;
			if (e.key.length === 1) typed = (typed + e.key.toLowerCase()).slice(-9);
			if (typed === "skinblink") {
				typed = "";
				// the whole page does a wave, like SkinBlink's wave pattern
				$$(".content > *").forEach(function (el, i) { el.style.setProperty("--n", i % 12); });
				document.body.classList.remove("skinblinked");
				void document.body.offsetWidth;
				document.body.classList.add("skinblinked");
				setTimeout(function () { document.body.classList.remove("skinblinked"); }, 4200);
				notify("You found it", "That's skin animation, but for the whole page. Imagine it on your skin.", "player");
			}
		});
		// look away and the tab title begs you to come back, while the favicon blinks
		var title = document.title, icon = $('link[rel="icon"]'), normal = icon ? icon.href : "", inverted = null, timer;
		if (icon) {
			var img = new Image();
			img.onload = function () {
				var c = document.createElement("canvas");
				c.width = c.height = 32;
				var g = c.getContext("2d");
				g.fillStyle = "#fff";
				g.fillRect(0, 0, 32, 32);
				g.globalCompositeOperation = "difference";
				g.drawImage(img, 0, 0, 32, 32);
				try { inverted = c.toDataURL(); } catch (e) { inverted = null; }
			};
			img.src = normal;
		}
		document.addEventListener("visibilitychange", function () {
			clearInterval(timer);
			if (document.hidden) {
				var msg = "come back! the skins miss you ... ", i = 0;
				timer = setInterval(function () {
					i++;
					document.title = (msg + msg).slice(i % msg.length, i % msg.length + 26);
					if (icon && inverted) icon.href = i % 2 ? inverted : normal;
				}, 400);
			} else {
				document.title = title;
				if (icon) icon.href = normal;
			}
		});
	}

	// ------------------------------------------------------------ go
	function soundHooks() {
		document.addEventListener("pointerdown", function (e) {
			if (!Sound.on) return;
			var el = e.target.closest && e.target.closest(".tab, .bm, .navbtn, .chan, .button, .winbtn, .start, .chip, .seg span, .gui-mod, .skin-chip");
			if (el) el.classList.contains("tab") ? Sound.tab() : Sound.click();
		});
	}

	function init() {
		themeCards();
		applyTheme(THEMES[themeIndex(store("troll-theme") || "noir")]);
		setCrt(store("troll-crt") !== "0");
		setSound(Sound.on, true);
		var crtBtn = $("#crt-toggle");
		if (crtBtn) crtBtn.addEventListener("click", function () { setCrt(!document.body.classList.contains("crt")); });
		var sndBtn = $("#sound-toggle");
		if (sndBtn) sndBtn.addEventListener("click", function () { setSound(!Sound.on); });
		var pal = $("#theme-cycle");
		if (pal) {
			pal.addEventListener("click", function (e) { cycleTheme(1, pointOf(e, pal)); });
			pal.addEventListener("contextmenu", function (e) { e.preventDefault(); cycleTheme(-1, pointOf(e, pal)); });
		}
		// each piece on its own, so one hiccup can't take the rest of the page down with it
		[letters, tooltips, windowControls, taskbar, wallpaper, desktopIcons, reveals, tilt, trail, poll, guestbook,
			downloads, lightbox, moduleFilter, webring, secrets, soundHooks, function () { Live.start(); }].forEach(function (fn) {
			try { fn(); } catch (e) { if (window.console) console.error(e); }
		});
		boot(function (booted) {
			var win = $("#browser");
			if (booted && win) {
				win.classList.add("opening");
				setTimeout(function () { win.classList.remove("opening"); }, 600);
			}
			chromeBits();
			typewriters();
			if (booted) {
				setTimeout(function () {
					var s = Live.stats;
					notify("Welcome to Troll Client!", s && s.online > 1 ? s.online + " people are on the site right now. Say hi in the guestbook." : "New here? Have a look at the skin animation.", "star", 7000);
				}, 1400);
			}
		});
	}

	window.TrollSite = {
		setTheme: setTheme, cycleTheme: cycleTheme, themes: THEMES,
		notify: notify, sound: Sound, bump: function (k, n) { Live.bump(k, n); }, live: Live
	};
	if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
	else init();
})();
