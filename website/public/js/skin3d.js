/* Skin animation, in your browser.
   A Minecraft player built out of CSS 3D boxes straight from a 64x64 skin texture (both layers,
   classic or slim arms, the Troll Client cape), and SkinBlink's pattern logic ported 1:1 from
   SkinBlink.java: the same layer order, patterns, tick interval and triggers.
   window.TrollSkin = { Model, Blink, load, head, ORDER } */
(function () {
	"use strict";

	var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
	var TEX = "img/skins/tex/";
	var SKINS = ["blink", "troll", "mime", "referee", "ghost", "hacker"];

	// the order SkinBlink walks the layers in (PlayerModelPart order in the Java)
	var ORDER = ["hat", "jacket", "lsleeve", "lpants", "rpants", "rsleeve", "cape"];
	var ORDER_LABELS = ["hat", "jacket", "l.sleeve", "l.pants", "r.pants", "r.sleeve", "cape"];

	// [u, v, width, height, depth] in the skin texture
	function parts(slim) {
		var aw = slim ? 3 : 4;
		return {
			head: { base: [0, 0, 8, 8, 8], over: [32, 0, 8, 8, 8], grow: 0.5, layer: "hat", pivot: [0, 0, 0], at: [0, -4, 0] },
			body: { base: [16, 16, 8, 12, 4], over: [16, 32, 8, 12, 4], grow: 0.25, layer: "jacket", pivot: [0, 0, 0], at: [0, 6, 0] },
			rarm: { base: [40, 16, aw, 12, 4], over: [40, 32, aw, 12, 4], grow: 0.25, layer: "rsleeve", pivot: [-5, 2, 0], at: [slim ? -0.5 : -1, 4, 0] },
			larm: { base: [32, 48, aw, 12, 4], over: [48, 48, aw, 12, 4], grow: 0.25, layer: "lsleeve", pivot: [5, 2, 0], at: [slim ? 0.5 : 1, 4, 0] },
			rleg: { base: [0, 16, 4, 12, 4], over: [0, 32, 4, 12, 4], grow: 0.25, layer: "rpants", pivot: [-1.9, 12, 0], at: [0, 6, 0] },
			lleg: { base: [16, 48, 4, 12, 4], over: [0, 48, 4, 12, 4], grow: 0.25, layer: "lpants", pivot: [1.9, 12, 0], at: [0, 6, 0] }
		};
	}

	function div(cls) {
		var d = document.createElement("div");
		d.className = cls;
		return d;
	}

	// ------------------------------------------------------------ skin loading
	var cache = {};

	/** Loads a skin into a clean 64x64 data URL: legacy 64x32 skins get their left limbs, and the
	    base layer is made opaque, exactly like the game does. Resolves { url, slim, image }. */
	function load(src, slim) {
		var key = src + "|" + !!slim;
		if (cache[key]) return cache[key];
		cache[key] = new Promise(function (resolve, reject) {
			var img = new Image();
			img.onload = function () {
				var c = document.createElement("canvas");
				c.width = 64;
				c.height = 64;
				var g = c.getContext("2d", { willReadFrequently: true });
				g.imageSmoothingEnabled = false;
				g.drawImage(img, 0, 0);
				if (img.height === 32) legacy(g);
				opaque(g, [[0, 0, 32, 16], [0, 16, 64, 16], [16, 48, 32, 16]]);
				resolve({ url: c.toDataURL(), slim: !!slim, canvas: c });
			};
			img.onerror = function () { delete cache[key]; reject(new Error("couldn't load " + src)); };
			img.src = src;
		});
		return cache[key];
	}

	function legacy(g) {
		// SkinTextureDownloader.processLegacySkin: mirror the right arm and leg over to the left
		var copies = [[4, 16, 16, 32, 4, 4], [8, 16, 16, 32, 4, 4], [0, 20, 24, 32, 4, 12], [4, 20, 16, 32, 4, 12],
			[8, 20, 8, 32, 4, 12], [12, 20, 16, 32, 4, 12], [44, 16, -8, 32, 4, 4], [48, 16, -8, 32, 4, 4],
			[40, 20, 0, 32, 4, 12], [44, 20, -8, 32, 4, 12], [48, 20, -16, 32, 4, 12], [52, 20, -8, 32, 4, 12]];
		var src = g.getImageData(0, 0, 64, 32);
		var out = g.getImageData(0, 0, 64, 64);
		copies.forEach(function (c) {
			for (var y = 0; y < c[5]; y++) {
				for (var x = 0; x < c[4]; x++) {
					var si = ((c[1] + y) * 64 + c[0] + x) * 4;
					var dx = c[0] + c[2] + (c[4] - 1 - x), dy = c[1] + c[3] + y;
					var di = (dy * 64 + dx) * 4;
					for (var k = 0; k < 4; k++) out.data[di + k] = src.data[si + k];
				}
			}
		});
		g.putImageData(out, 0, 0);
		// the "Notch hack": an old hat area with no transparency at all means no hat
		var hat = g.getImageData(32, 0, 32, 16), seeThrough = false;
		for (var i = 3; i < hat.data.length; i += 4) if (hat.data[i] < 128) { seeThrough = true; break; }
		if (!seeThrough) g.clearRect(32, 0, 32, 16);
	}

	function opaque(g, rects) {
		rects.forEach(function (r) {
			var d = g.getImageData(r[0], r[1], r[2], r[3]);
			for (var i = 3; i < d.data.length; i += 4) d.data[i] = 255;
			g.putImageData(d, r[0], r[1]);
		});
	}

	/** Draws a skin's face (with its hat layer) onto a canvas: guestbook avatars, the start menu... */
	function head(name, canvas) {
		var src = name.indexOf("/") >= 0 || name.indexOf("data:") === 0 ? name : TEX + name + ".png";
		canvas.width = 8;
		canvas.height = 8;
		return load(src).then(function (s) {
			var g = canvas.getContext("2d");
			g.imageSmoothingEnabled = false;
			g.clearRect(0, 0, 8, 8);
			g.drawImage(s.canvas, 8, 8, 8, 8, 0, 0, 8, 8);
			g.drawImage(s.canvas, 40, 8, 8, 8, 0, 0, 8, 8);
		}).catch(function () { /* leave it blank */ });
	}

	// ------------------------------------------------------------ the model
	function box(b, S, tex, tw, th) {
		var u = b[0], v = b[1], w = b[2], h = b[3], d = b[4];
		var el = div("box");
		var faces = [
			["front", u + d, v + d, w, h, "translateZ(" + d * S / 2 + "px)"],
			["back", u + 2 * d + w, v + d, w, h, "rotateY(180deg) translateZ(" + d * S / 2 + "px)"],
			["right", u, v + d, d, h, "rotateY(-90deg) translateZ(" + w * S / 2 + "px)"],
			["left", u + d + w, v + d, d, h, "rotateY(90deg) translateZ(" + w * S / 2 + "px)"],
			["top", u + d, v, w, d, "rotateX(90deg) translateZ(" + h * S / 2 + "px)"],
			["bottom", u + d + w, v, w, d, "rotateX(90deg) translateZ(" + -h * S / 2 + "px)"]
		];
		faces.forEach(function (f) {
			var face = div("face " + f[0]);
			var fw = f[3] * S, fh = f[4] * S;
			face.style.cssText = "width:" + fw + "px;height:" + fh + "px;left:" + -fw / 2 + "px;top:" + -fh / 2 + "px;" +
				"transform:" + f[5] + ";background-image:url(" + tex + ");background-size:" + tw * S + "px " + th * S + "px;" +
				"background-position:" + -f[1] * S + "px " + -f[2] * S + "px";
			el.appendChild(face);
		});
		return el;
	}

	function Model(host, opts) {
		opts = opts || {};
		this.host = host;
		this.opts = opts;
		this.S = opts.scale || 8;
		this.yaw = opts.yaw != null ? opts.yaw : -28;
		this.pitch = -10;
		this.spin = opts.spin != null ? opts.spin : 14; // degrees per second when idle
		this.vel = 0;
		this.layers = { hat: true, jacket: true, lsleeve: true, lpants: true, rpants: true, rsleeve: true, cape: true };
		this.capeOn = opts.cape !== false;
		this.state = { walk: false, sprint: false, sneak: false, use: false, air: 0, hurt: 0 };
		this.phase = 0;
		this.amp = 0;
		this.lean = 0;
		this.look = { x: 0, y: 0, tx: 0, ty: 0 };
		this.t = Math.random() * 10;
		this.visible = true;
		this.root = div("s3d");
		this.root.setAttribute("aria-hidden", "true");
		host.innerHTML = "";
		host.appendChild(this.root);
		if (opts.tag) {
			// a nametag that floats over the head and always faces you, like in game
			this.tag = div("s3d-tag");
			this.tag.textContent = opts.tag;
		}
		this.scene = div("s3d-scene");
		this.root.appendChild(this.scene);
		if (opts.hint) {
			var hint = div("s3d-hint");
			hint.textContent = opts.hint;
			this.root.appendChild(hint);
		}
		this.bind();
		this.watch();
	}

	Model.prototype.setSkin = function (src, slim) {
		var self = this;
		return load(src, slim).then(function (s) {
			self.skin = s;
			self.build();
			return s;
		});
	};

	Model.prototype.build = function () {
		var S = this.S, skin = this.skin, self = this;
		var P = parts(skin.slim);
		this.scene.innerHTML = "";
		if (this.opts.floor !== false) {
			// a retro grid floor under the feet, with a shadow that shrinks when you jump
			this.ground = div("ground");
			this.ground.style.cssText = "width:" + 44 * S + "px;height:" + 44 * S + "px;left:" + -22 * S + "px;top:" + -22 * S + "px;" +
				"transform:translate3d(0," + 16 * S + "px,0) rotateX(90deg);background-size:" + 4 * S + "px " + 4 * S + "px";
			this.shadow = div("shadow");
			this.shadow.style.cssText = "width:" + 14 * S + "px;height:" + 10 * S + "px;left:" + 15 * S + "px;top:" + 17 * S + "px";
			this.ground.appendChild(this.shadow);
			this.scene.appendChild(this.ground);
			this.groundY = 0;
		}
		this.player = div("player");
		this.upper = div("upper");
		this.player.appendChild(this.upper);
		this.joints = {};
		this.layerEls = {};
		Object.keys(P).forEach(function (name) {
			var p = P[name];
			var joint = div("joint " + name);
			var base = box(p.base, S, skin.url, 64, 64);
			base.style.transform = "translate3d(" + p.at[0] * S + "px," + p.at[1] * S + "px," + p.at[2] * S + "px)";
			var over = box(p.over, S, skin.url, 64, 64);
			var w = p.over[2], h = p.over[3], d = p.over[4];
			over.className = "box layer";
			over.style.transform = base.style.transform + " scale3d(" + (w + 2 * p.grow) / w + "," + (h + 2 * p.grow) / h + "," + (d + 2 * p.grow) / d + ")";
			joint.appendChild(base);
			joint.appendChild(over);
			(name === "rleg" || name === "lleg" ? self.player : self.upper).appendChild(joint);
			self.joints[name] = { el: joint, pivot: p.pivot };
			self.layerEls[p.layer] = over;
		});
		// the cape hangs off the back of the body, outer side facing backwards
		var capeJoint = div("joint cape");
		var capeBox = box([0, 0, 10, 16, 1], S, TEX + "cape.png", 64, 32);
		capeBox.className = "box layer";
		capeBox.style.transform = "rotateY(180deg) translate3d(0," + 8 * S + "px," + 0.5 * S + "px)";
		capeJoint.appendChild(capeBox);
		this.upper.appendChild(capeJoint);
		this.joints.cape = { el: capeJoint, pivot: [0, 0, 2.2] };
		this.layerEls.cape = capeBox;
		if (this.tag) this.player.appendChild(this.tag);
		this.scene.appendChild(this.player);
		this.applyLayers();
		this.pose(0);
	};

	Model.prototype.setLayers = function (states) {
		for (var k in states) this.layers[k] = states[k];
		this.applyLayers();
	};

	Model.prototype.applyLayers = function () {
		if (!this.layerEls) return;
		for (var k in this.layerEls) {
			var on = this.layers[k] && (k !== "cape" || this.capeOn);
			this.layerEls[k].classList.toggle("off", !on);
		}
	};

	Model.prototype.setCape = function (on) {
		this.capeOn = on;
		this.applyLayers();
	};

	Model.prototype.hurt = function () {
		var self = this;
		this.state.hurt = 0.5;
		this.root.classList.remove("hurt");
		void this.root.offsetWidth;
		this.root.classList.add("hurt");
		clearTimeout(this.hurtTimer);
		this.hurtTimer = setTimeout(function () { self.root.classList.remove("hurt"); }, 400);
	};

	Model.prototype.jump = function () {
		if (this.state.air <= 0) this.state.air = 0.62;
	};

	/** What SkinBlink's triggers look at, read off the simulated player. */
	Model.prototype.status = function () {
		var s = this.state;
		var moving = s.walk || s.sprint;
		return {
			air: s.air > 0, ground: s.air <= 0, moving: moving, still: !moving,
			sneak: s.sneak, sprint: s.sprint && moving && !s.sneak, hurt: s.hurt > 0, use: s.use
		};
	};

	Model.prototype.pose = function (dt) {
		if (!this.player) return;
		var S = this.S, s = this.state;
		this.t += dt;
		var t = this.t;
		var moving = s.walk || s.sprint;
		var sprinting = s.sprint && moving && !s.sneak;
		var targetAmp = moving ? (sprinting ? 1 : s.sneak ? 0.45 : 0.75) : 0;
		this.amp += (targetAmp - this.amp) * Math.min(1, dt * 8);
		this.phase += dt * (sprinting ? 13 : s.sneak ? 5 : 8.5) * (moving ? 1 : 0.4);
		this.lean += ((s.sneak ? 28 : 0) - this.lean) * Math.min(1, dt * 14);
		if (s.air > 0) s.air = Math.max(0, s.air - dt);
		if (s.hurt > 0) s.hurt = Math.max(0, s.hurt - dt);

		var swing = Math.sin(this.phase) * 42 * this.amp;
		var idle = reduced ? 0 : Math.sin(t * 1.2);
		var jumpH = s.air > 0 ? Math.sin(Math.PI * (1 - s.air / 0.62)) * 9 : 0;
		var knock = s.hurt > 0 ? Math.sin((0.5 - s.hurt) / 0.5 * Math.PI) * 10 : 0;
		var bob = moving && s.air <= 0 ? Math.abs(Math.cos(this.phase)) * 0.6 * this.amp : 0;

		this.player.style.transform = "translate3d(0," + ((-8 - jumpH - bob + this.lean / 14) * S) + "px,0) rotateX(" + -knock + "deg)";
		this.upper.style.transform = "translate3d(0," + 12 * S + "px,0) rotateX(" + -this.lean + "deg) translate3d(0," + -12 * S + "px,0)";

		var self = this;
		function set(name, rx, ry, rz) {
			var j = self.joints[name];
			if (!j) return;
			j.el.style.transform = "translate3d(" + j.pivot[0] * S + "px," + j.pivot[1] * S + "px," + -j.pivot[2] * S + "px) rotateY(" + (ry || 0) + "deg) rotateX(" + rx + "deg) rotateZ(" + (rz || 0) + "deg)";
		}
		var armOut = 2.5 + idle * 2;
		var rArm = -swing + idle * 2.5 + (s.sneak ? 22 : 0) + (s.air > 0 ? 10 : 0);
		if (s.use) rArm = 58 + Math.sin(t * 18) * 5;
		set("rarm", rArm, 0, armOut + (s.air > 0 ? 8 : 0));
		set("larm", swing - idle * 2.5 + (s.sneak ? 22 : 0) + (s.air > 0 ? 10 : 0), 0, -armOut - (s.air > 0 ? 8 : 0));
		set("rleg", swing * 0.95, 0, s.air > 0 ? 3 : 0);
		set("lleg", -swing * 0.95, 0, s.air > 0 ? -3 : 0);
		// while you hover, the head turns to stare at you, however the body is turned
		var ny = ((this.yaw % 360) + 540) % 360 - 180;
		var face = Math.max(0, Math.min(1, (150 - Math.abs(ny)) / 60));
		var wantX = this.hover ? Math.max(-55, Math.min(55, -ny + this.look.tx)) * face : 0;
		var wantY = this.hover ? this.look.ty * face : 0;
		this.look.x += (wantX - this.look.x) * Math.min(1, dt * 6);
		this.look.y += (wantY - this.look.y) * Math.min(1, dt * 6);
		set("head", this.look.y + (s.sneak ? -8 : 0) + (s.use ? -8 : 0), this.look.x, 0);
		set("body", 0, this.look.x * 0.15, 0);
		var capeSwing = 6 + this.amp * 26 + (s.air > 0 ? 18 : 0) + idle * 1.5 + (s.sneak ? 12 : 0);
		set("cape", -capeSwing, 0, Math.sin(this.phase * 0.5) * 2 * this.amp);
		this.scene.style.transform = "rotateX(" + this.pitch + "deg) rotateY(" + this.yaw + "deg)";
		if (this.tag) this.tag.style.transform = "translate3d(0," + (-10.6 + this.lean / 7) * S + "px,0) rotateY(" + -this.yaw + "deg) rotateX(" + -this.pitch + "deg) translate(-50%,-50%)";
		if (this.ground) {
			// the floor slides backwards under your feet while you walk
			this.groundY -= dt * (moving ? (sprinting ? 7 : s.sneak ? 2 : 4.3) : 0) * S;
			this.ground.style.backgroundPosition = "50% " + this.groundY.toFixed(1) + "px";
			this.shadow.style.transform = "scale(" + (1 - jumpH / 20).toFixed(3) + ")";
			this.shadow.style.opacity = (1 - jumpH / 16).toFixed(3);
		}
	};

	Model.prototype.bind = function () {
		var self = this, root = this.root;
		var drag = null;
		if (this.opts.interactive === false) {
			root.style.cursor = "inherit";
			return;
		}
		root.addEventListener("pointerdown", function (e) {
			if (e.button !== 0) return;
			drag = { x: e.clientX, y: e.clientY, yaw: self.yaw, pitch: self.pitch, moved: 0, last: e.clientX, t: performance.now() };
			root.setPointerCapture(e.pointerId);
			root.classList.add("dragging");
		});
		root.addEventListener("pointermove", function (e) {
			var r = root.getBoundingClientRect();
			// the head follows the cursor, like the player preview in your inventory
			self.hover = true;
			self.look.tx = Math.max(-40, Math.min(40, (e.clientX - r.left - r.width / 2) / r.width * 80));
			self.look.ty = Math.max(-30, Math.min(30, -(e.clientY - r.top - r.height * 0.3) / r.height * 60));
			if (!drag) return;
			var dx = e.clientX - drag.x, dy = e.clientY - drag.y;
			drag.moved = Math.max(drag.moved, Math.abs(dx) + Math.abs(dy));
			self.yaw = drag.yaw + dx * 0.6;
			self.pitch = Math.max(-35, Math.min(20, drag.pitch - dy * 0.3));
			var now = performance.now();
			self.vel = (e.clientX - drag.last) / Math.max(1, now - drag.t) * 600;
			drag.last = e.clientX;
			drag.t = now;
		});
		function end(e) {
			if (!drag) return;
			var wasClick = drag.moved < 5;
			drag = null;
			root.classList.remove("dragging");
			if (wasClick && self.opts.onClick) self.opts.onClick(e);
		}
		root.addEventListener("pointerup", end);
		root.addEventListener("pointercancel", end);
		root.addEventListener("pointerleave", function () {
			if (!drag) self.hover = false;
		});
		this.isDragging = function () { return !!drag; };
	};

	Model.prototype.watch = function () {
		var self = this;
		if ("IntersectionObserver" in window) {
			new IntersectionObserver(function (entries) {
				self.visible = entries[0].isIntersecting;
				if (self.visible) self.loop();
			}).observe(this.root);
		}
		document.addEventListener("visibilitychange", function () { if (!document.hidden) self.loop(); });
		this.loop();
	};

	Model.prototype.loop = function () {
		if (this.running) return;
		this.running = true;
		var self = this, last = performance.now();
		function frame(now) {
			var dt = Math.min(0.05, (now - last) / 1000);
			last = now;
			if (!self.visible || document.hidden) { self.running = false; return; }
			var dragging = self.isDragging && self.isDragging();
			if (!dragging) {
				if (Math.abs(self.vel) > 1) {
					self.yaw += self.vel * dt;
					self.vel *= Math.pow(0.04, dt);
				} else if (!reduced) {
					self.yaw += self.spin * dt;
				}
			}
			if (self.onFrame) self.onFrame(dt);
			self.pose(reduced ? 0 : dt);
			requestAnimationFrame(frame);
		}
		requestAnimationFrame(frame);
	};

	// ------------------------------------------------------------ SkinBlink, ported
	function Blink(opts) {
		opts = opts || {};
		this.pattern = opts.pattern || "Wave";
		this.interval = opts.interval || 3;
		this.cape = opts.cape !== false;
		this.trigger = opts.trigger || "Always";
		this.blinking = false;
		this.ticks = 0;
		this.step = 0;
		this.states = ORDER.map(function () { return true; });
		this.packets = 0;
	}

	Blink.prototype.triggered = function (st) {
		switch (this.trigger) {
			case "In Air": return st.air;
			case "On Ground": return st.ground;
			case "Moving": return st.moving;
			case "Still": return st.still;
			case "Sneaking": return st.sneak;
			case "Sprinting": return st.sprint;
			case "Hurt": return st.hurt;
			case "Using Item": return st.use;
			default: return true;
		}
	};

	/** One game tick. Returns true when it "sent a packet" (changed the layers). */
	Blink.prototype.tick = function (st) {
		var i;
		if (!this.triggered(st)) {
			if (this.blinking) {
				this.blinking = false;
				for (i = 0; i < ORDER.length; i++) this.states[i] = true;
				this.packets++;
				return true;
			}
			return false;
		}
		this.blinking = true;
		if (++this.ticks < this.interval) return false;
		this.ticks = 0;
		this.step++;
		for (i = 0; i < ORDER.length; i++) {
			if (ORDER[i] === "cape" && !this.cape) { this.states[i] = true; continue; }
			switch (this.pattern) {
				case "Blink": this.states[i] = this.step % 2 === 0; break;
				case "Random": this.states[i] = Math.random() < 0.5; break;
				case "Strobe": this.states[i] = this.step % 4 === 0; break;
				// a single gap travels up the body: hat, jacket, arms, legs...
				default: this.states[i] = i !== this.step % ORDER.length;
			}
		}
		this.packets++;
		return true;
	};

	Blink.prototype.layers = function () {
		var out = {};
		for (var i = 0; i < ORDER.length; i++) out[ORDER[i]] = this.states[i];
		return out;
	};

	// ------------------------------------------------------------ ready-made blinking models
	/** A self-running model: [data-skin3d="mini"] in the sidebar, "feature" on the home page,
	    "skin:<name>" for the skins row. */
	function auto(host) {
		var kind = host.getAttribute("data-skin3d");
		var name = kind.indexOf("skin:") === 0 ? kind.slice(5) : "blink";
		var small = kind === "mini" || kind.indexOf("skin:") === 0;
		var w = host.clientWidth || 120, h = host.clientHeight || 130;
		var S = Math.max(2, Math.floor(Math.min(w / 22, h / (kind === "feature" ? 44 : 38))));
		var model = new Model(host, {
			scale: S, spin: small ? 30 : 18, yaw: small ? (Math.random() * 80 - 40) : -24,
			interactive: !small || kind.indexOf("skin:") === 0, cape: name === "blink" || name === "troll",
			hint: kind === "feature" ? "drag to spin · click to punch" : null,
			tag: kind === "feature" ? "you" : null,
			onClick: function () { model.hurt(); }
		});
		var blink = new Blink({ pattern: kind === "mini" ? "Wave" : kind === "feature" ? "Wave" : ["Blink", "Wave", "Random", "Strobe"][Math.floor(Math.random() * 4)], interval: 3 });
		var acc = 0;
		model.onFrame = function (dt) {
			if (reduced) return;
			acc += dt;
			while (acc >= 0.05) {
				acc -= 0.05;
				if (blink.tick(model.status())) model.setLayers(blink.layers());
			}
		};
		model.setSkin(TEX + name + ".png");
		host.troll = { model: model, blink: blink };
		return model;
	}

	function init() {
		document.querySelectorAll("[data-skin3d]").forEach(function (host) {
			if (host.troll) return;
			// wait until it's laid out so it can size itself
			if (host.clientWidth) auto(host);
			else requestAnimationFrame(function () { auto(host); });
		});
	}

	window.TrollSkin = { Model: Model, Blink: Blink, load: load, head: head, ORDER: ORDER, LABELS: ORDER_LABELS, SKINS: SKINS, TEX: TEX };
	if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
	else init();
})();
