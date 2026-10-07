/* A working, browser-sized copy of the in-game menu (ClickGuiScreen).
   Same layout and controls: left click toggles, right click opens settings,
   type to search, the palette button cycles the (whole site's) theme. */
(function () {
	"use strict";

	var root = document.getElementById("gui-demo");
	if (!root || !window.TROLL) return;
	var data = window.TROLL;
	var state = { category: "TROLL", selected: null, query: "", on: {}, values: {} };
	var QUIPS = ["ready to ruin someone's day", "all systems mischievous", "twerk.exe loaded", "no warranty. no regrets."];

	function el(tag, cls, html) {
		var e = document.createElement(tag);
		if (cls) e.className = cls;
		if (html !== undefined) e.innerHTML = html;
		return e;
	}

	function esc(s) {
		return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; });
	}

	function catOf(id) {
		return data.categories.filter(function (c) { return c.id === id; })[0];
	}

	function modulesIn(cat) {
		return data.modules.filter(function (m) { return m.category === cat; });
	}

	function visible() {
		var q = state.query.trim().toLowerCase();
		if (!q) return modulesIn(state.category);
		var name = [], desc = [], setting = [];
		data.modules.forEach(function (m) {
			if (m.name.toLowerCase().indexOf(q) >= 0) name.push(m);
			else if (m.desc.toLowerCase().indexOf(q) >= 0) desc.push(m);
			else if (m.settings.some(function (s) { return s.name.toLowerCase().indexOf(q) >= 0; })) setting.push(m);
		});
		return name.concat(desc, setting);
	}

	function log(line) {
		var l = root.querySelector(".gui-footer .log");
		if (!l) return;
		var i = 0;
		clearInterval(log.timer);
		log.timer = setInterval(function () {
			l.textContent = line.slice(0, ++i);
			if (i >= line.length) clearInterval(log.timer);
		}, 18);
	}

	// ------------------------------------------------------------ skeleton
	root.innerHTML = "";
	root.classList.add("gui");
	var header = el("div", "gui-header");
	header.appendChild(el("div", "gui-logo", '<span class="tag">TROLL</span> CLIENT <span class="dim">v1.0</span>'));
	var search = el("input", "gui-search");
	search.type = "text";
	search.placeholder = "type to search";
	search.setAttribute("aria-label", "Search modules");
	header.appendChild(search);
	var palette = el("span", "gui-btn clicky", '<img class="icon" src="img/icons/palette.png" alt="cycle theme">');
	palette.title = "Theme (click to cycle, right click goes back)";
	header.appendChild(palette);
	root.appendChild(header);

	var body = el("div", "gui-body");
	var side = el("div", "gui-side");
	body.appendChild(side);
	var title = el("div", "gui-title");
	body.appendChild(title);
	var listCol = el("div", "gui-col");
	var setCol = el("div", "gui-col settings-col");
	body.appendChild(listCol);
	body.appendChild(setCol);
	root.appendChild(body);
	var footer = el("div", "gui-footer", '<span class="prompt">you@troll:~$</span><span class="log"></span>');
	root.appendChild(footer);
	var tip = el("div", "gui-tip");
	root.appendChild(tip);

	// ------------------------------------------------------------ sidebar
	function renderSide() {
		side.innerHTML = "";
		data.categories.forEach(function (c) {
			var mods = modulesIn(c.id).filter(function (m) { return m.toggleable; });
			var on = mods.filter(function (m) { return state.on[m.name]; }).length;
			var tab = el("a", "gui-tab" + (c.id === state.category && !state.query ? " on" : ""));
			tab.href = "#";
			tab.setAttribute("aria-label", c.name);
			var pips = mods.map(function (m, i) { return '<i class="' + (i < on ? "lit" : "") + '"></i>'; }).join("");
			tab.innerHTML = '<img src="img/icons/' + c.icon + '.png" alt=""><div class="pips">' + pips + "</div>" +
				'<span class="fly">' + c.name.toLowerCase() + (mods.length ? ' <span class="dim">' + on + "/" + mods.length + "</span>" : "") + "</span>";
			tab.addEventListener("click", function (e) {
				e.preventDefault();
				state.category = c.id;
				state.query = "";
				search.value = "";
				state.selected = null;
				log("cd ~/" + c.name.toLowerCase());
				render();
			});
			side.appendChild(tab);
		});
	}

	// ------------------------------------------------------------ module list
	function renderList() {
		var mods = visible();
		var q = state.query.trim();
		var cat = catOf(state.category);
		var toggleable = mods.filter(function (m) { return m.toggleable; });
		var on = toggleable.filter(function (m) { return state.on[m.name]; }).length;
		title.innerHTML = q
			? "<b>search</b><span class=\"dim\">" + mods.length + " result" + (mods.length === 1 ? "" : "s") + " for \"" + esc(q) + "\"</span>"
			: "<b>" + cat.name.toLowerCase() + "</b><span class=\"dim\">" + esc(cat.tagline) + "</span>";
		title.innerHTML += toggleable.length ? '<span class="count">' + on + "/" + toggleable.length + " on</span>" : "";

		listCol.innerHTML = "";
		var box = el("div", "group");
		box.appendChild(el("span", "legend", "modules <span class=\"dim\">" + mods.length + "</span>"));
		var ul = el("ul", "gui-mods");
		mods.forEach(function (m) {
			var li = el("li", "gui-mod clicky" + (state.on[m.name] ? " on" : "") + (state.selected === m.name ? " sel" : ""));
			li.setAttribute("data-name", m.name);
			var name = esc(m.name);
			if (q) {
				var i = m.name.toLowerCase().indexOf(q.toLowerCase());
				if (i >= 0) name = esc(m.name.slice(0, i)) + "<mark>" + esc(m.name.slice(i, i + q.length)) + "</mark>" + esc(m.name.slice(i + q.length));
			}
			li.innerHTML = (m.toggleable ? '<span class="box"></span>' : '<img class="icon" src="img/icons/gear.png" alt="">') +
				'<span class="name">' + name + "</span>" +
				'<span class="eq"><i></i><i></i><i></i></span>' +
				(q ? '<span class="cat">' + catOf(m.category).name.toLowerCase() + "</span>" : "") + '<span class="dim">&gt;</span>';
			li.addEventListener("click", function () {
				if (!m.toggleable) { select(m); return; }
				state.on[m.name] = !state.on[m.name];
				log(m.name.toLowerCase() + (state.on[m.name] ? " --enable" : " --disable"));
				if (window.TrollSite) window.TrollSite.bump("toggles", 1);
				render();
				if (state.on[m.name]) {
					var row = listCol.querySelector('[data-name="' + m.name + '"]');
					if (row) row.classList.add("shine");
				}
			});
			li.addEventListener("contextmenu", function (e) {
				e.preventDefault();
				select(m);
			});
			li.addEventListener("mouseenter", function (e) { showTip(e, m.desc + "\nleft: toggle   right: settings"); });
			li.addEventListener("mousemove", moveTip);
			li.addEventListener("mouseleave", hideTip);
			ul.appendChild(li);
		});
		if (!mods.length) ul.appendChild(el("li", "gui-empty", "nothing here.<br>try \"twerk\""));
		box.appendChild(ul);
		listCol.appendChild(box);
	}

	function select(m) {
		if (state.selected === m.name) {
			state.selected = null;
			log("exit");
		} else {
			state.selected = m.name;
			log("vim ~/" + catOf(m.category).name.toLowerCase() + "/" + m.name.toLowerCase() + ".cfg");
		}
		render();
	}

	// ------------------------------------------------------------ settings
	function value(m, s) {
		var key = m.name + "." + s.name;
		return key in state.values ? state.values[key] : s.value;
	}

	function setValue(m, s, v) {
		state.values[m.name + "." + s.name] = v;
	}

	function fmt(s, v) {
		var whole = s.step >= 1 && Math.abs(s.step - Math.round(s.step)) < 1e-9;
		return (whole ? Math.round(v) : v.toFixed(s.step >= 0.1 ? 1 : 2)) + (s.unit || "");
	}

	function renderSettings() {
		setCol.innerHTML = "";
		var box = el("div", "group gui-settings");
		var m = data.modules.filter(function (x) { return x.name === state.selected; })[0];
		if (!m) {
			box.appendChild(el("span", "legend", "settings"));
			box.appendChild(el("div", "gui-empty", "right click a module<br><span class=\"dim\">to tweak its settings</span>"));
			setCol.appendChild(box);
			return;
		}
		box.appendChild(el("span", "legend", esc(m.name.toLowerCase()) + '<span class="blink">_</span>'));
		box.appendChild(el("div", "desc", esc(m.desc)));
		if (m.toggleable) {
			box.appendChild(switchRow("Enabled", "Turn the module on or off", !!state.on[m.name], function (v) {
				state.on[m.name] = v;
				if (window.TrollSite) window.TrollSite.bump("toggles", 1);
				log(m.name.toLowerCase() + (v ? " --enable" : " --disable"));
				render();
			}, false));
		}
		m.settings.forEach(function (s) {
			var v = value(m, s);
			var changed = JSON.stringify(v) !== JSON.stringify(s.value);
			var row;
			if (s.type === "bool") {
				row = switchRow(s.name, s.desc, v, function (nv) { setValue(m, s, nv); renderSettings(); }, changed);
			} else if (s.type === "number") {
				row = el("div", "gui-row" + (changed ? " changed" : ""));
				row.innerHTML = '<span class="lbl">' + esc(s.name) + '</span><span class="val">' + fmt(s, v) + "</span>";
				var range = el("input");
				range.type = "range";
				range.min = s.min;
				range.max = s.max;
				range.step = s.step;
				range.value = v;
				range.setAttribute("aria-label", s.name);
				range.addEventListener("input", function () {
					setValue(m, s, parseFloat(range.value));
					row.querySelector(".val").textContent = fmt(s, parseFloat(range.value));
					row.classList.toggle("changed", parseFloat(range.value) !== s.value);
				});
				row.addEventListener("contextmenu", function (e) {
					e.preventDefault();
					setValue(m, s, s.value);
					renderSettings();
				});
				row.appendChild(range);
			} else if (s.type === "mode") {
				row = el("div", "gui-row" + (changed ? " changed" : ""));
				row.innerHTML = '<span class="lbl">' + esc(s.name) + "</span>";
				var total = s.modes.join("").length + s.modes.length * 2;
				if (total <= 26) {
					// short lists: segmented control, like the client
					var seg = el("div", "seg");
					s.modes.forEach(function (mode) {
						var o = el("span", "clicky" + (mode === v ? " on" : ""), esc(mode));
						o.addEventListener("click", function () { setValue(m, s, mode); renderSettings(); });
						seg.appendChild(o);
					});
					row.appendChild(seg);
				} else {
					// long lists collapse into a cycler: left click forward, right click back
					var cyc = el("span", "cycler clicky", '<span class="dim">&lt;</span> ' + esc(v) + ' <span class="dim">&gt;</span>');
					var step = function (dir) {
						var i = (s.modes.indexOf(v) + dir + s.modes.length) % s.modes.length;
						setValue(m, s, s.modes[i]);
						renderSettings();
					};
					cyc.addEventListener("click", function () { step(1); });
					cyc.addEventListener("contextmenu", function (e) { e.preventDefault(); step(-1); });
					row.appendChild(cyc);
				}
			} else if (s.type === "text") {
				row = el("div", "gui-row" + (changed ? " changed" : ""));
				row.innerHTML = '<span class="lbl">' + esc(s.name) + "</span>";
				var input = el("input", "text");
				input.value = v;
				input.placeholder = "type...";
				input.setAttribute("aria-label", s.name);
				input.addEventListener("input", function () { setValue(m, s, input.value); });
				row.appendChild(input);
			} else if (s.type === "color") {
				row = el("div", "gui-row");
				row.innerHTML = '<span class="lbl">' + esc(s.name) + '</span><span class="val">' + esc(v) +
					'</span><span class="swatch" style="background:' + esc(v) + '"></span>';
			}
			if (row) {
				row.addEventListener("mouseenter", function (e) {
					showTip(e, s.desc + (changed ? "\nchanged (default: " + (s.type === "number" ? fmt(s, s.value) : s.value) + ")" : ""));
				});
				row.addEventListener("mousemove", moveTip);
				row.addEventListener("mouseleave", hideTip);
				box.appendChild(row);
			}
		});
		setCol.appendChild(box);
	}

	function switchRow(label, desc, on, onChange, changed) {
		var row = el("div", "gui-row clicky" + (changed ? " changed" : ""));
		row.innerHTML = '<span class="lbl">' + esc(label) + '</span><span class="switch' + (on ? " on" : "") + '">' +
			(on ? "on" : "off") + '<span class="track"><span class="knob"></span></span></span>';
		row.addEventListener("click", function () { onChange(!on); });
		return row;
	}

	// ------------------------------------------------------------ tooltip (typewriter, like the client)
	function showTip(e, text) {
		clearTimeout(showTip.timer);
		clearInterval(showTip.typer);
		showTip.timer = setTimeout(function () {
			var lines = text.split("\n");
			tip.innerHTML = "";
			tip.style.display = "block";
			var full = lines.map(esc);
			var shown = 0, total = text.length;
			showTip.typer = setInterval(function () {
				shown += 6;
				var left = shown, html = [];
				full.forEach(function (l, i) {
					var raw = lines[i];
					var part = esc(raw.slice(0, Math.max(0, left)));
					html.push(i === 0 ? part : '<span class="dim">' + part + "</span>");
					left -= raw.length;
				});
				tip.innerHTML = html.join("<br>");
				if (shown >= total) clearInterval(showTip.typer);
			}, 16);
			moveTip(e);
		}, 400);
	}

	function moveTip(e) {
		var r = root.getBoundingClientRect();
		var x = e.clientX - r.left + 14, y = e.clientY - r.top + 16;
		if (x + 290 > r.width) x = Math.max(4, e.clientX - r.left - 290);
		tip.style.left = x + "px";
		tip.style.top = y + "px";
	}

	function hideTip() {
		clearTimeout(showTip.timer);
		clearInterval(showTip.typer);
		tip.style.display = "none";
	}

	// ------------------------------------------------------------ wiring
	function render() {
		renderSide();
		renderList();
		renderSettings();
	}

	search.addEventListener("input", function () {
		state.query = search.value;
		renderSide();
		renderList();
	});
	search.addEventListener("keydown", function (e) {
		if (e.key === "Enter") {
			var hits = visible();
			if (hits.length) select(hits[0]);
		}
		if (e.key === "Escape") {
			search.value = "";
			state.query = "";
			render();
		}
	});
	root.addEventListener("keydown", function (e) {
		// type-to-search, same as the client
		if (e.target === search || e.ctrlKey || e.metaKey || e.altKey || e.key.length !== 1 || !/[a-z0-9]/i.test(e.key)) return;
		search.focus();
	});
	root.tabIndex = 0;
	palette.addEventListener("click", function (e) {
		if (window.TrollSite) window.TrollSite.cycleTheme(1, { x: e.clientX, y: e.clientY });
		log("theme --preset " + (localStorage.getItem("troll-theme") || "noir"));
	});
	palette.addEventListener("contextmenu", function (e) {
		e.preventDefault();
		if (window.TrollSite) window.TrollSite.cycleTheme(-1, { x: e.clientX, y: e.clientY });
	});

	// start on Troll with Twerk open, so there's something to look at
	state.selected = "Twerk";
	render();
	log("hi, " + QUIPS[Math.floor(Math.random() * QUIPS.length)]);
})();
