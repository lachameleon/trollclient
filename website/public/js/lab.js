/* The skin animation lab: SkinBlink's real settings driving a 3D player, simulated "what you're
   doing in game" for the triggers, a tick-by-tick timeline of the layers, and the packets it
   would send. Load any Minecraft player's skin through the Worker. */
(function () {
	"use strict";

	var stageEl = document.getElementById("lab-stage");
	if (!stageEl || !window.TrollSkin) return;
	var TS = window.TrollSkin;
	var site = window.TrollSite || { bump: function () {}, notify: function () {}, sound: { click: function () {} } };
	var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

	var PATTERNS = ["Blink", "Wave", "Random", "Strobe"];
	var TRIGGERS = ["Always", "In Air", "On Ground", "Moving", "Still", "Sneaking", "Sprinting", "Hurt", "Using Item"];
	var DEFAULTS = { enabled: true, pattern: "Wave", interval: 3, cape: true, trigger: "Always" };
	var cfg = {};
	try { cfg = JSON.parse(localStorage.getItem("troll-lab") || "{}") || {}; } catch (e) { cfg = {}; }
	for (var k in DEFAULTS) if (!(k in cfg)) cfg[k] = DEFAULTS[k];
	function save() { try { localStorage.setItem("troll-lab", JSON.stringify(cfg)); } catch (e) { /* fine */ } }

	function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
	function el(tag, cls, html) {
		var e = document.createElement(tag);
		if (cls) e.className = cls;
		if (html !== undefined) e.innerHTML = html;
		return e;
	}

	// ------------------------------------------------------------ the model
	function scaleFor() { return Math.max(5, Math.min(10, Math.floor(stageEl.clientHeight / 46))); }
	var model = new TS.Model(stageEl, {
		scale: scaleFor(), yaw: -24, spin: 9, tag: "you", hint: "drag to spin · click to punch",
		onClick: function () { punch(); }
	});
	var blink = new TS.Blink(cfg);
	var skinSrc = TS.TEX + "blink.png", skinSlim = false;
	model.setSkin(skinSrc, false);
	window.addEventListener("resize", function () {
		var s = scaleFor();
		if (s !== model.S && model.skin) { model.S = s; model.build(); model.setLayers(blink.layers()); }
	});

	// ------------------------------------------------------------ settings box, styled like the client's
	var box = document.getElementById("lab-settings");
	function row(label, desc, changed) {
		var r = el("div", "gui-row" + (changed ? " changed" : ""));
		r.innerHTML = '<span class="lbl">' + esc(label) + "</span>";
		r.setAttribute("data-tip", desc);
		return r;
	}
	function switchRow(label, desc, on, fn, changed) {
		var r = row(label, desc, changed);
		r.classList.add("clicky");
		r.insertAdjacentHTML("beforeend", '<span class="switch' + (on ? " on" : "") + '">' + (on ? "on" : "off") + '<span class="track"><span class="knob"></span></span></span>');
		r.addEventListener("click", function () { fn(!on); });
		r.tabIndex = 0;
		r.setAttribute("role", "switch");
		r.setAttribute("aria-checked", on ? "true" : "false");
		r.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); fn(!on); } });
		return r;
	}
	function segRow(label, desc, options, value, fn) {
		var r = row(label, desc, value !== DEFAULTS[label.toLowerCase()]);
		var seg = el("div", "seg");
		seg.setAttribute("role", "radiogroup");
		seg.setAttribute("aria-label", label);
		options.forEach(function (o) {
			var s = el("span", "clicky" + (o === value ? " on" : ""), esc(o.toLowerCase()));
			s.tabIndex = 0;
			s.setAttribute("role", "radio");
			s.setAttribute("aria-checked", o === value ? "true" : "false");
			s.addEventListener("click", function () { fn(o); });
			s.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); fn(o); } });
			seg.appendChild(s);
		});
		r.appendChild(seg);
		return r;
	}
	function render() {
		box.innerHTML = '<span class="legend">skinblink<span class="blink">_</span></span>' +
			'<div class="desc">Flickers your hat, jacket, sleeves, trousers and cape so your skin strobes for everyone.</div>';
		box.appendChild(switchRow("Enabled", "Turn the module on or off", cfg.enabled, function (v) {
			cfg.enabled = v;
			if (!v) restore();
			changed();
		}, false));
		box.appendChild(segRow("Pattern", "How the layers flicker", PATTERNS, cfg.pattern, function (v) { cfg.pattern = v; changed(); }));
		var r = row("Interval", "Ticks between changes (20 ticks = 1 second)", cfg.interval !== DEFAULTS.interval);
		r.insertAdjacentHTML("beforeend", '<span class="val">' + cfg.interval + "t <span class=\"dim\">(" + cfg.interval * 50 + "ms)</span></span>");
		var range = el("input");
		range.type = "range";
		range.min = 1;
		range.max = 20;
		range.step = 1;
		range.value = cfg.interval;
		range.setAttribute("aria-label", "Interval in ticks");
		range.addEventListener("input", function () {
			cfg.interval = parseInt(range.value, 10);
			blink.interval = cfg.interval;
			r.querySelector(".val").innerHTML = cfg.interval + 't <span class="dim">(' + cfg.interval * 50 + "ms)</span>";
			r.classList.toggle("changed", cfg.interval !== DEFAULTS.interval);
			save();
		});
		r.addEventListener("contextmenu", function (e) { e.preventDefault(); cfg.interval = DEFAULTS.interval; changed(); });
		r.appendChild(range);
		box.appendChild(r);
		box.appendChild(switchRow("Cape", "Include your cape", cfg.cape, function (v) { cfg.cape = v; changed(); }, cfg.cape !== DEFAULTS.cape));
		box.appendChild(segRow("Trigger", "Only blink while this is happening; otherwise your skin looks normal", TRIGGERS, cfg.trigger, function (v) { cfg.trigger = v; changed(); }));
		document.getElementById("osd-pattern").textContent = cfg.enabled ? cfg.pattern.toLowerCase() : "off";
		document.getElementById("osd-trigger").textContent = cfg.trigger.toLowerCase();
	}
	function changed() {
		blink.pattern = cfg.pattern;
		blink.interval = cfg.interval;
		blink.cape = cfg.cape;
		blink.trigger = cfg.trigger;
		save();
		render();
		hintTrigger();
	}
	function restore() {
		blink.blinking = false;
		for (var i = 0; i < blink.states.length; i++) blink.states[i] = true;
		model.setLayers(blink.layers());
		packet();
	}

	// nudge people towards the action that makes their trigger fire
	var TRIGGER_ACT = { "In Air": "jump", "On Ground": null, "Moving": "walk", "Still": null, "Sneaking": "sneak", "Sprinting": "sprint", "Hurt": "hurt", "Using Item": "use" };
	function hintTrigger() {
		document.querySelectorAll("#lab-actions .button").forEach(function (b) { b.classList.remove("pulse"); });
		var act = TRIGGER_ACT[cfg.trigger];
		if (!act) return;
		var b = document.querySelector('#lab-actions [data-act="' + act + '"]');
		if (b) { void b.offsetWidth; b.classList.add("pulse"); }
	}

	// ------------------------------------------------------------ what you're doing in game
	var held = { walk: false, sprint: false, sneak: false, use: false };
	var keys = { walk: false, sprint: false, sneak: false, use: false };
	function sync() {
		["walk", "sprint", "sneak", "use"].forEach(function (k) {
			var on = held[k] || keys[k];
			model.state[k] = on;
			var b = document.querySelector('#lab-actions [data-act="' + k + '"]');
			if (b) { b.classList.toggle("on", on); b.setAttribute("aria-pressed", on ? "true" : "false"); }
		});
		// sprinting means moving
		if (model.state.sprint && !model.state.walk) model.state.walk = true;
	}
	function punch() {
		model.hurt();
		site.sound.blip && site.sound.blip(180, 0.12, "square", 0.05, 90);
	}
	document.querySelectorAll("#lab-actions .button").forEach(function (b) {
		b.addEventListener("click", function () {
			var act = b.getAttribute("data-act");
			if (act === "jump") { model.jump(); flash(b); return; }
			if (act === "hurt") { punch(); flash(b); return; }
			held[act] = !held[act];
			if (act === "sprint" && held.sprint) held.sneak = false;
			if (act === "sneak" && held.sneak) held.sprint = false;
			sync();
		});
	});
	function flash(b) { b.classList.remove("pulse"); void b.offsetWidth; b.classList.add("pulse"); }

	var lab = document.getElementById("lab"), labVisible = false;
	if ("IntersectionObserver" in window) {
		new IntersectionObserver(function (en) { labVisible = en[0].intersectionRatio > 0.4; }, { threshold: [0, 0.4, 1] }).observe(lab);
	}
	var KEYMAP = { KeyW: "walk", KeyA: "walk", KeyS: "walk", KeyD: "walk", ArrowUp: "walk", ArrowDown: "walk", ShiftLeft: "sneak", ShiftRight: "sneak", ControlLeft: "sprint", ControlRight: "sprint", KeyE: "use" };
	function typing(e) { return /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) || e.target.isContentEditable; }
	document.addEventListener("keydown", function (e) {
		if (!labVisible || typing(e) || e.metaKey || e.altKey) return;
		if (e.code === "Space") { e.preventDefault(); model.jump(); return; }
		var act = KEYMAP[e.code];
		if (!act) return;
		if (e.code.indexOf("Arrow") === 0) e.preventDefault();
		keys[act] = true;
		sync();
	});
	document.addEventListener("keyup", function (e) {
		var act = KEYMAP[e.code];
		if (!act) return;
		keys[act] = false;
		sync();
	});
	window.addEventListener("blur", function () { for (var k in keys) keys[k] = false; sync(); });

	// ------------------------------------------------------------ skins: the bundled ones, or anybody's
	var picker = document.getElementById("skin-picker");
	function chip(name, src, slim, label) {
		var b = el("button", "skin-chip" + (src === skinSrc ? " on" : ""));
		b.type = "button";
		b.setAttribute("aria-label", "wear " + label);
		b.setAttribute("data-tip", label);
		var c = el("canvas");
		b.appendChild(c);
		TS.head(src, c);
		b.addEventListener("click", function () { wear(src, slim, name, b); });
		picker.appendChild(b);
		return b;
	}
	function wear(src, slim, name, b) {
		skinSrc = src;
		skinSlim = slim;
		picker.querySelectorAll(".skin-chip").forEach(function (o) { o.classList.toggle("on", o === b); });
		model.setSkin(src, slim).then(function () {
			model.setLayers(blink.layers());
			model.setCape(name === "blink" || name === "troll" || name.indexOf("@") === 0);
			if (model.tag) model.tag.textContent = name.indexOf("@") === 0 ? name.slice(1) : "you";
		});
	}
	TS.SKINS.forEach(function (name) {
		chip(name, TS.TEX + name + ".png", false, name === "blink" ? "blink (demo skin, every layer)" : name + " (SkinChanger)");
	});

	var form = document.getElementById("userskin"), msg = document.getElementById("userskin-msg");
	form.addEventListener("submit", function (e) {
		e.preventDefault();
		var name = form.name.value.trim();
		if (!/^[A-Za-z0-9_]{2,16}$/.test(name)) { say("that's not a Minecraft username (2-16 letters, numbers or _)", true); return; }
		say("asking Mojang for " + name + "'s skin...", false);
		var btn = form.querySelector("button");
		btn.disabled = true;
		fetch("/api/skin/" + encodeURIComponent(name)).then(function (r) {
			if (!r.ok) return r.json().then(function (j) { throw new Error(j.error || "couldn't load that skin"); }, function () { throw new Error("couldn't load that skin"); });
			var slim = r.headers.get("x-skin-model") === "slim";
			var real = r.headers.get("x-skin-name") || name;
			return r.blob().then(function (blob) { return { url: URL.createObjectURL(blob), slim: slim, name: real }; });
		}).then(function (s) {
			btn.disabled = false;
			var b = chip("@" + s.name, s.url, s.slim, s.name);
			wear(s.url, s.slim, "@" + s.name, b);
			say("wearing " + s.name + "'s skin" + (s.slim ? " (slim arms)" : "") + ". the Troll Client cape is on the house.", false);
			site.sound.chime && site.sound.chime();
		}).catch(function (err) {
			btn.disabled = false;
			say(err.message === "Failed to fetch" ? "couldn't reach the skin server (this copy of the site might not have one)" : err.message, true);
		});
	});
	function say(text, err) {
		msg.textContent = text;
		msg.classList.toggle("err", err);
	}

	// ------------------------------------------------------------ the timeline and the packets
	var canvas = document.getElementById("timeline"), g = canvas.getContext("2d");
	var rows = document.getElementById("timeline-rows");
	TS.LABELS.forEach(function (l) { rows.appendChild(el("span", "", esc(l))); });
	var cols = [], COL = 6, ROW = 14, colors = {};
	function readColors() {
		var cs = getComputedStyle(document.body);
		colors.on = cs.getPropertyValue("--text").trim() || "#eee";
		colors.off = cs.getPropertyValue("--border").trim() || "#333";
		colors.dim = cs.getPropertyValue("--dim").trim() || "#777";
		draw();
	}
	function size() {
		var dpr = Math.min(2, window.devicePixelRatio || 1);
		canvas.width = Math.round(canvas.clientWidth * dpr);
		canvas.height = Math.round(98 * dpr);
		g.setTransform(dpr, 0, 0, dpr, 0, 0);
		draw();
	}
	function draw() {
		var w = canvas.clientWidth, n = Math.floor(w / COL);
		g.clearRect(0, 0, w, 98);
		var start = Math.max(0, cols.length - n);
		for (var c = start; c < cols.length; c++) {
			var x = w - (cols.length - c) * COL;
			var col = cols[c];
			for (var r = 0; r < 7; r++) {
				if (col.idle) { g.fillStyle = colors.off; g.globalAlpha = 0.5; }
				else if (col.s[r]) { g.fillStyle = colors.on; g.globalAlpha = col.p ? 1 : 0.8; }
				else { g.fillStyle = colors.off; g.globalAlpha = 1; }
				g.fillRect(x + 1, r * ROW + 3, COL - 2, ROW - 6);
			}
			if (col.p) {
				// a tick where a packet went out
				g.globalAlpha = 1;
				g.fillStyle = colors.dim;
				g.fillRect(x + COL / 2 - 0.5, 0, 1, 2);
			}
		}
		g.globalAlpha = 1;
		if (cols.length > n * 2) cols = cols.slice(-n);
	}
	window.addEventListener("resize", size);
	document.addEventListener("troll-theme", function () { setTimeout(readColors, 30); });

	var count = document.getElementById("pkt-count"), rate = document.getElementById("pkt-rate");
	var wire = document.getElementById("wire"), server = document.getElementById("server");
	var sent = [], lastPkt = 0, total = 0;
	function packet() {
		total++;
		var now = performance.now();
		sent.push(now);
		count.textContent = total.toLocaleString("en-US");
		site.bump("blinks", 1);
		if (reduced || now - lastPkt < 110) return;
		lastPkt = now;
		var p = el("i", "pkt");
		wire.appendChild(p);
		setTimeout(function () {
			p.remove();
			server.classList.remove("rx");
			void server.offsetWidth;
			server.classList.add("rx");
		}, 900);
	}
	setInterval(function () {
		var now = performance.now();
		sent = sent.filter(function (t) { return now - t < 1000; });
		rate.textContent = sent.length;
	}, 250);

	// one game tick every 50ms, in step with the animation frames
	var acc = 0;
	model.onFrame = function (dt) {
		acc += dt;
		var guard = 0;
		while (acc >= 0.05 && guard++ < 4) {
			acc -= 0.05;
			var sentOne = false;
			if (cfg.enabled) {
				sentOne = blink.tick(model.status());
				if (sentOne) {
					model.setLayers(blink.layers());
					packet();
				}
			}
			cols.push({ s: blink.states.slice(), p: sentOne, idle: !cfg.enabled });
		}
		if (acc > 0.2) acc = 0;
		draw();
	};

	// ------------------------------------------------------------ the pattern cards further down
	var cards = Array.prototype.slice.call(document.querySelectorAll("#patterns .pattern")).map(function (card) {
		var strip = card.querySelector(".strip");
		var cells = TS.ORDER.map(function (name, i) {
			var c = el("i");
			c.title = TS.LABELS[i];
			strip.appendChild(c);
			return c;
		});
		return { blink: new TS.Blink({ pattern: card.getAttribute("data-pattern"), interval: 4 }), cells: cells };
	});
	function stepCards() {
		cards.forEach(function (c) {
			if (c.blink.tick({ ground: true, still: true })) {
				c.blink.states.forEach(function (on, i) { c.cells[i].classList.toggle("off", !on); });
			}
		});
	}
	if (cards.length) {
		if (reduced) { for (var i = 0; i < 8; i++) stepCards(); }
		else setInterval(function () { if (!document.hidden) stepCards(); }, 50);
	}

	render();
	readColors();
	size();
	sync();
	window.TrollLab = { model: model, blink: blink, settings: cfg };
})();
