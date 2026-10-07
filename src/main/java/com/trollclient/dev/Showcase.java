package com.trollclient.dev;

import com.mojang.authlib.GameProfile;
import com.trollclient.TrollClient;
import com.trollclient.gui.clickgui.ClickGuiScreen;
import com.trollclient.gui.clickgui.GuiState;
import com.trollclient.gui.title.TrollTitleScreen;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.module.client.HudModule;
import com.trollclient.module.client.ThemeModule;
import com.trollclient.util.SkinOverrides;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Records the showcase video ({@code ./gradlew runShowcase}). A fixed script:
 * the title screen, a tour of the menu, then one short scene per feature with
 * custom-skinned stand-in players, shaders and a free camera. Frames land in
 * {@code run-showcase/showcase/frames}; {@code tools/make_video.py} turns them
 * into the final video. Does nothing unless {@code -Dtrollclient.showcase=true}.
 */
public final class Showcase {
	private static final int FPS = 30;

	private interface Phase {
		/** Called every client tick with the ticks spent in this phase; returns true when done. */
		boolean tick(int t);
	}

	private static final List<Phase> PHASES = new ArrayList<>();
	private static final Map<String, RemotePlayer> DUMMIES = new HashMap<>();
	private static int phase;
	private static int t;
	private static Vec3 arena = Vec3.ZERO;
	private static int nextId = -9000;
	private static Method onMove;

	private Showcase() {
	}

	public static boolean enabled() {
		return Boolean.getBoolean("trollclient.showcase");
	}

	public static void init() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TrollClient.MOD_ID, "showcase"), (g, delta) -> Captions.renderHud(g));
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) ->
				ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> Captions.render(g, g.guiWidth(), g.guiHeight())));
		buildScript();
	}

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		acceptConfirmScreens(mc);
		if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
			mc.gui.setScreen(null);
		}
		CameraRig.tick();
		mc.gui.toastManager().clear();
		if (mc.gui.screen() != null) {
			double[] p = Captions.cursorPos();
			moveMouse(p[0], p[1]);
		}
		if (phase >= PHASES.size()) {
			return;
		}
		boolean done;
		try {
			done = PHASES.get(phase).tick(t);
		} catch (RuntimeException e) {
			TrollClient.LOGGER.error("[showcase] phase {} failed", phase, e);
			done = true;
		}
		if (done) {
			phase++;
			t = 0;
		} else {
			t++;
		}
	}

	// ------------------------------------------------------------------ the script

	private static void buildScript() {
		Minecraft mc = Minecraft.getInstance();
		ThemeModule theme = ModuleManager.get(ThemeModule.class);
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);

		once(() -> {
			mc.options.onboardAccessibility = false;
			// recording keeps going while you use other windows
			mc.options.pauseOnLostFocus = false;
			mc.options.guiScale().set(3);
			mc.options.save();
			mc.resizeGui();
			theme.preset.set("Noir");
			theme.font.set("Terminal");
			theme.accentMode.set("Shimmer");
			gui.openAnimation.set("CRT");
			gui.backdrop.set("Rain");
			gui.rememberModule.set(true);
			for (Module m : ModuleManager.all()) {
				if (m.isToggleable() && m.getCategory() != Category.CLIENT) {
					m.setEnabled(false);
				}
			}
			module("SkinChanger").setEnabled(true);
			module("SkinChanger").getSetting("Skin").parse("Troll");
			module("Shaders").setEnabled(false);
			HudModule hud = ModuleManager.get(HudModule.class);
			hud.setEnabled(true);
			hud.radar.set(true);
			hud.targetHud.set(true);
			hud.notifications.set(false);
		});
		waitUntil(() -> mc.gui.overlay() == null && mc.gui.screen() != null, 20, () -> {
			if (!(mc.gui.screen() instanceof TrollTitleScreen)) {
				mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
			}
		});

		// ---------------------------------------------------------------- 1. title screen
		waitUntil(() -> mc.gui.screen() instanceof TrollTitleScreen, 0, () -> {
			FrameRecorder.start(mc.gameDirectory.toPath().resolve("showcase").resolve("frames"), FPS);
			Captions.cursor(true);
			Captions.warp(mc.getWindow().getGuiScaledWidth() * 0.82, mc.getWindow().getGuiScaledHeight() * 0.8);
		});
		timed(11f, k -> {
			TrollTitleScreen title = mc.gui.screen() instanceof TrollTitleScreen s ? s : null;
			if (title == null) {
				return;
			}
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			if (at(k, 2.4f)) {
				Captions.show("v1.0", "TROLL CLIENT", "a black & white client for minecraft 26.2", 5f);
			}
			if (at(k, 3.6f)) {
				glideTo(title.itemCenter(0), 0.5f);
			}
			if (at(k, 4.4f)) {
				glideTo(title.itemCenter(1), 0.35f);
			}
			if (at(k, 5.1f)) {
				glideTo(title.itemCenter(4), 0.45f);
			}
			if (at(k, 6.3f)) {
				glideTo(new double[]{w * 0.18, h * 0.62}, 0.5f);
			}
			if (at(k, 7.0f) || at(k, 8.2f)) {
				click(title, 0);
			}
			if (at(k, 7.5f)) {
				glideTo(new double[]{w * 0.8, h * 0.45}, 0.5f);
			}
			if (at(k, 9.0f)) {
				glideTo(title.itemCenter(4), 0.5f);
			}
		});

		// ---------------------------------------------------------------- world (not recorded)
		once(() -> {
			FrameRecorder.pause();
			Captions.cursor(false);
			mc.gui.setScreen(null);
			CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TrollTitleScreen()));
		});
		waitUntil(() -> mc.gui.screen() instanceof CreateWorldScreen, 20, () -> {
			CreateWorldScreen screen = (CreateWorldScreen) mc.gui.screen();
			WorldCreationUiState state = screen.getUiState();
			state.setAllowCommands(true);
			state.setSeed("troll client");
			state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			state.setDifficulty(Difficulty.PEACEFUL);
			try {
				Method create = CreateWorldScreen.class.getDeclaredMethod("onCreate");
				create.setAccessible(true);
				create.invoke(screen);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
		waitUntil(() -> mc.player != null && mc.level != null && mc.gui.screen() == null, 100, () -> {
			for (String c : new String[]{"gamerule send_command_feedback false", "gamerule show_advancement_messages false",
					"gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false",
					"time set 5000", "weather clear"}) {
				cmd(c);
			}
		});
		waitUntil(() -> true, 30, () -> {
			// a flat stage cut into the real terrain around spawn
			cmd("fill ~-16 ~-5 ~-16 ~16 ~-2 ~16 dirt");
			cmd("fill ~-16 ~-1 ~-16 ~16 ~-1 ~16 grass_block");
			cmd("fill ~-16 ~ ~-16 ~16 ~12 ~16 air");
			// invisible walls so a nearby river can't flood the stage
			cmd("fill ~-17 ~-1 ~-17 ~17 ~3 ~-17 barrier");
			cmd("fill ~-17 ~-1 ~17 ~17 ~3 ~17 barrier");
			cmd("fill ~-17 ~-1 ~-17 ~-17 ~3 ~17 barrier");
			cmd("fill ~17 ~-1 ~-17 ~17 ~3 ~17 barrier");
		});
		waitUntil(() -> true, 10, () -> cmd("fill ~-16 ~-1 ~-16 ~16 ~4 ~16 air replace water"));
		waitUntil(() -> true, 20, () -> {
			arena = new Vec3(Math.floor(mc.player.getX()) + 0.5, Math.floor(mc.player.getY() + 0.01), Math.floor(mc.player.getZ()) + 0.5);
			tp(arena.add(0, 0, -12), 180, 8);
			hotbar("cobblestone 64", "snowball 16", "firework_rocket 64", "diamond_sword", "totem_of_undying",
					"golden_apple 8", "ender_pearl 16", "bow", "cobblestone 64");
			mc.gui.hud.getChat().clearMessages(false);
		});
		int[] landing = {0};
		waitUntil(() -> mc.player.onGround() || mc.player.isInWater() || ++landing[0] > 100, 30,
				() -> TrollClient.LOGGER.info("[showcase] stage ready at {}", arena));

		// ---------------------------------------------------------------- 2. the menu
		scene(22f, () -> {
			shader(null);
			tp(arena.add(0, 0, -14), 180, 4);
			GuiState.category = Category.COMBAT;
			GuiState.selectedModule = "CrystalCancel";
			GuiState.centerX = 0.5f;
			GuiState.centerY = 0.5f;
		}, k -> {
			ClickGuiScreen menu = mc.gui.screen() instanceof ClickGuiScreen s ? s : null;
			if (at(k, 0.6f)) {
				pressKey(GLFW.GLFW_KEY_RIGHT_SHIFT);
			}
			if (at(k, 1.2f)) {
				Captions.warp(mc.getWindow().getGuiScaledWidth() * 0.7, mc.getWindow().getGuiScaledHeight() * 0.75);
				Captions.cursor(true);
			}
			if (at(k, 1.4f)) {
				Captions.show("01 / interface", "THE MENU", "right shift. left click toggles, right click opens settings", 7f);
			}
			if (menu == null) {
				return;
			}
			if (at(k, 1.8f)) {
				glideTo(menu.tabCenter(Category.MOVEMENT.ordinal()), 0.5f);
			}
			if (at(k, 2.7f)) {
				glideTo(menu.tabCenter(Category.TROLL.ordinal()), 0.3f);
			}
			if (at(k, 3.2f)) {
				click(menu, 0);
			}
			if (at(k, 3.9f)) {
				glideTo(menu.moduleRowCenter(0), 0.5f);
			}
			if (at(k, 5.0f)) {
				click(menu, 0);
			}
			if (at(k, 5.8f)) {
				click(menu, 1);
			}
			if (at(k, 6.8f)) {
				glideTo(menu.settingsRow("Speed", 0.3f), 0.5f);
			}
			if (at(k, 7.5f)) {
				press(menu, 0);
			}
			if (at(k, 7.6f)) {
				glideTo(menu.settingsRow("Speed", 0.85f), 0.9f);
			}
			if (at(k, 8.6f)) {
				release(menu, 0);
			}
			if (at(k, 9.1f)) {
				glideTo(menu.settingsRow("Mode", 0.95f), 0.4f);
			}
			if (at(k, 9.7f) || at(k, 10.2f)) {
				click(menu, 0);
			}
			if (at(k, 10.8f)) {
				glideTo(menu.paletteCenter(), 0.5f);
				Captions.show("02 / themes", "THEMES", "noir, paper, ink, graphite, fog, terminal, newsprint or your own", 5f);
			}
			String[] presets = {"Paper", "Ink", "Graphite", "Fog", "Noir"};
			for (int i = 0; i < presets.length; i++) {
				if (at(k, 11.5f + i * 0.9f)) {
					click(menu, 0);
					theme.preset.set(presets[i]);
				}
			}
			if (at(k, 16.0f)) {
				Captions.show("03 / search", "TYPE TO SEARCH", "matches light up. arrows, space and enter work too", 5f);
				glideTo(menu.searchCenter(), 0.5f);
			}
			String query = "orbit";
			for (int i = 0; i < query.length(); i++) {
				if (at(k, 16.8f + i * 0.12f)) {
					menu.charTyped(new CharacterEvent(query.charAt(i)));
				}
			}
			if (at(k, 17.9f)) {
				menu.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
			}
			if (at(k, 19.0f)) {
				menu.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
			}
			if (at(k, 19.5f) || at(k, 19.8f)) {
				menu.keyPressed(new KeyEvent(GLFW.GLFW_KEY_DOWN, 0, 0));
			}
			if (at(k, 20.4f)) {
				// Esc only unfocuses the search box at this point, so close the menu directly
				menu.onClose();
				Captions.cursor(false);
			}
		});
		once(() -> module("Twerk").setEnabled(false));

		// ---------------------------------------------------------------- 3. shaders
		scene(11f, () -> {
			shader("Noir");
			CameraRig.cut(arena.add(-10, 7, -14), arena.add(30, 4, 30));
			CameraRig.dolly(arena.add(-4, 6, -15), arena.add(40, 2, 20), 11f);
		}, k -> {
			if (at(k, 0.3f)) {
				Captions.show("04 / visuals", "SHADERS", "noir / crt / 1-bit dither / halftone / pen & ink", 10f);
			}
			String[] looks = {"Noir", "CRT", "Dither", "Halftone", "Ink"};
			for (int i = 1; i < looks.length; i++) {
				if (at(k, i * 2.2f)) {
					shader(looks[i]);
				}
			}
		});

		// ---------------------------------------------------------------- 4. twerk + itemflex
		scene(8f, () -> {
			shader("Noir");
			tp(arena, 0, 0);
			CameraRig.cut(arena.add(0.4, 1.7, 3.6), arena.add(0, 1.1, 0));
			Module twerk = module("Twerk");
			twerk.getSetting("Mode").parse("Twerk");
			twerk.getSetting("Flail Arms").parse("true");
			module("ItemFlex").getSetting("Interval").parse("3");
		}, k -> {
			if (at(k, 0.2f)) {
				module("Twerk").setEnabled(true);
				module("ItemFlex").setEnabled(true);
			}
			if (at(k, 0.4f)) {
				Captions.show("05 / troll", "TWERK + ITEMFLEX", "server-side body language. everybody can see it.", 7f);
			}
			if (at(k, 4.2f)) {
				module("Twerk").getSetting("Mode").parse("Spin");
				CameraRig.dolly(arena.add(-2.6, 2.4, 2.6), arena.add(0, 1.0, 0), 3.5f);
			}
		});
		once(() -> {
			module("Twerk").setEnabled(false);
			module("ItemFlex").setEnabled(false);
		});

		// ---------------------------------------------------------------- 5. orbit
		scene(8f, () -> {
			shader("CRT");
			tp(arena.add(-3, 0, 0), 90, 0);
			dummy("victim_steve", "mime", arena, 0);
			CameraRig.cut(arena.add(7, 4.5, 6), arena.add(0, 1, 0));
			CameraRig.dolly(arena.add(-6, 4, 7.5), arena.add(0, 1, 0), 8f);
			module("Orbit").getSetting("Radius").parse("3");
		}, k -> {
			if (at(k, 0.1f)) {
				module("Orbit").setEnabled(true);
			}
			if (at(k, 0.4f)) {
				Captions.show("06 / movement", "ORBIT", "circles a player like a tiny, annoying moon", 7f);
			}
		});
		once(() -> {
			module("Orbit").setEnabled(false);
			removeDummies();
		});

		// ---------------------------------------------------------------- 6. stalker (angel mode)
		scene(10f, () -> {
			shader("Noir");
			tp(arena.add(0, 0, 4), 180, 0);
			dummy("lag_lord", "hacker", arena.add(0, 0, -1), 180);
			CameraRig.cut(arena.add(7, 2.6, -1), arena.add(0, 1, -3));
			module("Stalker").getSetting("Mode").parse("Angel");
			module("Stalker").getSetting("Distance").parse("1.5");
		}, k -> {
			if (at(k, 0.1f)) {
				module("Stalker").setEnabled(true);
			}
			if (at(k, 0.4f)) {
				Captions.show("07 / movement", "STALKER", "angel mode: only moves while they aren't looking", 9f);
			}
			RemotePlayer d = DUMMIES.get("lag_lord");
			if (d == null) {
				return;
			}
			// walk away, glance back, walk on... the stalker freezes every time they look
			float s = k / 20f;
			boolean looking = (s > 2.6f && s < 4.2f) || (s > 6.4f && s < 8.0f);
			float yaw = looking ? 0 : 180;
			Vec3 pos = d.position();
			if (!looking) {
				pos = pos.add(0, 0, -0.07);
			}
			walk(d, pos, yaw);
			if (k % 10 == 0) {
				// truck alongside the pair, looking between them
				CameraRig.dolly(new Vec3(arena.x + 7, arena.y + 2.6, d.getZ() + 1.0), d.position().add(0, 1, 1.4), 0.6f);
			}
		});
		once(() -> {
			module("Stalker").setEnabled(false);
			removeDummies();
		});

		// ---------------------------------------------------------------- 7. trapper
		scene(7f, () -> {
			shader("Ink");
			clearStage();
			tp(arena.add(0, 0, -1), 0, 20);
			dummy("xX_PvP_Xx", "referee", arena.add(0, 0, 2), 180);
			CameraRig.cut(arena.add(4.5, 3.2, 6.5), arena.add(0, 1, 2));
			module("Trapper").getSetting("Only When Still").parse("true");
			module("Trapper").getSetting("Still For").parse("14");
			module("Trapper").getSetting("Air Place").parse("true");
			module("Trapper").getSetting("Blocks Per Tick").parse("1");
		}, k -> {
			if (at(k, 0.3f)) {
				module("Trapper").setEnabled(true);
			}
			if (at(k, 0.5f)) {
				Captions.show("08 / combat", "TRAPPER", "stand still for half a second. we dare you.", 6f);
			}
			if (at(k, 4.0f)) {
				CameraRig.dolly(arena.add(-3.5, 4.5, 6), arena.add(0, 1, 2), 3f);
			}
		});
		once(() -> {
			module("Trapper").setEnabled(false);
			removeDummies();
			clearStage();
		});

		// ---------------------------------------------------------------- 8. pelter + confetti
		scene(8f, () -> {
			shader("Dither");
			tp(arena.add(0, 0, -4), 0, 0);
			dummy("afk_andy", "ghost", arena.add(0, 0, 3), 180);
			CameraRig.cut(arena.add(1.6, 2.3, -6.8), arena.add(0, 1.2, 3));
			module("Pelter").getSetting("Delay").parse("250");
			module("Confetti").getSetting("Delay").parse("700");
		}, k -> {
			if (at(k, 0.2f)) {
				module("Pelter").setEnabled(true);
				module("Confetti").setEnabled(true);
			}
			if (at(k, 0.4f)) {
				Captions.show("09 / troll", "PELTER + CONFETTI", "snowballs with real ballistics. fireworks for no reason.", 7f);
			}
		});
		once(() -> {
			module("Pelter").setEnabled(false);
			module("Confetti").setEnabled(false);
			removeDummies();
		});

		// ---------------------------------------------------------------- 9. nowayhome
		scene(9f, () -> {
			shader("Halftone");
			clearStage();
			tp(arena.add(0, 0, -2), 0, 10);
			dummy("victim_steve", "mime", arena.add(-6, 0, 2), -90);
			CameraRig.cut(arena.add(0.5, 9, 9), arena.add(0, 0, 1));
			module("NoWayHome").getSetting("Range").parse("8");
		}, k -> {
			if (at(k, 0.2f)) {
				module("NoWayHome").setEnabled(true);
			}
			if (at(k, 0.4f)) {
				Captions.show("10 / troll", "NOWAYHOME", "a wall in front of them. every. single. time.", 8f);
			}
			RemotePlayer d = DUMMIES.get("victim_steve");
			if (d != null) {
				wanderInto(d, k);
			}
		});
		once(() -> {
			module("NoWayHome").setEnabled(false);
			removeDummies();
			clearStage();
		});

		// ---------------------------------------------------------------- 10. mimic
		scene(8f, () -> {
			shader("Noir");
			tp(arena.add(1.8, 0, 0), 90, 0);
			dummy("lag_lord", "hacker", arena.add(-1.8, 0, 0), -90);
			CameraRig.cut(arena.add(0, 1.9, 5.5), arena.add(0, 1.1, 0));
			module("Mimic").getSetting("Look").parse("Mirror");
			module("Mimic").getSetting("Walk").parse("Mirror");
		}, k -> {
			if (at(k, 0.1f)) {
				module("Mimic").setEnabled(true);
			}
			if (at(k, 0.4f)) {
				Captions.show("11 / troll", "MIMIC", "copies everything you do. mirror mode is extra creepy.", 7f);
			}
			RemotePlayer d = DUMMIES.get("lag_lord");
			if (d != null) {
				perform(d, k);
			}
		});
		once(() -> {
			module("Mimic").setEnabled(false);
			removeDummies();
		});

		// ---------------------------------------------------------------- 11. racket
		scene(6.5f, () -> {
			shader("CRT");
			clearStage();
			props(arena.add(0, 0, 3));
			tp(arena.add(0, 0, 0.5), 0, 15);
			dummy("xX_PvP_Xx", "referee", arena.add(0.5, 0, 5), 180);
			CameraRig.cut(arena.add(4.5, 2.4, 0), arena.add(0, 0.8, 3.5));
			module("Racket").getSetting("Speed").parse("12");
			module("Racket").getSetting("Buttons").parse("true");
		}, k -> {
			if (at(k, 0.2f)) {
				module("Racket").setEnabled(true);
			}
			if (at(k, 0.4f)) {
				Captions.show("12 / troll", "RACKET", "every door, gate, lever and bell in reach. at once.", 5.5f);
			}
		});
		once(() -> {
			module("Racket").setEnabled(false);
			removeDummies();
			clearStage();
		});

		// ---------------------------------------------------------------- 12. chat
		scene(9f, () -> {
			shader(null);
			CameraRig.release();
			tp(arena.add(0, 0, -14), 180, -5);
			mc.gui.hud.getChat().clearMessages(false);
			module("ChatStyle").getSetting("Style").parse("Small Caps");
			module("ChatStyle").setEnabled(true);
			module("Spammer").getSetting("Delay").parse("3");
			module("Spammer").getSetting("Messages").parse("troll client on top | have you tried turning it off and on again");
			module("Announcer").getSetting("Audience").parse("Client");
			module("Announcer").getSetting("Delay").parse("5");
			module("Announcer").getSetting("Minimum").parse("1");
		}, k -> {
			if (at(k, 0.3f)) {
				mc.gui.setScreen(new ChatScreen("", false));
				Captions.show("13 / chat", "CHAT", "chatstyle / spammer / announcer / parrot", 8f);
			}
			String text = "gg everyone";
			for (int i = 0; i < text.length(); i++) {
				if (at(k, 0.9f + i * 0.09f) && mc.gui.screen() instanceof ChatScreen chat) {
					chatInput(chat).setValue(text.substring(0, i + 1));
				}
			}
			if (at(k, 2.3f) && mc.gui.screen() instanceof ChatScreen chat) {
				chat.handleChatInput(text, true);
				mc.gui.setScreen(null);
				module("Spammer").setEnabled(true);
				module("Announcer").setEnabled(true);
			}
		});
		once(() -> {
			for (String name : new String[]{"ChatStyle", "Spammer", "Announcer"}) {
				module(name).setEnabled(false);
			}
		});

		// ---------------------------------------------------------------- 13. outro
		scene(6.5f, () -> {
			shader("Noir");
			CameraRig.cut(arena.add(-12, 9, -16), arena.add(20, 3, 20));
			CameraRig.dolly(arena.add(-8, 8, -17), arena.add(26, 2, 18), 6.5f);
		}, k -> {
			if (at(k, 1.0f)) {
				Captions.outro();
			}
		});
		once(() -> {
			FrameRecorder.stop();
			TrollClient.LOGGER.info("[showcase] done: {} frames", FrameRecorder.frames());
			mc.stop();
		});
	}

	// ------------------------------------------------------------------ phase builders

	private static void once(Runnable action) {
		PHASES.add(t -> {
			action.run();
			return true;
		});
	}

	/** Waits for a condition, then {@code delay} more ticks, then runs the action. */
	private static void waitUntil(BooleanSupplier ready, int delay, Runnable action) {
		int[] seen = {-1};
		PHASES.add(t -> {
			if (seen[0] < 0) {
				if (!ready.getAsBoolean()) {
					return false;
				}
				seen[0] = t;
			}
			if (t - seen[0] < delay) {
				return false;
			}
			action.run();
			return true;
		});
	}

	private static void timed(float seconds, IntConsumer body) {
		int ticks = Math.round(seconds * 20);
		PHASES.add(t -> {
			body.accept(t);
			return t >= ticks;
		});
	}

	/**
	 * A recorded scene: recording pauses while {@code prep} sets the stage (teleports,
	 * camera, shaders settle for half a second), then resumes for the scene itself.
	 */
	private static void scene(float seconds, Runnable prep, IntConsumer body) {
		int prepTicks = 12;
		int ticks = Math.round(seconds * 20);
		PHASES.add(t -> {
			if (t == 0) {
				FrameRecorder.pause();
				Minecraft mc = Minecraft.getInstance();
				if (mc.gui.screen() != null) {
					mc.gui.setScreen(null);
				}
				prep.run();
			}
			if (t < prepTicks) {
				return false;
			}
			if (t == prepTicks) {
				// commands like /kill complain in red when there's nothing to kill
				Minecraft.getInstance().gui.hud.getChat().clearMessages(false);
				FrameRecorder.resume();
			}
			body.accept(t - prepTicks);
			return t - prepTicks >= ticks;
		});
	}

	private static boolean at(int tick, float seconds) {
		return tick == Math.round(seconds * 20);
	}

	// ------------------------------------------------------------------ helpers

	private static Module module(String name) {
		return ModuleManager.byName(name);
	}

	private static void cmd(String command) {
		Minecraft.getInstance().player.connection.sendCommand(command);
	}

	private static void tp(Vec3 pos, float yaw, float pitch) {
		cmd(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", pos.x, pos.y, pos.z, yaw, pitch));
	}

	private static void hotbar(String... items) {
		for (int i = 0; i < items.length; i++) {
			cmd("item replace entity @s hotbar." + i + " with " + items[i]);
		}
	}

	private static void shader(String name) {
		Module shaders = module("Shaders");
		if (name == null) {
			shaders.setEnabled(false);
		} else {
			shaders.getSetting("Shader").parse(name);
			shaders.setEnabled(true);
		}
	}

	private static void clearStage() {
		BlockPos c = BlockPos.containing(arena);
		cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", c.getX() - 15, c.getY(), c.getZ() - 15,
				c.getX() + 15, c.getY() + 8, c.getZ() + 15));
		cmd("kill @e[type=item]");
		cmd("kill @e[type=snowball]");
	}

	/** A row of noisy blocks for Racket. */
	private static void props(Vec3 at) {
		BlockPos b = BlockPos.containing(at);
		int y = b.getY();
		String[] blocks = {"oak_door[half=lower,facing=north]", "spruce_fence_gate[facing=north]", "oak_trapdoor[half=bottom,facing=north]",
				"bell[attachment=floor,facing=north]", "birch_door[half=lower,facing=north]", "dark_oak_fence_gate[facing=north]"};
		int x = b.getX() - 2;
		for (String block : blocks) {
			cmd(String.format(Locale.ROOT, "setblock %d %d %d %s", x, y, b.getZ(), block));
			if (block.contains("_door")) {
				cmd(String.format(Locale.ROOT, "setblock %d %d %d %s", x, y + 1, b.getZ(), block.replace("lower", "upper")));
			}
			x++;
		}
		cmd(String.format(Locale.ROOT, "setblock %d %d %d stone", b.getX() + 4, y, b.getZ()));
		cmd(String.format(Locale.ROOT, "setblock %d %d %d lever[face=floor,facing=north]", b.getX() + 4, y + 1, b.getZ()));
		cmd(String.format(Locale.ROOT, "setblock %d %d %d stone_button[face=floor,facing=north]", b.getX() - 3, y, b.getZ()));
	}

	private static RemotePlayer dummy(String name, String skin, Vec3 pos, float yaw) {
		Minecraft mc = Minecraft.getInstance();
		UUID id = UUID.nameUUIDFromBytes(("troll-dummy-" + name).getBytes());
		RemotePlayer d = new RemotePlayer(mc.level, new GameProfile(id, name));
		d.setId(nextId--);
		d.snapTo(pos.x, pos.y, pos.z, yaw, 0);
		d.setYHeadRot(yaw);
		d.setYBodyRot(yaw);
		SkinOverrides.set(id, skin, false);
		mc.level.addEntity(d);
		DUMMIES.put(name, d);
		return d;
	}

	private static void removeDummies() {
		Minecraft mc = Minecraft.getInstance();
		for (RemotePlayer d : DUMMIES.values()) {
			mc.level.removeEntity(d.getId(), Entity.RemovalReason.DISCARDED);
			SkinOverrides.clear(d.getUUID());
		}
		DUMMIES.clear();
	}

	/** Moves a stand-in player the way the server would: interpolated, so legs swing and turns are smooth. */
	private static void walk(RemotePlayer d, Vec3 pos, float yaw) {
		float smooth = d.getYRot() + Mth.wrapDegrees(yaw - d.getYRot()) * 0.35f;
		d.moveOrInterpolateTo(pos, smooth, 0);
		d.lerpHeadTo(smooth, 3);
	}

	/** NoWayHome scene: walk until something solid is in the way, pause, turn, carry on. */
	private static void wanderInto(RemotePlayer d, int k) {
		Minecraft mc = Minecraft.getInstance();
		float yaw = d.getYRot();
		Vec3 dir = Vec3.directionFromRotation(0, yaw);
		Vec3 next = d.position().add(dir.scale(0.6));
		BlockPos feet = BlockPos.containing(next.x, d.getY() + 0.1, next.z);
		boolean blocked = !mc.level.getBlockState(feet).isAir() || !mc.level.getBlockState(feet.above()).isAir();
		float target = Math.round(yaw / 90f) * 90f;
		if (blocked) {
			// bonk: stand there for a moment, then turn left or right
			if (k % 14 == 13) {
				target += (k / 14) % 2 == 0 ? 90 : -90;
				d.snapTo(d.getX(), d.getY(), d.getZ(), target, 0);
				d.lerpHeadTo(target, 3);
			}
			walk(d, d.position(), target);
			return;
		}
		walk(d, d.position().add(dir.scale(0.11)), target);
	}

	/** Mimic scene: a little routine for the stand-in to perform. */
	private static void perform(RemotePlayer d, int k) {
		float s = k / 20f;
		boolean crouch = (s > 0.8f && s < 1.6f) || (s > 4.8f && s < 5.2f);
		d.setShiftKeyDown(crouch);
		d.setPose(crouch ? Pose.CROUCHING : Pose.STANDING);
		Vec3 base = arena.add(-1.8, 0, 0);
		double hop = s > 2.0f && s < 2.5f ? Math.sin((s - 2.0f) / 0.5f * Math.PI) * 1.1 : 0;
		double step = s > 5.8f && s < 7.2f ? Math.sin((s - 5.8f) / 1.4f * Math.PI) * 1.2 : 0;
		float yaw = -90 + (s > 3.4f && s < 4.6f ? (float) Math.sin((s - 3.4f) * 6) * 45 : 0);
		walk(d, base.add(step, hop, 0), yaw);
		if ((s > 2.8f && s < 3.4f) && k % 4 == 0) {
			d.swing(InteractionHand.MAIN_HAND);
		}
	}

	// ------------------------------------------------------------------ input

	private static void glideTo(double[] point, float seconds) {
		if (point != null) {
			Captions.glide(point[0], point[1], seconds);
		}
	}

	private static void click(Screen screen, int button) {
		press(screen, button);
		release(screen, button);
	}

	private static void press(Screen screen, int button) {
		double[] p = Captions.cursorPos();
		Captions.clicked();
		screen.mouseClicked(new MouseButtonEvent(p[0], p[1], new MouseButtonInfo(button, 0)), false);
	}

	private static void release(Screen screen, int button) {
		double[] p = Captions.cursorPos();
		screen.mouseReleased(new MouseButtonEvent(p[0], p[1], new MouseButtonInfo(button, 0)));
	}

	private static void pressKey(int key) {
		Minecraft mc = Minecraft.getInstance();
		try {
			Method press = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, KeyEvent.class);
			press.setAccessible(true);
			press.invoke(mc.keyboardHandler, mc.getWindow().handle(), GLFW.GLFW_PRESS, new KeyEvent(key, 0, 0));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Points the game's mouse at a GUI coordinate, so hover effects follow the drawn cursor. */
	private static void moveMouse(double guiX, double guiY) {
		Minecraft mc = Minecraft.getInstance();
		double k = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
		try {
			if (onMove == null) {
				onMove = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
				onMove.setAccessible(true);
			}
			onMove.invoke(mc.mouseHandler, mc.getWindow().handle(), guiX * k, guiY * k);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static EditBox chatInput(ChatScreen chat) {
		try {
			java.lang.reflect.Field f = ChatScreen.class.getDeclaredField("input");
			f.setAccessible(true);
			return (EditBox) f.get(chat);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** World creation sometimes asks about experimental settings; always say yes. */
	private static void acceptConfirmScreens(Minecraft mc) {
		if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.ConfirmScreen confirm) {
			try {
				java.lang.reflect.Field f = net.minecraft.client.gui.screens.ConfirmScreen.class.getDeclaredField("callback");
				f.setAccessible(true);
				((it.unimi.dsi.fastutil.booleans.BooleanConsumer) f.get(confirm)).accept(true);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	public static Path framesDir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("showcase").resolve("frames");
	}
}
