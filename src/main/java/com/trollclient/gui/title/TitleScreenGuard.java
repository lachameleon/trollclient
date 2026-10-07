package com.trollclient.gui.title;

import com.trollclient.TrollClient;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.TitleScreenModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.Locale;

/**
 * Decides when a main menu gets swapped for ours. Three layers, so other mods
 * can't sneak their menu past us: the {@code Gui#setScreen} mixin, a check
 * after every screen init, and a per-tick fallback for anything that sets the
 * screen some other way.
 */
public final class TitleScreenGuard {
	/**
	 * Set when the user asks for the vanilla menu via "[ vanilla menu ]". Until they come back with the
	 * "Troll Client" button (or join a world), no main menu gets replaced, including one another mod
	 * swaps in for the vanilla screen we opened.
	 */
	private static boolean vanillaRequested;
	private static long windowStart;
	private static int replacedInWindow;
	private static boolean gaveUp;

	private TitleScreenGuard() {
	}

	/** The user wants the normal main menu: stop replacing main menus until they come back. */
	public static void requestVanilla() {
		vanillaRequested = true;
	}

	public static boolean isVanillaRequested() {
		return vanillaRequested;
	}

	/** True if this screen is a main menu (vanilla or modded) that we should replace. */
	public static boolean shouldReplace(Screen screen) {
		if (screen == null || screen instanceof TrollTitleScreen || vanillaRequested || gaveUp) {
			return false;
		}
		TitleScreenModule module = ModuleManager.get(TitleScreenModule.class);
		if (module == null || !module.isEnabled()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			return false;
		}
		// subclasses count too: several menu mods extend the vanilla title screen
		if (screen instanceof TitleScreen) {
			return true;
		}
		return module.replaceModded.get() && isModdedMainMenu(screen);
	}

	private static boolean isModdedMainMenu(Screen screen) {
		String name = screen.getClass().getSimpleName().toLowerCase(Locale.ROOT);
		return name.endsWith("titlescreen") || name.endsWith("mainmenuscreen") || name.endsWith("mainmenu");
	}

	/** Mixin hook for {@code Gui#setScreen}. */
	public static Screen filter(Screen screen) {
		Screen menu = customMenu(screen);
		if (menu != null) {
			return menu;
		}
		if (!shouldReplace(screen)) {
			return screen;
		}
		return countReplacement(screen) ? new TrollTitleScreen() : screen;
	}

	/** Vanilla world / server lists become ours while the TitleScreen module's "Custom Menus" is on. */
	private static Screen customMenu(Screen screen) {
		TitleScreenModule module = ModuleManager.get(TitleScreenModule.class);
		if (screen == null || module == null || !module.isEnabled() || !module.customMenus.get()) {
			return null;
		}
		Class<?> c = screen.getClass();
		if (c == net.minecraft.client.gui.screens.worldselection.SelectWorldScreen.class) {
			return new com.trollclient.gui.menus.TrollWorldsScreen(parentOf(screen, c));
		}
		if (c == net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen.class) {
			return new com.trollclient.gui.menus.TrollServersScreen(parentOf(screen, c));
		}
		return null;
	}

	private static Screen parentOf(Screen screen, Class<?> c) {
		try {
			java.lang.reflect.Field f = c.getDeclaredField("lastScreen");
			f.setAccessible(true);
			return (Screen) f.get(screen);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/** Fallback for menus opened without {@code Gui#setScreen}, or swapped in after we ran. */
	public static void tick(Minecraft mc) {
		if (mc.level != null) {
			// the vanilla menu was for this visit only; back on the title screen after leaving the world
			vanillaRequested = false;
		}
		Screen current = mc.gui.screen();
		if (mc.gui.overlay() == null && shouldReplace(current) && countReplacement(current)) {
			TrollClient.LOGGER.info("Replacing {} with the Troll Client title screen", current.getClass().getName());
			mc.gui.setScreen(new TrollTitleScreen());
		}
	}

	/**
	 * Screen init hook: whichever main menu shows while the user wanted the vanilla one
	 * (vanilla's, or one another mod put in its place) gets a way back to ours.
	 * (Swapping a screen from inside its own init isn't safe, so replacing is left to
	 * the mixin and {@link #tick}.)
	 */
	public static void afterInit(Minecraft mc, Screen screen, int width) {
		if (!vanillaRequested || mc.level != null || screen instanceof TrollTitleScreen
				|| !(screen instanceof TitleScreen || isModdedMainMenu(screen))) {
			return;
		}
		TitleScreenModule module = ModuleManager.get(TitleScreenModule.class);
		if (module == null || !module.isEnabled()) {
			return;
		}
		net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(
				net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.literal("Troll Client"), b -> {
					vanillaRequested = false;
					mc.gui.setScreen(new TrollTitleScreen());
				}).bounds(4, 4, 80, 20).build());
	}

	/**
	 * Guards against a tug of war with a mod that keeps forcing its own menu back:
	 * after a dozen swaps in five seconds we stop and let it win.
	 */
	private static boolean countReplacement(Screen screen) {
		long now = System.currentTimeMillis();
		if (now - windowStart > 5000) {
			windowStart = now;
			replacedInWindow = 0;
		}
		if (++replacedInWindow > 12) {
			gaveUp = true;
			TrollClient.LOGGER.warn("Another mod keeps reopening {}; leaving the main menu to it", screen.getClass().getName());
			return false;
		}
		return true;
	}
}
