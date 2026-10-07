/* TROLL-VISION 2000: channel 1 is the showcase video, 2 to 6 are the shaders. It switches itself
   on (CRT line and all) when you scroll to it, and changing channel costs you a burst of static. */
(function () {
	"use strict";

	var tv = document.getElementById("tv-set");
	if (!tv) return;
	var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
	var video = tv.querySelector("video");
	var stills = Array.prototype.slice.call(tv.querySelectorAll(".still"));
	var chans = Array.prototype.slice.call(tv.querySelectorAll(".chan"));
	var noise = tv.querySelector("canvas.static");
	var osd = tv.querySelector(".osd"), knob = tv.querySelector(".knob"), power = tv.querySelector(".power");
	var g = noise.getContext("2d");
	var NAMES = { 1: "showcase", 2: "noir", 3: "crt", 4: "dither", 5: "halftone", 6: "ink" };
	var ch = 1, state = "off", turns = 0, osdTimer, staticTimer;
	var sound = window.TrollSite ? window.TrollSite.sound : null;

	noise.width = 160;
	noise.height = 90;
	var img = g.createImageData(160, 90), px = new Uint32Array(img.data.buffer);
	function snow() {
		for (var i = 0; i < px.length; i++) {
			var v = Math.random() * 255 | 0;
			// the odd brighter band rolling through, like a bad signal
			px[i] = (255 << 24) | (v << 16) | (v << 8) | v;
		}
		var band = (performance.now() / 6) % 90 | 0;
		for (var x = 0; x < 160; x++) px[band * 160 + x] = 0xffdddddd;
		g.putImageData(img, 0, 0);
	}

	function showOsd() {
		osd.querySelector(".ch").textContent = "CH " + String(ch).padStart(2, "0");
		osd.querySelector("small").textContent = NAMES[ch];
		osd.classList.add("show");
		clearTimeout(osdTimer);
		osdTimer = setTimeout(function () { osd.classList.remove("show"); }, 2200);
	}

	function tune(n) {
		if (state !== "on") { powerOn(n); return; }
		ch = n;
		chans.forEach(function (c) { c.classList.toggle("on", +c.getAttribute("data-ch") === ch); });
		if (ch !== 1 && !video.paused) video.pause();
		// a burst of static between channels
		noise.classList.add("on");
		if (sound) sound.blip(90 + Math.random() * 60, 0.22, "sawtooth", 0.02);
		var t0 = performance.now();
		cancelAnimationFrame(staticTimer);
		(function fizz(now) {
			snow();
			if (now - t0 < (reduced ? 0 : 260)) staticTimer = requestAnimationFrame(fizz);
			else {
				noise.classList.remove("on");
				stills.forEach(function (s) { s.classList.toggle("on", +s.getAttribute("data-ch") === ch); });
				video.style.visibility = ch === 1 ? "visible" : "hidden";
			}
		})(t0);
		showOsd();
	}

	function powerOn(n) {
		if (state === "on" || state === "warming") return;
		state = "warming";
		tv.classList.remove("off", "switching-off");
		tv.classList.add("powering");
		if (sound) sound.blip(60, 0.5, "sine", 0.05, 3000);
		setTimeout(function () {
			tv.classList.remove("powering");
			tv.classList.add("on");
			state = "on";
			tune(n || ch);
		}, reduced ? 0 : 700);
	}

	function powerOff() {
		if (state !== "on") return;
		state = "off";
		video.pause();
		tv.classList.remove("on");
		tv.classList.add("switching-off");
		if (sound) sound.blip(3000, 0.35, "sine", 0.04, 60);
		setTimeout(function () { tv.classList.remove("switching-off"); tv.classList.add("off"); }, reduced ? 0 : 500);
	}

	chans.forEach(function (c) {
		c.addEventListener("click", function () { tune(+c.getAttribute("data-ch")); });
	});
	knob.addEventListener("click", function () {
		turns++;
		knob.style.transform = "rotate(" + turns * 60 + "deg)";
		tune(ch % 6 + 1);
	});
	power.addEventListener("click", function () {
		if (state === "on") powerOff();
		else powerOn();
	});
	video.addEventListener("play", function () { if (ch !== 1) tune(1); });

	// switch on by itself the first time it scrolls into view
	if ("IntersectionObserver" in window) {
		var io = new IntersectionObserver(function (en) {
			if (en[0].isIntersecting) { io.disconnect(); powerOn(1); }
		}, { threshold: 0.5 });
		io.observe(tv);
	} else {
		powerOn(1);
	}
})();
