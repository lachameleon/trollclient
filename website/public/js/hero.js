/* The homepage hero: a port of the client's title screen.
   Warp starfield, the chunky 3D pixel logo dropping in letter by letter,
   a demoscene sine scroller, and a shockwave wherever you click. */
(function () {
	"use strict";

	var canvas = document.getElementById("hero");
	if (!canvas || !window.TROLL) return;
	var ctx = canvas.getContext("2d");
	var glyphs = window.TROLL.glyphs;
	var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
	var LOGO = "TROLL CLIENT";
	var SCROLL = "greetings to everyone getting walled in by nowayhome ... crystalcancel says no ... twerk responsibly ... " +
		"35 modules, 5 shaders, 7 themes ... press right shift in game ... best viewed in netscape at 800x600 ...      ";

	var W = 0, H = 0, dpr = 1;
	var stars = [];
	var rings = [];
	var start = performance.now();
	var mouse = { x: 0, y: 0, over: false };
	var colors = { text: "#ededed", dim: "#7a7a7a", bg: "#000000", scan: "rgba(0,0,0,0.18)" };

	function readColors() {
		var cs = getComputedStyle(document.body);
		colors.text = cs.getPropertyValue("--text").trim() || "#ededed";
		colors.dim = cs.getPropertyValue("--dim").trim() || "#7a7a7a";
		colors.bg = cs.getPropertyValue("--bg").trim() || "#000";
		canvas.parentElement.style.background = colors.bg;
		// black lines on a light background read far stronger, same as in the client
		colors.scan = document.body.classList.contains("light") ? "rgba(0,0,0,0.05)" : "rgba(0,0,0,0.18)";
	}
	document.addEventListener("troll-theme", readColors);

	function resize() {
		var r = canvas.getBoundingClientRect();
		dpr = Math.min(2, window.devicePixelRatio || 1);
		W = Math.max(1, Math.round(r.width));
		H = Math.max(1, Math.round(r.height));
		canvas.width = W * dpr;
		canvas.height = H * dpr;
		ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
		ctx.imageSmoothingEnabled = false;
	}
	window.addEventListener("resize", resize);

	function glyph(c) {
		return glyphs[c] || glyphs[c.toUpperCase()] || ["..", "..", "..", "..", "..", "..", ".."];
	}

	// lay the logo out once: one entry per lit pixel
	var logoPixels = [], logoW = 0;
	(function () {
		var x = 0;
		for (var i = 0; i < LOGO.length; i++) {
			var g = glyph(LOGO[i]);
			for (var y = 0; y < g.length && y < 7; y++) {
				for (var gx = 0; gx < g[y].length; gx++) {
					if (g[y][gx] === "#") logoPixels.push({ x: x + gx, y: y, seed: Math.random() });
				}
			}
			x += g[0].length + 1;
		}
		logoW = x - 1;
	})();

	function textWidth(s, px) {
		var w = 0;
		for (var i = 0; i < s.length; i++) w += (s[i] === " " ? 4 : glyph(s[i])[0].length + 1);
		return w * px;
	}

	function drawText(s, x, y, px, color) {
		ctx.fillStyle = color;
		for (var i = 0; i < s.length; i++) {
			var c = s[i];
			if (c === " ") { x += 4 * px; continue; }
			var g = glyph(c);
			for (var gy = 0; gy < g.length; gy++) {
				for (var gx = 0; gx < g[gy].length; gx++) {
					if (g[gy][gx] === "#") ctx.fillRect(x + gx * px, y + gy * px, px, px);
				}
			}
			x += (g[0].length + 1) * px;
		}
		return x;
	}

	function respawn(s, z) {
		s.x = (Math.random() * 2 - 1) * 1.2;
		s.y = (Math.random() * 2 - 1) * 1.2;
		s.z = Math.max(0.02, z);
	}

	for (var i = 0; i < 260; i++) {
		var s = {};
		respawn(s, Math.random());
		stars.push(s);
	}

	function outBounce(t) {
		var n1 = 7.5625, d1 = 2.75;
		if (t < 1 / d1) return n1 * t * t;
		if (t < 2 / d1) { t -= 1.5 / d1; return n1 * t * t + 0.75; }
		if (t < 2.5 / d1) { t -= 2.25 / d1; return n1 * t * t + 0.9375; }
		t -= 2.625 / d1; return n1 * t * t + 0.984375;
	}

	var last = performance.now();
	function frame(now) {
		var dt = Math.min(0.1, (now - last) / 1000);
		last = now;
		var age = (now - start) / 1000;
		ctx.clearRect(0, 0, W, H);

		// stars: faster while the mouse is over the hero, like hovering a menu button
		var speed = (mouse.over ? 0.35 : 0.07) + (rings.length ? 0.4 : 0);
		var cx = W / 2 + (mouse.over ? (mouse.x - W / 2) * -0.03 : 0);
		var cy = H / 2 + (mouse.over ? (mouse.y - H / 2) * -0.03 : 0);
		var fov = Math.max(W, H) * 0.5;
		for (var i = 0; i < stars.length; i++) {
			var s = stars[i];
			var oz = s.z;
			s.z -= speed * dt;
			if (s.z <= 0.02) { respawn(s, 1); continue; }
			var sx = cx + s.x / s.z * fov, sy = cy + s.y / s.z * fov;
			if (sx < -4 || sy < -4 || sx > W + 4 || sy > H + 4) { respawn(s, 1); continue; }
			var depth = 1 - s.z;
			ctx.globalAlpha = 0.15 + depth * 0.85;
			ctx.fillStyle = colors.text;
			var tz = Math.min(1, oz + speed * 0.06);
			var tx = cx + s.x / tz * fov, ty = cy + s.y / tz * fov;
			ctx.globalAlpha *= 0.4;
			ctx.fillRect((sx + tx) / 2, (sy + ty) / 2, 1, 1);
			ctx.globalAlpha = 0.15 + depth * 0.85;
			var size = depth > 0.85 ? 2 : 1;
			ctx.fillRect(sx, sy, size, size);
		}
		ctx.globalAlpha = 1;

		// shockwave rings
		rings = rings.filter(function (r) { return now - r.t < 1200; });
		rings.forEach(function (r) {
			var t = (now - r.t) / 1200;
			var radius = (1 - Math.pow(1 - t, 3)) * Math.max(W, H) * 0.6;
			var dots = Math.max(24, radius * 0.9);
			ctx.globalAlpha = (1 - t) * 0.7;
			ctx.fillStyle = colors.text;
			for (var k = 0; k < dots; k++) {
				var a = k * Math.PI * 2 / dots;
				ctx.fillRect(r.x + Math.cos(a) * radius, r.y + Math.sin(a) * radius, 1, 1);
			}
		});
		ctx.globalAlpha = 1;

		// the logo
		var ps = Math.max(2, Math.min(9, Math.floor(W * 0.8 / logoW)));
		var lx = Math.round((W - logoW * ps) / 2);
		var ly = Math.round(H * 0.2);
		var depthPx = Math.max(2, Math.floor(ps / 2) + 1);
		var logoAge = reduced ? 9 : age - 0.2;
		var glitch = !reduced && (Math.floor(age / 2.9) * 13 % 7 > 3) && (age % 2.9) < 0.18;
		var band = Math.floor(Math.random() * 7);
		for (var pass = 0; pass < 2; pass++) {
			for (var p = 0; p < logoPixels.length; p++) {
				var px = logoPixels[p];
				var delay = 0.6 * px.x / logoW + px.seed * 0.12;
				var t = Math.min(1, Math.max(0, (logoAge - delay) / 0.55));
				if (t <= 0) continue;
				var drop = (1 - outBounce(t)) * (ly + 60);
				var wave = reduced ? 0 : Math.sin(age * 2.2 + px.x * 0.22) * ps * 0.25;
				var x = lx + px.x * ps + (glitch && px.y === band ? 6 * ps : 0);
				var y = ly + px.y * ps - drop + wave;
				if (pass === 0) {
					for (var d = depthPx; d >= 1; d--) {
						ctx.fillStyle = colors.dim;
						ctx.globalAlpha = 0.35 + 0.45 * (depthPx - d) / depthPx;
						ctx.fillRect(x + d, y + d, ps, ps);
					}
					ctx.globalAlpha = 1;
				} else {
					ctx.fillStyle = colors.text;
					ctx.globalAlpha = px.y > 4 ? 0.85 : 1;
					ctx.fillRect(x, y, ps, ps);
					// a sheen sweeps across the face every few seconds
					var sweep = Math.pow(Math.max(0, Math.cos((px.x / logoW - age * 0.25) * Math.PI * 2)), 18);
					if (sweep > 0.05) {
						ctx.globalAlpha = sweep * 0.6;
						ctx.fillStyle = colors.bg;
						ctx.fillRect(x, y + ps - Math.max(1, ps / 3), ps, Math.max(1, ps / 3));
					}
					ctx.globalAlpha = 1;
				}
			}
		}

		// tagline types itself out once the logo lands
		var tag = "a client for being mildly annoying";
		var tagAge = logoAge - 1.1;
		if (tagAge > 0) {
			var shown = tag.slice(0, Math.floor(tagAge * 28));
			var tp = 2;
			var tw = textWidth(tag, tp);
			var tx2 = Math.round((W - tw) / 2), ty2 = ly + 7 * ps + 22;
			var end = drawText(shown, tx2, ty2, tp, colors.dim);
			if (Math.floor(age * 2.2) % 2 === 0) {
				ctx.fillStyle = colors.text;
				ctx.fillRect(end + 2, ty2 - 2, 10, 18);
			}
		}

		// sine scroller
		var sp = 2;
		var total = textWidth(SCROLL, sp);
		var offset = (age * 70) % total;
		var sxp = W - offset - total;
		var baseY = H - 34;
		while (sxp < W) {
			var cursor = sxp;
			for (var c = 0; c < SCROLL.length; c++) {
				var ch = SCROLL[c];
				var cw = ch === " " ? 4 * sp : (glyph(ch)[0].length + 1) * sp;
				if (cursor > -cw && cursor < W && ch !== " ") {
					var yy = baseY + Math.sin(cursor * 0.022 + age * 3) * 7;
					var edge = Math.min(1, Math.min(cursor, W - cursor) / 60);
					ctx.globalAlpha = Math.max(0, (0.55 + 0.45 * Math.sin(cursor * 0.01 - age * 2)) * edge);
					drawText(ch, cursor, yy, sp, colors.text);
				}
				cursor += cw;
			}
			sxp += total;
		}
		ctx.globalAlpha = 1;

		// scanlines over everything
		ctx.fillStyle = colors.scan;
		for (var sl = 0; sl < H; sl += 3) ctx.fillRect(0, sl, W, 1);

		if (!visible || document.hidden) { running = false; return; }
		requestAnimationFrame(frame);
	}

	// no point drawing a starfield nobody can see
	var visible = true, running = false;
	function wake() {
		if (running || !started) return;
		running = true;
		last = performance.now();
		requestAnimationFrame(frame);
	}
	if ("IntersectionObserver" in window) {
		new IntersectionObserver(function (en) {
			visible = en[0].isIntersecting;
			if (visible) wake();
		}).observe(canvas);
	}
	document.addEventListener("visibilitychange", function () { if (!document.hidden) wake(); });

	canvas.addEventListener("mousemove", function (e) {
		var r = canvas.getBoundingClientRect();
		mouse.x = e.clientX - r.left;
		mouse.y = e.clientY - r.top;
		mouse.over = true;
	});
	canvas.addEventListener("mouseleave", function () { mouse.over = false; });
	canvas.addEventListener("click", function (e) {
		var r = canvas.getBoundingClientRect();
		rings.push({ x: e.clientX - r.left, y: e.clientY - r.top, t: performance.now() });
	});

	var started = false;
	function go() {
		if (started) return;
		started = true;
		readColors();
		resize();
		wake();
	}
	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", go);
	} else {
		go();
	}
})();
