package com.trollclient.dev;

import com.trollclient.TrollClient;
import com.trollclient.gui.clickgui.ClickGuiScreen;
import com.trollclient.gui.clickgui.GuiState;
import com.trollclient.gui.title.TrollTitleScreen;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.module.client.ThemeModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Development-only visual smoke test, enabled with {@code -Dtrollclient.devshots=true}
 * (see the {@code runDevShots} Gradle task). Walks through the menus, creates a
 * throwaway test world, pokes a few modules and saves screenshots to
 * {@code run/screenshots/}. Does nothing in normal play.
 */
public final class DevShots {
	private record Step(String name, BooleanSupplier ready, int waitTicks, Runnable action) {
	}

	private static final List<Step> STEPS = new ArrayList<>();

	/** Stand-ins for menus that other mods put in place of the vanilla one. */
	private static final class ModdedTitleScreen extends net.minecraft.client.gui.screens.TitleScreen {
	}

	private static final class FancyMainMenuScreen extends net.minecraft.client.gui.screens.Screen {
		FancyMainMenuScreen() {
			super(net.minecraft.network.chat.Component.literal("fancy"));
		}
	}

	/** Client-side fake player for exercising the targeting modules in singleplayer. */
	private static net.minecraft.client.player.RemotePlayer dummy;
	private static int index;
	private static int waited;
	private static boolean readySeen;
	private static net.minecraft.world.phys.Vec3 start = net.minecraft.world.phys.Vec3.ZERO;
	/** Counts how often a watched value changes between ticks (sneak state, hotbar slot...). */
	private static java.util.function.IntSupplier watch;
	private static int watchLast;
	private static int watchChanges;

	private DevShots() {
	}

	public static boolean enabled() {
		return Boolean.getBoolean("trollclient.devshots");
	}

	public static void init() {
		Minecraft mc = Minecraft.getInstance();
		ThemeModule theme = ModuleManager.get(ThemeModule.class);
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);

		step("skip onboarding", () -> mc.gui.overlay() == null && mc.gui.screen() != null, 20, () -> {
			if (!(mc.gui.screen() instanceof TrollTitleScreen)) {
				// a fresh run directory starts on the accessibility onboarding screen
				mc.options.onboardAccessibility = false;
				mc.options.save();
				mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
			}
		});
		step("title", () -> mc.gui.screen() instanceof TrollTitleScreen, 110, () -> shot("01_title"));
		// the title screen under every other preset: its background, logo and chrome should follow the theme
		for (String preset : new String[]{"Graphite", "Terminal", "Paper", "Newsprint", "Ink", "Fog", "Frutiger Aero", "Vaporwave",
				"Amber", "Phosphor", "Classic", "Sakura", "Ocean", "Dracula", "Nord", "Solarized", "Crimson", "Aurora", "Handheld"}) {
			String file = preset.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
			step("title " + file, () -> true, 1, () -> theme.preset.set(preset));
			step("", () -> true, 30, () -> shot("01t_title_" + file));
		}
		step("title glow", () -> true, 1, () -> {
			theme.preset.set("Noir");
			theme.glow.set(true);
			theme.accentMode.set("Rainbow");
			// hover the first menu item so the selection bar (with its glow and shadow) shows
			double[] item = ((TrollTitleScreen) mc.gui.screen()).itemCenter(0);
			hoverAt(item);
		});
		step("", () -> true, 30, () -> {
			shot("01t_title_glow");
			theme.glow.set(false);
			theme.accentMode.set("Shimmer");
			hoverAt(new double[]{1, 1});
		});
		step("vanilla button", () -> true, 1, () -> {
			// a real click on "[ vanilla menu ]": cursor there, then press + release through MouseHandler
			hoverAt(new double[]{20, 11});
		});
		step("", () -> true, 3, () -> clickAt(0));
		step("", () -> true, 20, () -> {
			TrollClient.LOGGER.info("[devshots] vanilla button: after a real click, screen = {}", screenName());
			shot("01a_vanilla_clicked");
			// a client mod that puts its own menu in place of the vanilla one we just opened
			mc.gui.setScreen(new FancyMainMenuScreen());
		});
		step("", () -> true, 10, () -> {
			boolean back = net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(mc.gui.screen()).stream()
					.anyMatch(w -> w.getMessage().getString().equals("Troll Client"));
			TrollClient.LOGGER.info("[devshots] vanilla button: after another mod swaps its menu in, screen = {}, back button = {}",
					screenName(), back);
			// the cursor is still over the corner, right where the back button sits
			clickAt(0);
		});
		step("", () -> true, 5, () -> TrollClient.LOGGER.info("[devshots] vanilla button: after the back button, screen = {}", screenName()));
		step("title guard", () -> true, 1, () -> {
			TrollClient.LOGGER.info("[devshots] guard: subclass={}, modded name={}, world list={}",
					com.trollclient.gui.title.TitleScreenGuard.shouldReplace(new ModdedTitleScreen()),
					com.trollclient.gui.title.TitleScreenGuard.shouldReplace(new FancyMainMenuScreen()),
					com.trollclient.gui.title.TitleScreenGuard.shouldReplace(
							new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(null)));
			mc.gui.setScreen(new ModdedTitleScreen());
		});
		step("", () -> true, 5, () -> {
			TrollClient.LOGGER.info("[devshots] guard: after opening a modded subclass, screen = {}", screenName());
			// a mod that skips Gui#setScreen and writes the field itself
			net.minecraft.client.gui.screens.TitleScreen sneaky = new net.minecraft.client.gui.screens.TitleScreen();
			try {
				java.lang.reflect.Field f = net.minecraft.client.gui.Gui.class.getDeclaredField("screen");
				f.setAccessible(true);
				f.set(mc.gui, sneaky);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
			sneaky.init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
		});
		step("", () -> true, 5, () -> {
			TrollClient.LOGGER.info("[devshots] guard: after a direct field write, screen = {}", screenName());
			mc.gui.setScreen(new FancyMainMenuScreen());
		});
		step("", () -> true, 5, () -> {
			TrollClient.LOGGER.info("[devshots] guard: after a custom 'MainMenu' screen, screen = {}", screenName());
			com.trollclient.gui.title.TitleScreenGuard.requestVanilla();
			mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
		});
		step("", () -> true, 40, () -> {
			boolean button = net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(mc.gui.screen()).stream()
					.anyMatch(w -> w.getMessage().getString().equals("Troll Client"));
			TrollClient.LOGGER.info("[devshots] guard: vanilla menu on request, screen = {}, back button = {}", screenName(), button);
			shot("01b_vanilla_with_button");
			// another mod re-opening the vanilla menu while the user still wants it: it stays vanilla
			mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
		});
		step("", () -> true, 5, () -> {
			TrollClient.LOGGER.info("[devshots] guard: vanilla menu re-opened while wanted, screen = {}", screenName());
			hoverAt(new double[]{20, 11});
			clickAt(0);
		});
		step("", () -> true, 5, () -> {
			TrollClient.LOGGER.info("[devshots] guard: after the back button, screen = {}", screenName());
			mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
		});
		step("", () -> true, 5, () -> TrollClient.LOGGER.info("[devshots] guard: fresh vanilla menu afterwards, screen = {}", screenName()));
		step("gui combat", () -> true, 1, () -> openGui(Category.COMBAT, "CrystalCancel"));
		step("", () -> true, 45, () -> shot("02_gui_combat"));
		step("gui troll", () -> true, 1, () -> openGui(Category.TROLL, "Twerk"));
		step("", () -> true, 45, () -> shot("03_gui_troll"));
		step("keyboard cursor", () -> true, 1, () -> {
			ClickGuiScreen s = (ClickGuiScreen) mc.gui.screen();
			for (int i = 0; i < 3; i++) {
				s.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0));
			}
		});
		step("", () -> true, 20, () -> shot("03b_keyboard_cursor"));
		step("middle click bind", () -> true, 1, () -> {
			ClickGuiScreen s = (ClickGuiScreen) mc.gui.screen();
			double[] p = s.moduleRowCenter(5);
			s.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(p[0], p[1],
					new net.minecraft.client.input.MouseButtonInfo(2, 0)), false);
		});
		step("", () -> true, 20, () -> {
			shot("03c_bind_prompt");
			((ClickGuiScreen) mc.gui.screen()).keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
		});
		step("search", () -> true, 5, () -> {
			ClickGuiScreen s = (ClickGuiScreen) mc.gui.screen();
			s.charTyped(new net.minecraft.client.input.CharacterEvent('a'));
			s.charTyped(new net.minecraft.client.input.CharacterEvent('n'));
		});
		step("", () -> true, 30, () -> shot("03d_search"));
		step("chat category", () -> true, 1, () -> {
			ModuleManager.get(ClickGuiModule.class).openAnimation.set("Assemble");
			openGui(Category.CHAT, "ChatStyle");
		});
		step("", () -> true, 7, () -> shot("03h_assemble_opening"));
		step("", () -> true, 40, () -> {
			shot("03i_chat_category");
			com.trollclient.module.chat.ChatStyle style = ModuleManager.get(com.trollclient.module.chat.ChatStyle.class);
			for (String mode : new String[]{"Small Caps", "Fullwidth", "Bubble", "Leet"}) {
				style.getSetting("Style").parse(mode);
				TrollClient.LOGGER.info("[devshots] chat style {}: {}", mode, style.apply("troll client on top"));
			}
			style.getSetting("Style").parse("Small Caps");
			ModuleManager.get(ClickGuiModule.class).openAnimation.set("CRT");
		});
		step("morse settings", () -> true, 1, () -> openGui(Category.TROLL, "Morse"));
		step("", () -> true, 45, () -> shot("03e_morse_settings"));
		step("flyout", () -> true, 1, () -> hoverAt(((ClickGuiScreen) mc.gui.screen()).tabCenter(Category.MOVEMENT.ordinal())));
		step("", () -> true, 20, () -> shot("03f_flyout"));
		step("tooltip", () -> true, 1, () -> hoverAt(((ClickGuiScreen) mc.gui.screen()).moduleRowCenter(6)));
		step("", () -> true, 50, () -> shot("03g_tooltip"));
		step("paper", () -> true, 1, () -> {
			theme.preset.set("Paper");
			openGui(Category.MOVEMENT, "PlayerFollow");
		});
		step("", () -> true, 45, () -> shot("04_gui_paper"));
		step("newsprint", () -> true, 1, () -> {
			theme.preset.set("Newsprint");
			theme.font.set("Minecraft");
			gui.openAnimation.set("Zoom");
			openGui(Category.CLIENT, "Theme");
		});
		step("", () -> true, 45, () -> shot("05_gui_newsprint_theme"));
		step("graphite", () -> true, 1, () -> {
			theme.preset.set("Graphite");
			theme.font.set("Terminal");
			theme.accentMode.set("Rainbow");
			gui.backdrop.set("Grid");
			openGui(Category.CLIENT, "ClickGUI");
		});
		step("", () -> true, 45, () -> shot("06_gui_graphite_rainbow"));
		step("frutiger aero", () -> true, 1, () -> {
			theme.preset.set("Frutiger Aero");
			theme.accentMode.set("Shimmer");
			theme.corners.set("Round");
			gui.backdrop.set("Theme");
			openGui(Category.TROLL, "Twerk");
		});
		step("", () -> true, 45, () -> shot("06b_gui_frutiger_aero"));
		step("vaporwave", () -> true, 1, () -> {
			theme.preset.set("Vaporwave");
			theme.corners.set("Notched");
			openGui(Category.CHAT, "Hypeman");
		});
		step("", () -> true, 45, () -> shot("06c_gui_vaporwave"));
		step("crt mid-animation", () -> true, 1, () -> {
			theme.preset.set("Noir");
			theme.accentMode.set("Shimmer");
			gui.backdrop.set("Rain");
			gui.openAnimation.set("CRT");
			openGui(Category.COMBAT, "ArrowDodge");
		});
		step("", () -> true, 6, () -> shot("07_gui_crt_opening"));
		step("create world", () -> true, 30, () -> {
			mc.gui.setScreen(null);
			CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TrollTitleScreen()));
		});
		step("confirm world", () -> mc.gui.screen() instanceof CreateWorldScreen, 20, () -> {
			CreateWorldScreen screen = (CreateWorldScreen) mc.gui.screen();
			screen.getUiState().setAllowCommands(true);
			try {
				// 26.x is unobfuscated, so the private method keeps its name at runtime
				java.lang.reflect.Method create = CreateWorldScreen.class.getDeclaredMethod("onCreate");
				create.setAccessible(true);
				create.invoke(screen);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
		step("in world", () -> mc.player != null && mc.level != null && mc.gui.screen() == null, 160, () -> {
			cmd("time set day");
			cmd("weather clear");
			cmd("gamerule send_command_feedback false");
			cmd("gamemode survival");
			cmd("effect give @s resistance 600 4 true");
			cmd("give @s cobblestone 64");
			cmd("give @s dirt 64");
			cmd("give @s golden_apple 4");
			module("Twerk").setEnabled(true);
			module("FoodAnnoy").setEnabled(true);
			ModuleManager.get(com.trollclient.module.player.ItemPickup.class).getSetting("Only Player Drops").parse("false");
			module("ItemPickup").setEnabled(true);
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
		});
		step("", () -> true, 60, () -> {
			TrollClient.LOGGER.info("[devshots] food annoy nibbles: {}", module("FoodAnnoy").getInfo());
			shot("08_twerk_f5");
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			mc.gui.hud.getChat().clearMessages(false);
		});
		step("drop item", () -> true, 10, () -> {
			// sneaking and eating both slow walking, so test pickup on its own
			module("Twerk").setEnabled(false);
			module("FoodAnnoy").setEnabled(false);
			start = mc.player.position();
			TrollClient.LOGGER.info("[devshots] player before pickup at {}", start);
			cmd("summon item ~7 ~1 ~5 {Item:{id:\"minecraft:diamond\",count:1}}");
		});
		step("pickup", () -> true, 120, () -> {
			boolean diamondLeft = false;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof ItemEntity item && item.getItem().getItem() == net.minecraft.world.item.Items.DIAMOND) {
					diamondLeft = true;
				}
			}
			int diamonds = mc.player.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND);
			TrollClient.LOGGER.info("[devshots] after pickup: moved {} blocks, diamond on ground={}, diamonds in inventory={}",
					String.format("%.2f", mc.player.position().distanceTo(start)), diamondLeft, diamonds);
			shot("09_hud_after_pickup");
			module("ItemPickup").setEnabled(false);
		});
		step("arrow", () -> true, 20, () -> {
			module("ArrowDodge").setEnabled(true);
			start = mc.player.position();
			cmd(String.format(java.util.Locale.ROOT, "summon arrow %.2f %.2f %.2f {Motion:[-2.5d,0.02d,0.0d]}",
					start.x + 14, start.y + 1.2, start.z));
		});
		step("", () -> true, 15, () -> {
			net.minecraft.world.phys.Vec3 now = mc.player.position();
			TrollClient.LOGGER.info("[devshots] arrow dodge: sideways (z) moved {} blocks, along shot (x) {}",
					String.format("%.2f", now.z - start.z), String.format("%.2f", now.x - start.x));
			module("ArrowDodge").setEnabled(false);
		});
		step("crystal", () -> true, 20, () -> {
			Module cc = module("CrystalCancel");
			cc.getSetting("Require Enemy").parse("false");
			cc.setEnabled(true);
			mc.player.setXRot(25);
			start = mc.player.position();
			cmd(String.format(java.util.Locale.ROOT, "summon end_crystal %.2f %.2f %.2f",
					start.x + 4.5, start.y, start.z));
		});
		step("", () -> true, 30, () -> {
			TrollClient.LOGGER.info("[devshots] crystal cancel info: {}", module("CrystalCancel").getInfo());
			mc.player.setYRot(-90);
			shot("10_crystal_cancel");
		});
		step("pop counter", () -> true, 10, () -> {
			Module pop = module("PopCounter");
			pop.getSetting("Count Self").parse("true");
			pop.setEnabled(true);
			cmd("effect clear @s resistance");
			cmd("item replace entity @s weapon.offhand with totem_of_undying");
		});
		step("", () -> true, 10, () -> cmd("damage @s 40 minecraft:generic"));
		step("", () -> true, 25, () -> {
			TrollClient.LOGGER.info("[devshots] pop counter total: {}", module("PopCounter").getInfo());
			shot("12_pop_counter");
			cmd("effect give @s resistance 600 4 true");
			module("PopCounter").setEnabled(false);
		});
		step("racket", () -> true, 10, () -> {
			net.minecraft.core.BlockPos door = mc.player.blockPosition().east(2);
			cmd("fill " + door.getX() + " " + door.getY() + " " + door.getZ() + " " + door.getX() + " " + (door.getY() + 1) + " "
					+ door.getZ() + " air");
			cmd("setblock " + door.getX() + " " + (door.getY() - 1) + " " + door.getZ() + " stone");
			cmd("setblock " + door.getX() + " " + door.getY() + " " + door.getZ() + " oak_door[half=lower]");
			cmd("setblock " + door.getX() + " " + (door.getY() + 1) + " " + door.getZ() + " oak_door[half=upper]");
			start = net.minecraft.world.phys.Vec3.atLowerCornerOf(door);
		});
		step("", () -> true, 10, () -> {
			net.minecraft.core.BlockPos door = net.minecraft.core.BlockPos.containing(start);
			startWatch(() -> mc.level.getBlockState(door).hasProperty(net.minecraft.world.level.block.DoorBlock.OPEN)
					&& mc.level.getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN) ? 1 : 0);
			Module racket = module("Racket");
			racket.getSetting("Near Players").parse("false");
			racket.setEnabled(true);
		});
		step("", () -> true, 40, () -> {
			TrollClient.LOGGER.info("[devshots] racket: candidates={}, door flips in 2s={}", module("Racket").getInfo(), endWatch());
			module("Racket").setEnabled(false);
		});
		step("morse", () -> true, 5, () -> {
			startWatch(() -> mc.player.isShiftKeyDown() ? 1 : 0);
			Module morse = module("Morse");
			morse.getSetting("Unit").parse("1");
			morse.getSetting("Message").parse("sos");
			morse.setEnabled(true);
		});
		step("", () -> true, 40, () -> {
			TrollClient.LOGGER.info("[devshots] morse: sneak changes in 2s={} (sos = 9 symbols -> 18 changes per pass), letter={}",
					endWatch(), module("Morse").getInfo());
			module("Morse").setEnabled(false);
		});
		step("item flex", () -> true, 5, () -> {
			startWatch(() -> mc.player.getInventory().getSelectedSlot());
			module("ItemFlex").setEnabled(true);
		});
		step("", () -> true, 40, () -> {
			TrollClient.LOGGER.info("[devshots] item flex: slot changes in 2s={}", endWatch());
			com.trollclient.module.chat.Parrot parrot = ModuleManager.get(com.trollclient.module.chat.Parrot.class);
			TrollClient.LOGGER.info("[devshots] parrot mock: {}", parrot.transform("why are you following me"));
			module("Twerk").setEnabled(true);
			module("Morse").setEnabled(true);
			module("Racket").setEnabled(true);
		});
		step("", () -> true, 30, () -> shot("13_hud_new_modules"));
		step("", () -> true, 1, () -> {
			for (String name : new String[]{"ItemFlex", "Twerk", "Morse", "Racket"}) {
				module(name).setEnabled(false);
			}
		});
		step("arena", () -> true, 5, () -> cmd("tp @s ~40 ~12 ~"));
		step("", () -> true, 20, () -> {
			// a clean flat platform, away from the door and crystal the earlier tests left behind
			cmd("fill ~-9 ~-1 ~-9 ~9 ~-1 ~9 stone");
			cmd("fill ~-9 ~ ~-9 ~9 ~5 ~9 air");
			cmd("give @s snowball 16");
			cmd("give @s firework_rocket 8");
		});
		step("dummy", () -> mc.player.onGround(), 20, () -> {
			com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Dummy");
			dummy = new net.minecraft.client.player.RemotePlayer(mc.level, profile);
			dummy.setId(-4242);
			net.minecraft.world.phys.Vec3 at = mc.player.position().add(4, 0, 0);
			dummy.snapTo(at.x, at.y, at.z, 90f, 0f);
			dummy.setYHeadRot(90f);
			mc.level.addEntity(dummy);
			start = mc.player.position();
			module("Orbit").setEnabled(true);
		});
		step("", () -> true, 60, () -> {
			TrollClient.LOGGER.info("[devshots] orbit: distance to dummy {} (radius 2.5), moved {} blocks",
					String.format("%.2f", mc.player.distanceTo(dummy)), String.format("%.2f", mc.player.position().distanceTo(start)));
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		});
		// screenshots capture the last rendered frame, so camera changes need a tick to show up
		step("", () -> true, 4, () -> {
			shot("14_orbit_target_hud");
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			module("Orbit").setEnabled(false);
			// stalker: dummy faces +x (yaw -90), so "behind" it is the -x side
			dummy.snapTo(dummy.getX(), dummy.getY(), dummy.getZ(), -90f, 0f);
			dummy.setYHeadRot(-90f);
			module("Stalker").setEnabled(true);
		});
		step("", () -> true, 60, () -> {
			TrollClient.LOGGER.info("[devshots] stalker: me-dummy x offset {}, z offset {} (want about -2.5, 0), info={}",
					String.format("%.2f", mc.player.getX() - dummy.getX()), String.format("%.2f", mc.player.getZ() - dummy.getZ()),
					module("Stalker").getInfo());
			module("Stalker").setEnabled(false);
			module("Pelter").setEnabled(true);
			module("Confetti").setEnabled(true);
		});
		step("", () -> true, 40, () -> {
			TrollClient.LOGGER.info("[devshots] pelter thrown={}, confetti launched={}", module("Pelter").getInfo(), module("Confetti").getInfo());
			shot("14b_pelter_confetti");
			module("Pelter").setEnabled(false);
			module("Confetti").setEnabled(false);
			Module trapper = module("Trapper");
			trapper.getSetting("Only When Still").parse("false");
			trapper.getSetting("Air Place").parse("true");
			trapper.setEnabled(true);
		});
		step("", () -> true, 40, () -> {
			net.minecraft.core.BlockPos feet = dummy.blockPosition();
			int solid = 0;
			for (net.minecraft.core.BlockPos p : new net.minecraft.core.BlockPos[]{feet.north(), feet.south(), feet.east(), feet.west(),
					feet.above().north(), feet.above().south(), feet.above().east(), feet.above().west(), feet.above(2)}) {
				if (!mc.level.getBlockState(p).isAir()) {
					solid++;
				}
			}
			TrollClient.LOGGER.info("[devshots] trapper: {}/9 cage blocks around the dummy, info={}", solid, module("Trapper").getInfo());
			float[] look = com.trollclient.util.Rotations.lookAt(dummy.position().add(0, 1, 0));
			mc.player.setYRot(look[0]);
			mc.player.setXRot(look[1]);
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		});
		step("", () -> true, 4, () -> {
			shot("15_trapper");
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			module("Trapper").setEnabled(false);
			// knock the cage down and stand the dummy somewhere fresh, facing +z
			cmd("fill ~-9 ~ ~-9 ~9 ~5 ~9 air");
		});
		step("grudge", () -> true, 5, () -> {
			net.minecraft.world.phys.Vec3 at = mc.player.position().add(4, 0, 0);
			dummy.snapTo(at.x, at.y, at.z, 0f, 0f);
			dummy.setYHeadRot(0f);
			Module grudge = module("Grudge");
			grudge.getSetting("Payback").parse("Goalie");
			grudge.setEnabled(true);
			// the packet a server sends when the dummy punches us, through the real handler (and our mixin)
			mc.getConnection().handleDamageEvent(new net.minecraft.network.protocol.game.ClientboundDamageEventPacket(mc.player,
					mc.level.damageSources().playerAttack(dummy)));
		});
		step("", () -> true, 2, () -> TrollClient.LOGGER.info("[devshots] grudge: holding={}, orbit target={}, goalie on={}",
				ModuleManager.get(com.trollclient.module.combat.Grudge.class).holding(),
				module("Orbit").getSetting("Target").display(), module("Goalie").isEnabled()));
		step("", () -> true, 60, () -> {
			// goalie (switched on as payback) should be standing ~1.4 blocks in front of the dummy, along +z
			TrollClient.LOGGER.info("[devshots] goalie: me-dummy x offset {}, z offset {} (want about 0, 1.4), info={}",
					String.format("%.2f", mc.player.getX() - dummy.getX()), String.format("%.2f", mc.player.getZ() - dummy.getZ()),
					module("Goalie").getInfo());
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		});
		step("", () -> true, 4, () -> {
			shot("16_goalie");
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			// the dummy dies: the grudge is settled
			ModuleManager.entityEvent(dummy, (byte) 3);
			TrollClient.LOGGER.info("[devshots] grudge settled: holding={}, orbit target='{}', goalie on={}",
					ModuleManager.get(com.trollclient.module.combat.Grudge.class).holding(),
					module("Orbit").getSetting("Target").display(), module("Goalie").isEnabled());
			module("Grudge").setEnabled(false);
		});
		step("graffiti", () -> true, 5, () -> {
			cmd("give @s oak_sign 4");
			Module graffiti = module("Graffiti");
			graffiti.getSetting("Messages").parse("{player}/was here");
			graffiti.getSetting("Stop After").parse("1");
			graffiti.setEnabled(true);
		});
		step("", () -> true, 60, () -> {
			net.minecraft.core.BlockPos signPos = null;
			String text = "";
			for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(mc.player.blockPosition().offset(-6, -3, -6),
					mc.player.blockPosition().offset(6, 3, 6))) {
				if (mc.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
					signPos = p.immutable();
					StringBuilder sb = new StringBuilder();
					for (int i = 0; i < 4; i++) {
						sb.append('[').append(sign.getFrontText().getMessage(i, false).getString()).append(']');
					}
					text = sb.toString();
				}
			}
			TrollClient.LOGGER.info("[devshots] graffiti: info={}, enabled={}, sign at {} reads {}, editor screen={}",
					module("Graffiti").getInfo(), module("Graffiti").isEnabled(), signPos, text, screenName());
			if (signPos != null) {
				float[] look = com.trollclient.util.Rotations.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(signPos));
				mc.player.setYRot(look[0]);
				mc.player.setXRot(look[1]);
			}
		});
		step("", () -> true, 4, () -> shot("17_graffiti"));
		step("honk", () -> true, 5, () -> {
			cmd("clear @s oak_sign");
			cmd("give @s goat_horn[instrument=\"minecraft:ponder_goat_horn\"]");
			Module honk = module("Honk");
			honk.setEnabled(true);
		});
		step("", () -> true, 30, () -> {
			int slot = com.trollclient.util.InventoryUtil.findHotbar(net.minecraft.world.item.Items.GOAT_HORN);
			TrollClient.LOGGER.info("[devshots] honk: info={}, horn on cooldown={}", module("Honk").getInfo(),
					slot >= 0 && mc.player.getCooldowns().isOnCooldown(mc.player.getInventory().getItem(slot)));
			module("Honk").setEnabled(false);
			cmd("item replace entity @s weapon.mainhand with diamond_sword");
			cmd("item replace entity @s weapon.offhand with shield");
		});
		step("juggle", () -> true, 5, () -> {
			startWatch(() -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(mc.player.getOffhandItem().getItem()));
			module("Juggle").setEnabled(true);
		});
		step("", () -> true, 40, () -> {
			TrollClient.LOGGER.info("[devshots] juggle: offhand changes in 2s={}", endWatch());
			module("Juggle").setEnabled(false);
		});
		// the server answers the last swap a moment later
		step("", () -> true, 5, () -> TrollClient.LOGGER.info("[devshots] juggle: after stopping, main={}, off={}",
				mc.player.getMainHandItem().getItem(), mc.player.getOffhandItem().getItem()));
		step("greeter", () -> true, 1, () -> {
			Module greeter = module("Greeter");
			greeter.getSetting("Audience").parse("Client");
			greeter.setEnabled(true);
		});
		step("", () -> true, 30, () -> {
			TrollClient.LOGGER.info("[devshots] greeter: greetings={}", module("Greeter").getInfo());
			module("Greeter").setEnabled(false);
			Module reply = module("AutoReply");
			reply.getSetting("Delay").parse("0");
			reply.getSetting("Cooldown").parse("1");
			reply.setEnabled(true);
			ModuleManager.chatMessage("Dummy", "hey " + mc.player.getGameProfile().name() + " are you there?");
		});
		step("", () -> true, 30, () -> {
			TrollClient.LOGGER.info("[devshots] auto reply: replies={}", module("AutoReply").getInfo());
			module("AutoReply").setEnabled(false);
			com.trollclient.module.chat.Typo typo = ModuleManager.get(com.trollclient.module.chat.Typo.class);
			typo.getSetting("Chance").parse("100");
			typo.getSetting("Correction Delay").parse("500");
			typo.setEnabled(true);
			for (String kind : new String[]{"Swap", "Fat Finger", "Double", "Drop"}) {
				typo.getSetting("Kind").parse(kind);
				TrollClient.LOGGER.info("[devshots] typo {}: {}", kind, typo.mistype("definitely"));
			}
			typo.getSetting("Kind").parse("Mixed");
			// the real path: chat goes through Fabric's MODIFY_CHAT, Typo then ChatStyle
			mc.getConnection().sendChat("this message definitely contains mistakes");
		});
		step("", () -> true, 30, () -> {
			// the integrated server logs both lines it received (the typo'd one and the "*fix") as chat
			TrollClient.LOGGER.info("[devshots] typo: typos={}", module("Typo").getInfo());
			module("Typo").setEnabled(false);
			for (String name : new String[]{"Grudge", "Greeter", "Honk", "Juggle", "Typo"}) {
				module(name).setEnabled(true);
			}
		});
		step("", () -> true, 20, () -> {
			shot("18_hud_more_modules");
			for (String name : new String[]{"Grudge", "Greeter", "Honk", "Juggle", "Typo"}) {
				module(name).setEnabled(false);
			}
		});
		newModules(mc);
		step("", () -> true, 5, () -> mc.level.removeEntity(dummy.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
		step("gui tools", () -> true, 5, () -> {
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			net.minecraft.core.BlockPos chest = mc.player.blockPosition().east(2);
			cmd("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
					+ " chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}");
			start = net.minecraft.world.phys.Vec3.atCenterOf(chest);
		});
		step("", () -> true, 10, () -> {
			// a macro opens the chest: face it, give the crosshair a tick to catch up, use it, wait for the GUI
			float[] look = com.trollclient.util.Rotations.lookAt(start);
			com.trollclient.macro.Macro m = com.trollclient.macro.MacroManager.create();
			m.name.set("devshots chest");
			com.trollclient.macro.MacroStep rotate = newStep("rotate");
			set(rotate, "Yaw", Float.toString(look[0]));
			set(rotate, "Pitch", Float.toString(look[1]));
			m.steps.add(rotate);
			com.trollclient.macro.MacroStep wait = newStep("delay");
			set(wait, "Unit", "Ticks");
			set(wait, "Ticks", "2");
			m.steps.add(wait);
			m.steps.add(newStep("use_block"));
			com.trollclient.macro.MacroStep waitGui = newStep("wait_gui");
			set(waitGui, "Timeout", "3");
			m.steps.add(waitGui);
			com.trollclient.macro.MacroStep note = newStep("notify");
			set(note, "Text", "chest open");
			m.steps.add(note);
			com.trollclient.macro.MacroManager.start(m);
		});
		step("", () -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen, 20, () -> {
			TrollClient.LOGGER.info("[devshots] gui tools: macro opened {}, macro still running={}", screenName(),
					com.trollclient.macro.MacroManager.isRunning(com.trollclient.macro.MacroManager.byName("devshots chest")));
			// a real click on the panel's delay button, through Fabric's screen events
			hoverAt(com.trollclient.gui.tools.GuiToolsPanel.buttonCenter("delay"));
			clickAt(0);
		});
		step("", () -> true, 3, () -> {
			TrollClient.LOGGER.info("[devshots] gui tools: after clicking delay, delaying={}, screen={}",
					com.trollclient.packet.PacketGate.isDelaying(), screenName());
			start = new net.minecraft.world.phys.Vec3(serverDiamonds(), 0, 0);
			// shift-click the diamonds out of the chest: the click packet should wait in the queue
			mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, 0, 0,
					net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, mc.player);
			hoverAt(new double[]{mc.getWindow().getGuiScaledWidth() / 2.0, mc.getWindow().getGuiScaledHeight() - 30});
		});
		step("", () -> true, 10, () -> {
			TrollClient.LOGGER.info("[devshots] gui tools: queued={} ({}), server diamonds still {} (was {})",
					com.trollclient.packet.PacketGate.queued(),
					com.trollclient.packet.PacketGate.queue().isEmpty() ? "-" : com.trollclient.packet.PacketGate.queue().get(0).detail(),
					serverDiamonds(), (int) start.x);
			shot("19_gui_tools");
		});
		step("", () -> true, 2, () -> TrollClient.LOGGER.info("[devshots] gui tools: flushed {}",
				com.trollclient.packet.PacketGate.flush()));
		step("", () -> true, 10, () -> {
			TrollClient.LOGGER.info("[devshots] gui tools: after flush, server diamonds {} (was {})", serverDiamonds(), (int) start.x);
			com.trollclient.packet.PacketGate.setDelaying(false);
			// sending off: the click is thrown away
			com.trollclient.packet.PacketGate.setSending(false);
			mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, 1, 0,
					net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
			TrollClient.LOGGER.info("[devshots] gui tools: send off, dropped={}, queued={}",
					com.trollclient.packet.PacketGate.dropped(), com.trollclient.packet.PacketGate.queued());
			com.trollclient.packet.PacketGate.setSending(true);
			com.trollclient.packet.GuiTools.save();
			com.trollclient.packet.GuiTools.closeWithoutPacket();
			TrollClient.LOGGER.info("[devshots] gui tools: closed without packet, screen={}, client menu is inventory={}, server menu still open={}",
					screenName(), mc.player.containerMenu == mc.player.inventoryMenu, serverMenuOpen());
		});
		step("", () -> true, 5, () -> {
			com.trollclient.packet.GuiTools.restore();
			TrollClient.LOGGER.info("[devshots] gui tools: restored, screen={}", screenName());
			com.trollclient.packet.GuiTools.desync();
		});
		step("", () -> true, 5, () -> {
			TrollClient.LOGGER.info("[devshots] gui tools: after de-sync, client screen={}, server menu still open={}", screenName(), serverMenuOpen());
			mc.player.clientSideCloseContainer();
			com.trollclient.gui.macro.MacroEditorScreen.open(com.trollclient.macro.MacroManager.byName("devshots chest"));
		});
		step("macro editor", () -> true, 20, () -> {
			com.trollclient.gui.macro.MacroEditorScreen editor = (com.trollclient.gui.macro.MacroEditorScreen) mc.gui.screen();
			hoverAt(editor.stepRowCenter(3));
			clickAt(0);
		});
		step("", () -> true, 25, () -> shot("20_macro_editor"));
		step("", () -> true, 1, () -> ((com.trollclient.gui.macro.MacroEditorScreen) mc.gui.screen()).showPalette("packets"));
		step("", () -> true, 20, () -> {
			shot("20b_macro_palette");
			mc.gui.setScreen(null);
			com.trollclient.macro.MacroManager.remove(com.trollclient.macro.MacroManager.byName("devshots chest"));
		});
		step("bunny", () -> true, 5, () -> {
			startWatch(() -> mc.player.onGround() ? 1 : 0);
			com.trollclient.macro.MacroManager.start(com.trollclient.macro.MacroManager.byName("bunny"));
		});
		step("", () -> true, 80, () -> {
			TrollClient.LOGGER.info("[devshots] bunny macro: ground changes={} (5 hops = 10), still running={}", endWatch(),
					com.trollclient.macro.MacroManager.isRunning(com.trollclient.macro.MacroManager.byName("bunny")));
			com.trollclient.macro.MacroManager.byName("bunny").setKey(org.lwjgl.glfw.GLFW.GLFW_KEY_K);
			pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_K);
		});
		step("", () -> true, 3, () -> {
			TrollClient.LOGGER.info("[devshots] macro key: bunny running after K={}",
					com.trollclient.macro.MacroManager.isRunning(com.trollclient.macro.MacroManager.byName("bunny")));
			com.trollclient.macro.MacroManager.byName("bunny").setKey(-1);
			// with delay on, even chat waits in the queue
			com.trollclient.packet.PacketGate.setDelaying(true);
			com.trollclient.util.ChatUtil.send("this waits in the queue");
		});
		step("", () -> true, 5, () -> {
			shot("21_hud_badges");
			TrollClient.LOGGER.info("[devshots] hud badges: queued={}", com.trollclient.packet.PacketGate.queued());
			com.trollclient.packet.PacketGate.clear();
			com.trollclient.packet.PacketGate.setDelaying(false);
			com.trollclient.macro.MacroManager.stopAll();
			com.trollclient.command.Commands.handle(".macro list");
			com.trollclient.command.Commands.handle(".macro run bunny");
			TrollClient.LOGGER.info("[devshots] .macro run bunny: running={}",
					com.trollclient.macro.MacroManager.isRunning(com.trollclient.macro.MacroManager.byName("bunny")));
			com.trollclient.command.Commands.handle(".macro stop");
		});
		step("skin blink", () -> true, 5, () -> {
			startWatch(() -> mc.options.isModelPartEnabled(net.minecraft.world.entity.player.PlayerModelPart.HAT) ? 1 : 0);
			module("SkinBlink").getSetting("Pattern").parse("Blink");
			module("SkinBlink").setEnabled(true);
		});
		step("", () -> true, 30, () -> {
			int flips = endWatch();
			module("SkinBlink").setEnabled(false);
			TrollClient.LOGGER.info("[devshots] skin blink: hat flips in 1.5s={}, hat restored={}", flips,
					mc.options.isModelPartEnabled(net.minecraft.world.entity.player.PlayerModelPart.HAT));
		});
		step("rshift", () -> true, 10, () -> {
			try {
				// same path a real key press takes: KeyboardHandler.keyPress -> our mixin -> vanilla screen dispatch
				java.lang.reflect.Method press = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress",
						long.class, int.class, net.minecraft.client.input.KeyEvent.class);
				press.setAccessible(true);
				press.invoke(mc.keyboardHandler, mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_PRESS,
						new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT, 0, 0));
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
		step("", () -> true, 20, () -> TrollClient.LOGGER.info("[devshots] after right shift, screen = {}",
				mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName()));
		step("gui in game", () -> true, 1, () -> openGui(Category.TROLL, "NoWayHome"));
		step("", () -> true, 45, () -> shot("11_gui_ingame"));
		step("done", () -> true, 10, () -> {
			TrollClient.LOGGER.info("[devshots] finished, stopping client");
			mc.stop();
		});
	}

	/** The second wave of modules, mostly against the dummy (which only exists on our side, so the server never sees it). */
	private static void newModules(Minecraft mc) {
		// a fresh platform up in the air: by now the first one is often half flooded, depending on the seed
		step("new modules", () -> true, 10, () -> cmd("tp @s ~ ~30 ~"));
		step("", () -> true, 5, () -> {
			cmd("fill ~-9 ~-1 ~-9 ~9 ~-1 ~9 stone");
			cmd("fill ~-9 ~ ~-9 ~9 ~5 ~9 air");
		});
		step("stare + narrator", () -> mc.player.onGround(), 5, () -> {
			net.minecraft.world.phys.Vec3 at = mc.player.position().add(4, 0, 0);
			dummy.snapTo(at.x, at.y, at.z, 90f, 0f);
			dummy.setYHeadRot(90f);
			module("Stare").getSetting("Mode").parse("Nod");
			module("Stare").setEnabled(true);
			Module narrator = module("Narrator");
			narrator.getSetting("Audience").parse("Client");
			narrator.getSetting("Chance").parse("100");
			narrator.getSetting("Cooldown").parse("2");
			narrator.setEnabled(true);
			module("Keystrokes").setEnabled(true);
		});
		step("", () -> true, 10, () -> dummy.setPose(net.minecraft.world.entity.Pose.CROUCHING));
		step("", () -> true, 50, () -> {
			TrollClient.LOGGER.info("[devshots] stare: info={}, server pitch={}; narrator lines={}", module("Stare").getInfo(),
					String.format("%.1f", com.trollclient.util.Rotations.serverPitch()), module("Narrator").getInfo());
			dummy.setPose(net.minecraft.world.entity.Pose.STANDING);
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
		});
		step("", () -> true, 4, () -> {
			shot("22_stare_keystrokes");
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			module("Stare").setEnabled(false);
			module("Narrator").setEnabled(false);
			com.trollclient.util.Friends.add("Dummy");
			module("Bodyguard").setEnabled(true);
		});
		step("", () -> true, 60, () -> {
			TrollClient.LOGGER.info("[devshots] bodyguard: distance to dummy {} (want about 2-3), info={}",
					String.format("%.2f", mc.player.distanceTo(dummy)), module("Bodyguard").getInfo());
			module("Bodyguard").setEnabled(false);
			com.trollclient.util.Friends.remove("Dummy");
			module("NPC").setEnabled(true);
		});
		step("", () -> true, 40, () -> {
			TrollClient.LOGGER.info("[devshots] npc: info={} (dummy within stare range)", module("NPC").getInfo());
			module("NPC").setEnabled(false);
			cmd("give @s fishing_rod");
			module("Angler").setEnabled(true);
		});
		step("", () -> true, 12, () -> TrollClient.LOGGER.info("[devshots] angler: line out={}", mc.player.fishing != null));
		step("", () -> true, 30, () -> {
			// the server can't hook a dummy it doesn't know about: the cast should time out and reel back in
			TrollClient.LOGGER.info("[devshots] angler: line out after giving up={}, casts={}", mc.player.fishing != null,
					field(module("Angler"), "casts"));
			module("Angler").setEnabled(false);
		});
		step("", () -> true, 10, () -> {
			TrollClient.LOGGER.info("[devshots] angler: after switching off, line out={}, holding={}", mc.player.fishing != null,
					mc.player.getMainHandItem().getItem());
			cmd("fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 grass_block");
			cmd("give @s bone_meal 16");
			module("Gardener").setEnabled(true);
		});
		step("", () -> true, 50, () -> {
			int plants = 0;
			for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(dummy.blockPosition().offset(-5, 0, -5),
					dummy.blockPosition().offset(5, 0, 5))) {
				if (!mc.level.getBlockState(p).isAir()) {
					plants++;
				}
			}
			TrollClient.LOGGER.info("[devshots] gardener: bone meals={}, plants around the dummy={}", module("Gardener").getInfo(), plants);
			module("Gardener").setEnabled(false);
			Module streak = module("KillStreak");
			streak.getSetting("Mobs Count").parse("true");
			streak.setEnabled(true);
			cmd("item replace entity @s weapon.mainhand with diamond_sword");
			cmd("summon chicken ~1.6 ~ ~1 {NoAI:1b}");
			cmd("summon chicken ~1.6 ~ ~-1 {NoAI:1b}");
		});
		step("", () -> true, 15, () -> hitChicken(mc));
		step("", () -> true, 16, () -> hitChicken(mc));
		step("", () -> true, 6, () -> {
			TrollClient.LOGGER.info("[devshots] kill streak: streak={}", module("KillStreak").getInfo());
			shot("23_killstreak");
		});
		step("", () -> true, 50, () -> {
			module("KillStreak").setEnabled(false);
			Module fidget = module("Fidget");
			fidget.getSetting("Idle After").parse("3");
			fidget.getSetting("Every").parse("1");
			fidget.setEnabled(true);
		});
		step("", () -> true, 120, () -> {
			TrollClient.LOGGER.info("[devshots] fidget: info={}", module("Fidget").getInfo());
			module("Fidget").setEnabled(false);
			Module hype = module("Hypeman");
			hype.getSetting("Chance").parse("100");
			hype.getSetting("Delay").parse("0");
			hype.setEnabled(true);
			ModuleManager.chatMessage("Dummy", "check out my new house");
		});
		step("", () -> true, 10, () -> {
			TrollClient.LOGGER.info("[devshots] hypeman: reactions={}", module("Hypeman").getInfo());
			module("Hypeman").setEnabled(false);
			// the chat parser against layouts real servers use; our own name is the only one in the tab list
			String me = mc.player.getGameProfile().name();
			for (String line : new String[]{"<" + me + "> plain vanilla", "<[VIP] " + me + "> ranked vanilla",
					"[Owner] " + me + " » arrow style", "Party > [MVP+] " + me + ": party chat", "[G] " + me + " | guild chat",
					"✦ " + me + " ➥ fancy symbols", me + " joined the game", me + " was slain by Zombie",
					"~ " + me + " ~ says: custom layout"}) {
				String[] parsed = com.trollclient.util.ChatUtil.parse(line);
				TrollClient.LOGGER.info("[devshots] chat parse: \"{}\" -> {}", line, parsed == null ? "not chat" : parsed[0] + " / " + parsed[1]);
			}
			module("ChatFormat").getSetting("Formats").parse("~ {player} ~ says: {message}");
			String[] custom = com.trollclient.util.ChatUtil.parse("~ " + me + " ~ says: custom layout");
			TrollClient.LOGGER.info("[devshots] chat parse with a custom format: {}", custom == null ? "not chat" : custom[0] + " / " + custom[1]);
			module("ChatFormat").getSetting("Formats").parse("");
			Module quiz = module("Quizmaster");
			quiz.getSetting("Topic").parse("Custom");
			quiz.getSetting("Custom Questions").parse("what's the best block? = dirt | who made this quiz? = troll client");
			quiz.getSetting("Min Players").parse("0");
			quiz.getSetting("Message Gap").parse("500");
			quiz.setEnabled(true);
		});
		step("", () -> true, 90, () -> {
			// one of these is right, whichever question came up; the second is misspelled on purpose
			ModuleManager.chatMessage("Dummy", "is it dirt");
			ModuleManager.chatMessage("Dummy", "troll clinet");
		});
		step("", () -> true, 20, () -> {
			TrollClient.LOGGER.info("[devshots] quizmaster: info={}, question still open={}", module("Quizmaster").getInfo(),
					field(module("Quizmaster"), "current") != null);
			ModuleManager.chatMessage("Dummy", "!top");
		});
		step("", () -> true, 20, () -> {
			module("Quizmaster").setEnabled(false);
			Module countdown = module("Countdown");
			countdown.getSetting("From").parse("3");
			countdown.getSetting("Interval").parse("1");
			countdown.setEnabled(true);
		});
		step("", () -> true, 120, () -> {
			TrollClient.LOGGER.info("[devshots] countdown: still on after finishing={}", module("Countdown").isEnabled());
			module("Keystrokes").setEnabled(false);
			// mow the lawn: tall grass and flowers would catch the crosshair in the chest test
			cmd("fill ~-9 ~ ~-9 ~9 ~5 ~9 air");
		});
	}

	private static void hitChicken(Minecraft mc) {
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof net.minecraft.world.entity.animal.chicken.Chicken chicken && chicken.isAlive() && chicken.distanceTo(mc.player) < 3.5) {
				mc.gameMode.attack(mc.player, chicken);
				mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
				return;
			}
		}
		TrollClient.LOGGER.info("[devshots] kill streak: no chicken in reach");
	}

	private static Object field(Object owner, String name) {
		try {
			java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.get(owner);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static com.trollclient.macro.MacroStep newStep(String id) {
		return com.trollclient.macro.Steps.byId(id).create();
	}

	private static void set(com.trollclient.macro.MacroStep step, String setting, String value) {
		for (com.trollclient.setting.Setting<?> s : step.settings()) {
			if (s.getName().equalsIgnoreCase(setting)) {
				s.parse(value);
				return;
			}
		}
		throw new IllegalArgumentException(step.type().id() + " has no setting " + setting);
	}

	/** Diamonds the integrated server thinks we have: what actually reached it. */
	private static int serverDiamonds() {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.server.level.ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
		return sp == null ? -1 : sp.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND);
	}

	/** Whether the integrated server still has a container open for us. */
	private static boolean serverMenuOpen() {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.server.level.ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
		return sp != null && sp.containerMenu != sp.inventoryMenu;
	}

	/** A key press the way GLFW delivers it: KeyboardHandler.keyPress, then our mixin and the screen. */
	private static void pressKey(int key) {
		Minecraft mc = Minecraft.getInstance();
		try {
			java.lang.reflect.Method press = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress",
					long.class, int.class, net.minecraft.client.input.KeyEvent.class);
			press.setAccessible(true);
			press.invoke(mc.keyboardHandler, mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_PRESS,
					new net.minecraft.client.input.KeyEvent(key, 0, 0));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String screenName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName();
	}

	private static void startWatch(java.util.function.IntSupplier supplier) {
		watch = supplier;
		watchLast = supplier.getAsInt();
		watchChanges = 0;
	}

	private static int endWatch() {
		int n = watchChanges;
		watch = null;
		watchChanges = 0;
		return n;
	}

	private static void step(String name, BooleanSupplier ready, int waitTicks, Runnable action) {
		STEPS.add(new Step(name, ready, waitTicks, action));
	}

	public static void tick() {
		if (index >= STEPS.size()) {
			return;
		}
		acceptConfirmScreens();
		// the test drives the game itself; clicking another window mustn't freeze it behind the pause menu
		Minecraft mc = Minecraft.getInstance();
		mc.options.pauseOnLostFocus = false;
		if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
			mc.gui.setScreen(null);
		}
		if (watch != null) {
			int v = watch.getAsInt();
			if (v != watchLast) {
				watchChanges++;
				watchLast = v;
			}
		}
		Step step = STEPS.get(index);
		if (!readySeen) {
			if (!step.ready().getAsBoolean()) {
				return;
			}
			readySeen = true;
			waited = 0;
		}
		if (++waited < step.waitTicks()) {
			return;
		}
		try {
			if (!step.name().isEmpty()) {
				TrollClient.LOGGER.info("[devshots] step: {}", step.name());
			}
			step.action().run();
		} catch (RuntimeException e) {
			TrollClient.LOGGER.error("[devshots] step {} failed", step.name(), e);
		}
		index++;
		readySeen = false;
	}

	/** Clicks "yes" on any confirmation dialog (experimental settings warnings and the like). */
	private static void acceptConfirmScreens() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.ConfirmScreen confirm) {
			try {
				java.lang.reflect.Field f = net.minecraft.client.gui.screens.ConfirmScreen.class.getDeclaredField("callback");
				f.setAccessible(true);
				TrollClient.LOGGER.info("[devshots] accepting confirm screen: {}", confirm.getTitle().getString());
				((it.unimi.dsi.fastutil.booleans.BooleanConsumer) f.get(confirm)).accept(true);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/** Moves the real cursor to a GUI-space point (window coordinates are unscaled). */
	private static void hoverAt(double[] gui) {
		com.mojang.blaze3d.platform.Window w = Minecraft.getInstance().getWindow();
		double k = (double) w.getScreenWidth() / w.getGuiScaledWidth();
		org.lwjgl.glfw.GLFW.glfwSetCursorPos(w.handle(), gui[0] * k, gui[1] * k);
		try {
			// macOS doesn't fire the cursor callback for programmatic moves, so feed the handler directly
			java.lang.reflect.Method move = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove",
					long.class, double.class, double.class);
			move.setAccessible(true);
			move.invoke(Minecraft.getInstance().mouseHandler, w.handle(), gui[0] * k, gui[1] * k);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Presses and releases a mouse button where the cursor is, the way GLFW would report it. */
	private static void clickAt(int button) {
		Minecraft mc = Minecraft.getInstance();
		try {
			java.lang.reflect.Method onButton = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onButton",
					long.class, net.minecraft.client.input.MouseButtonInfo.class, int.class);
			onButton.setAccessible(true);
			net.minecraft.client.input.MouseButtonInfo info = new net.minecraft.client.input.MouseButtonInfo(button, 0);
			onButton.invoke(mc.mouseHandler, mc.getWindow().handle(), info, org.lwjgl.glfw.GLFW.GLFW_PRESS);
			onButton.invoke(mc.mouseHandler, mc.getWindow().handle(), info, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void openGui(Category category, String module) {
		GuiState.category = category;
		GuiState.selectedModule = module;
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new ClickGuiScreen(mc.level == null ? new TrollTitleScreen() : null));
	}

	private static Module module(String name) {
		return ModuleManager.byName(name);
	}

	private static void cmd(String command) {
		Minecraft.getInstance().player.connection.sendCommand(command);
	}

	private static void shot(String name) {
		Minecraft mc = Minecraft.getInstance();
		Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
				msg -> TrollClient.LOGGER.info("[devshots] {}", msg.getString()));
	}
}
