package com.trollclient.gui.hud;

import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Theme;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.mixin.ToastManagerAccessor;
import com.trollclient.module.client.GuiToolsModule;
import com.trollclient.module.client.HudModule;
import com.trollclient.module.client.KeystrokesModule;
import com.trollclient.module.combat.KillStreak;
import com.trollclient.macro.MacroManager;
import com.trollclient.macro.MacroRun;
import com.trollclient.packet.PacketGate;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Watermark, array list, coordinates and notifications. */
public final class HudRenderer {
	private static final Map<Module, Anim> SLIDE = new HashMap<>();
	/** How far the right-hand array list is pushed down to make room for vanilla toasts. */
	private static final Anim TOAST_PUSH = new Anim(10);
	private static long lastNanos;

	private HudRenderer() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		HudModule hud = ModuleManager.get(HudModule.class);
		if (mc.gui.hud.isHidden() || mc.player == null) {
			return;
		}
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		Theme.update();
		int width = g.guiWidth();
		int height = g.guiHeight();
		float prevAlpha = Draw.alpha;
		Draw.alpha = 1f;

		if (hud.isEnabled()) {
			if (hud.watermark.get()) {
				watermark(g, hud, mc);
			}
			if (hud.radar.get()) {
				Radar.render(g, 4, radarTop(hud), hud.radarSize.getFloat(), hud.radarRange.getFloat(), dt);
			}
			if (hud.targetHud.get()) {
				TargetHud.render(g, width, height, dt);
			}
			if (hud.arrayList.get()) {
				arrayList(g, hud, mc, width, dt);
			}
			if (hud.coords.get()) {
				coords(g, mc, height);
			}
		}
		KeystrokesModule keys = ModuleManager.get(KeystrokesModule.class);
		if (keys.isEnabled()) {
			keys.render(g, width, height, dt);
		}
		KillStreak streak = ModuleManager.get(KillStreak.class);
		if (streak.isEnabled()) {
			streak.render(g, width, height);
		}
		Notifications.render(g, width, height, dt);
		badges(g, width);
		Draw.alpha = prevAlpha;
	}

	/**
	 * Top-centre badges for states that are easy to forget about: GUI packets
	 * being held or thrown away, and macros running.
	 */
	private static void badges(GuiGraphicsExtractor g, int width) {
		List<String> lines = new ArrayList<>();
		GuiToolsModule tools = ModuleManager.get(GuiToolsModule.class);
		if (tools.warning.get() && PacketGate.isHolding()) {
			if (!PacketGate.isSending()) {
				lines.add("gui packets: dropping (" + PacketGate.dropped() + ")");
			} else {
				lines.add("gui packets: " + PacketGate.queued() + " held" + (PacketGate.isDelaying() ? "" : ", delay off"));
			}
		}
		for (MacroRun run : MacroManager.runs()) {
			lines.add("macro " + run.macro().getName().toLowerCase(Locale.ROOT) + " " + (run.index() + 1) + "/" + run.macro().steps.size());
		}
		float y = 4;
		boolean blink = (int) (Motion.time() * 2) % 2 == 0;
		for (String line : lines) {
			float w = Theme.width(line) + 14;
			float x = (width - w) / 2f;
			Draw.panel(g, x, y, w, 12, Theme.a(ColorUtil.withAlpha(Theme.panel, 220)));
			Draw.panelOutline(g, x, y, w, 12, Theme.border);
			Draw.rect(g, x + 3, y + 4, 4, 4, blink ? Theme.accent() : Theme.textDim);
			Draw.text(g, line, x + 10, y + 2, Theme.text);
			y += 14;
		}
	}

	private static void watermark(GuiGraphicsExtractor g, HudModule hud, Minecraft mc) {
		String text = hud.watermarkText.get().isBlank() ? "Troll Client" : hud.watermarkText.get();
		float x = 4;
		float y = 4;
		switch (hud.watermarkStyle.get()) {
			case "Block" -> {
				float w = Theme.width(text) + 8;
				Draw.gradientH(g, x, y, w, 12, p -> Theme.accent(p / 1000f));
				Draw.text(g, text, x + 4, y + 2, Theme.onAccent(), false);
			}
			case "Terminal" -> {
				String prompt = "~$ " + text.toLowerCase(Locale.ROOT);
				Draw.rect(g, x - 2, y - 2, Theme.width(prompt) + 12, 13, Theme.a(ColorUtil.withAlpha(Theme.bg, 170)));
				Draw.text(g, prompt, x + 1, y + 1, Theme.text);
				if ((int) (Motion.time() * 2.2f) % 2 == 0) {
					Draw.rect(g, x + 2 + Theme.width(prompt), y, 5, 9, Theme.accent());
				}
			}
			default -> {
				// [TROLL] client: first word inverted, the rest plain
				String[] parts = text.split(" ", 2);
				float aw = Theme.width(parts[0]) + 6;
				Draw.panel(g, x, y, aw, 12, Theme.accent(0.1f));
				Draw.text(g, parts[0], x + 3, y + 2, Theme.onAccent(), false);
				if (parts.length > 1) {
					Draw.text(g, parts[1], x + aw + 3, y + 2, Theme.text, true);
				}
			}
		}
		if (hud.fps.get()) {
			Draw.text(g, mc.getFps() + " fps", x, y + 16, Theme.textDim, true);
		}
	}

	private static void arrayList(GuiGraphicsExtractor g, HudModule hud, Minecraft mc, int width, float dt) {
		boolean right = hud.side.is("Right");
		List<Module> mods = new ArrayList<>();
		for (Module m : ModuleManager.all()) {
			Anim a = SLIDE.computeIfAbsent(m, k -> new Anim(0, 12));
			boolean show = m.isEnabled() && !m.isHidden() && m.getCategory() != Category.CLIENT;
			a.target(show).update(dt);
			if (a.get() > 0.005f) {
				mods.add(m);
			}
		}
		mods.sort((a, b) -> Float.compare(Theme.width(label(b, hud)), Theme.width(label(a, hud))));

		float push = 0;
		if (right) {
			BitSet slots = ((ToastManagerAccessor) mc.gui.toastManager()).troll$getOccupiedSlots();
			push = TOAST_PUSH.target(slots.length() * Toast.SLOT_HEIGHT).update(dt);
		}
		float leftTop = hud.radar.get() ? radarTop(hud) + hud.radarSize.getFloat() + 5 : (hud.watermark.get() ? 30 : 3);
		float y = right ? 3 + push : leftTop;
		String style = hud.listStyle.get();
		int index = 0;
		for (Module m : mods) {
			float a = SLIDE.get(m).get();
			String name = hud.lowercase.get() ? m.getName().toLowerCase(Locale.ROOT) : m.getName();
			String info = hud.showInfo.get() ? m.displayInfo() : null;
			if (info != null && hud.lowercase.get()) {
				info = info.toLowerCase(Locale.ROOT);
			}
			float w = Theme.width(name) + (info != null ? Theme.width(" " + info) : 0) + 6;
			float h = 11;
			float e = Motion.outCubic(a);
			float offset = (1 - e) * (w + 6);
			float x = right ? width - w - 3 + offset : 3 - offset;
			int accent = Theme.accent(index * 0.08f);
			switch (style) {
				case "Box" -> {
					Draw.rect(g, x - 1, y, w + 2, h * e, Theme.a(ColorUtil.withAlpha(Theme.panel, 200)));
					Draw.outline(g, x - 1, y, w + 2, h * e + 1, ColorUtil.fade(Theme.border, e));
				}
				case "Minimal" -> {
				}
				default -> {
					Draw.rect(g, x, y, w, h * e, Theme.a(ColorUtil.withAlpha(Theme.bg, 150)));
					Draw.rect(g, right ? x + w : x - 1, y, 1, h * e, accent);
				}
			}
			float prev = Draw.alpha;
			Draw.alpha = e;
			Draw.text(g, name, x + 3, y + 2, ColorUtil.lerp(Theme.text, accent, 0.25f), style.equals("Minimal"));
			if (info != null) {
				Draw.text(g, " " + info, x + 3 + Theme.width(name), y + 2, Theme.textDim, style.equals("Minimal"));
			}
			Draw.alpha = prev;
			y += h * e;
			index++;
		}
	}

	/** The radar sits under the watermark (and its FPS line) in the top-left corner. */
	private static float radarTop(HudModule hud) {
		if (!hud.watermark.get()) {
			return 4;
		}
		return hud.fps.get() ? 32 : 21;
	}

	private static String label(Module m, HudModule hud) {
		String info = hud.showInfo.get() ? m.displayInfo() : null;
		return info == null ? m.getName() : m.getName() + " " + info;
	}

	private static void coords(GuiGraphicsExtractor g, Minecraft mc, int height) {
		float y = height - 11 - (mc.gui.screen() instanceof ChatScreen ? 14 : 0);
		String xyz = String.format(Locale.ROOT, "%.1f %.1f %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
		Draw.text(g, "xyz", 4, y, Theme.textDim, true);
		Draw.text(g, xyz, 4 + Theme.width("xyz "), y, Theme.text, true);
	}
}
