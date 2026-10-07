package com.trollclient.command;

import com.mojang.blaze3d.platform.InputConstants;
import com.trollclient.config.ConfigManager;
import com.trollclient.gui.clickgui.ClickGuiScreen;
import com.trollclient.gui.clickgui.components.BindRow;
import com.trollclient.gui.macro.MacroEditorScreen;
import com.trollclient.macro.Macro;
import com.trollclient.macro.MacroManager;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.module.movement.PlayerFollow;
import com.trollclient.setting.Setting;
import com.trollclient.util.Friends;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Client-side chat commands, e.g. {@code .toggle twerk} or {@code .follow Notch}. */
public final class Commands {
	private Commands() {
	}

	public static String prefix() {
		String p = ModuleManager.get(ClickGuiModule.class).prefix.get();
		return p.isEmpty() ? "." : p;
	}

	/** @return true if the message was a client command (and must not be sent). */
	public static boolean handle(String message) {
		String prefix = prefix();
		if (!message.startsWith(prefix) || message.length() <= prefix.length()) {
			return false;
		}
		String[] args = message.substring(prefix.length()).trim().split("\\s+");
		String cmd = args[0].toLowerCase(Locale.ROOT);
		try {
			switch (cmd) {
				case "help", "h", "?" -> help();
				case "toggle", "t" -> toggle(args);
				case "bind", "b" -> bind(args);
				case "follow" -> follow(args);
				case "target" -> target(args);
				case "morse" -> morse(args);
				case "macro", "macros", "m" -> macro(args);
				case "friend", "f" -> friend(args);
				case "set", "s" -> set(args);
				case "modules", "list" -> list();
				case "gui", "menu" -> Minecraft.getInstance().execute(() ->
						Minecraft.getInstance().gui.setScreen(new ClickGuiScreen(null)));
				case "save" -> {
					ConfigManager.save();
					info("Config saved.");
				}
				case "load" -> {
					ConfigManager.load();
					info("Config reloaded.");
				}
				default -> error("Unknown command. Try " + prefix + "help");
			}
		} catch (RuntimeException e) {
			error("That didn't work: " + e.getMessage());
		}
		return true;
	}

	private static void help() {
		String p = prefix();
		info("Commands:");
		line(p + "toggle <module>", "turn a module on/off");
		line(p + "bind <module> <key|none>", "set a keybind");
		line(p + "follow <player>", "follow someone with PlayerFollow");
		line(p + "target <player|nearest>", "set the target of every module that has one");
		line(p + "morse <message>", "spell a message with Morse");
		line(p + "macro [list|run|stop|edit] [name]", "run macros, or open the editor");
		line(p + "friend add|remove|list [name]", "manage friends");
		line(p + "set <module> <setting> <value>", "change a setting");
		line(p + "modules", "list every module");
		line(p + "gui", "open the menu");
		line(p + "save / " + p + "load", "write or reload the config");
	}

	private static void toggle(String[] args) {
		Module m = module(args, 1);
		if (m == null) {
			return;
		}
		if (!m.isToggleable()) {
			error(m.getName() + " is settings-only.");
			return;
		}
		m.toggle();
	}

	private static void bind(String[] args) {
		Module m = module(args, 1);
		if (m == null) {
			return;
		}
		if (args.length < 3) {
			info(m.getName() + " is bound to " + BindRow.keyName(m.getKey()));
			return;
		}
		String keyName = args[2].toLowerCase(Locale.ROOT);
		if (keyName.equals("none") || keyName.equals("unbind")) {
			m.setKey(GLFW.GLFW_KEY_UNKNOWN);
			info("Unbound " + m.getName());
			return;
		}
		int key = parseKey(keyName);
		if (key <= 0) {
			error("Unknown key: " + args[2]);
			return;
		}
		m.setKey(key);
		info("Bound " + m.getName() + " to " + BindRow.keyName(key));
	}

	private static int parseKey(String name) {
		switch (name) {
			case "rshift" -> {
				return GLFW.GLFW_KEY_RIGHT_SHIFT;
			}
			case "lshift" -> {
				return GLFW.GLFW_KEY_LEFT_SHIFT;
			}
			case "rctrl" -> {
				return GLFW.GLFW_KEY_RIGHT_CONTROL;
			}
			case "ralt" -> {
				return GLFW.GLFW_KEY_RIGHT_ALT;
			}
			default -> {
			}
		}
		InputConstants.Key key = InputConstants.getKey("key.keyboard." + name);
		return key == InputConstants.UNKNOWN ? -1 : key.getValue();
	}

	private static void follow(String[] args) {
		PlayerFollow follow = ModuleManager.get(PlayerFollow.class);
		if (args.length < 2) {
			follow.setTarget("");
			info("Following the nearest player.");
		} else {
			follow.setTarget(args[1]);
			info("Following " + args[1] + ".");
		}
		if (!follow.isEnabled()) {
			follow.toggle();
		}
	}

	private static void target(String[] args) {
		String name = args.length < 2 || args[1].equalsIgnoreCase("nearest") ? "" : args[1];
		StringBuilder changed = new StringBuilder();
		for (Module m : ModuleManager.all()) {
			Setting<?> s = m.getSetting("Target");
			if (s instanceof com.trollclient.setting.TextSetting text) {
				text.set(name);
				changed.append(changed.isEmpty() ? "" : ", ").append(m.getName());
			}
		}
		info((name.isEmpty() ? "Targeting the nearest player" : "Targeting " + name) + " in " + changed + ".");
	}

	private static void morse(String[] args) {
		com.trollclient.module.troll.Morse morse = ModuleManager.get(com.trollclient.module.troll.Morse.class);
		if (args.length < 2) {
			info("Usage: " + prefix() + "morse <message>");
			return;
		}
		morse.setMessage(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
		if (!morse.isEnabled()) {
			morse.toggle();
		}
		info("Spelling \"" + String.join(" ", Arrays.copyOfRange(args, 1, args.length)) + "\" in Morse.");
	}

	private static void macro(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "edit";
		String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
		switch (action) {
			case "list", "ls" -> {
				if (MacroManager.macros().isEmpty()) {
					info("No macros yet. " + prefix() + "macro opens the editor.");
					return;
				}
				MutableComponent msg = Component.literal("");
				for (Macro m : MacroManager.macros()) {
					boolean running = MacroManager.isRunning(m);
					String key = m.getKey() > 0 ? " [" + BindRow.keyName(m.getKey()) + "]" : "";
					msg.append(Component.literal(m.getName() + key + "  ").withStyle(running ? ChatFormatting.WHITE : ChatFormatting.GRAY));
				}
				send(msg);
			}
			case "run", "start" -> {
				Macro m = MacroManager.byName(name);
				if (m == null) {
					error("No macro called \"" + name + "\". " + prefix() + "macro list shows them.");
					return;
				}
				MacroManager.start(m);
				info("Running " + m.getName() + ".");
			}
			case "stop" -> {
				if (name.isEmpty()) {
					info("Stopped " + MacroManager.stopAll() + " macro(s).");
					return;
				}
				Macro m = MacroManager.byName(name);
				if (m == null) {
					error("No macro called \"" + name + "\".");
					return;
				}
				MacroManager.stop(m);
				info("Stopped " + m.getName() + ".");
			}
			default -> {
				Macro m = name.isEmpty() ? null : MacroManager.byName(name);
				// chat closes after the command runs; open the editor once it has
				Minecraft.getInstance().execute(() -> MacroEditorScreen.open(m));
			}
		}
	}

	private static void friend(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
		switch (action) {
			case "add" -> {
				requireArgs(args, 3);
				info(Friends.add(args[2]) ? "Added " + args[2] + " as a friend." : args[2] + " is already a friend.");
			}
			case "remove", "del" -> {
				requireArgs(args, 3);
				info(Friends.remove(args[2]) ? "Removed " + args[2] + "." : args[2] + " isn't a friend.");
			}
			default -> info(Friends.all().isEmpty() ? "No friends yet. Ouch." : "Friends: " + String.join(", ", Friends.all()));
		}
	}

	private static void set(String[] args) {
		Module m = module(args, 1);
		if (m == null) {
			return;
		}
		if (args.length < 3) {
			info(m.getName() + " settings: " + m.getSettings().stream()
					.map(s -> s.getName().replace(" ", "") + "=" + s.display()).collect(Collectors.joining(", ")));
			return;
		}
		Setting<?> s = m.getSetting(args[2]);
		if (s == null) {
			error("No setting " + args[2] + " on " + m.getName());
			return;
		}
		if (args.length < 4) {
			info(s.getName() + " = " + s.display());
			return;
		}
		String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
		if (s.parse(value)) {
			info(m.getName() + "." + s.getName() + " = " + s.display());
		} else {
			error("Couldn't parse \"" + value + "\"");
		}
	}

	private static void list() {
		MutableComponent msg = Component.literal("");
		for (Module m : ModuleManager.all()) {
			msg.append(Component.literal(m.getName() + " ").withStyle(m.isEnabled() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
		}
		send(msg);
	}

	private static Module module(String[] args, int index) {
		if (args.length <= index) {
			error("Which module?");
			return null;
		}
		Module m = ModuleManager.byName(args[index]);
		if (m == null) {
			error("No module called " + args[index]);
		}
		return m;
	}

	private static void requireArgs(String[] args, int count) {
		if (args.length < count) {
			throw new IllegalArgumentException("not enough arguments");
		}
	}

	private static MutableComponent tag() {
		return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal("troll").withStyle(ChatFormatting.WHITE))
				.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void send(Component c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(tag().append(c));
		}
	}

	public static void info(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	private static void error(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.RED));
	}

	private static void line(String usage, String what) {
		send(Component.literal(usage).withStyle(ChatFormatting.WHITE)
				.append(Component.literal(" - " + what).withStyle(ChatFormatting.DARK_GRAY)));
	}
}
