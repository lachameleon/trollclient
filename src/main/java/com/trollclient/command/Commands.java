package com.trollclient.command;

import com.mojang.blaze3d.platform.InputConstants;
import com.trollclient.chat.Chatroom;
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
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.Setting;
import com.trollclient.telemetry.Telemetry;
import com.trollclient.update.Updater;
import com.trollclient.util.Friends;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Client-side chat commands, e.g. {@code .toggle twerk} or {@code .follow Notch}. */
public final class Commands {
	/** What can come next after the words already typed (see {@link Spec}). */
	@FunctionalInterface
	public interface Completer {
		List<String> complete(List<String> before);
	}

	/**
	 * One command as {@code .help} and the chat tips describe it: its name, other spellings, the
	 * arguments it takes, and what could be typed next given what's there so far.
	 */
	public record Spec(String name, List<String> aliases, String args, String description, Completer completer) {
		public String usage(String prefix) {
			return prefix + name + (args.isEmpty() ? "" : " " + args);
		}

		public boolean answersTo(String word) {
			return name.equals(word) || aliases.contains(word);
		}
	}

	private static final Completer NOTHING = before -> List.of();

	/** In the order {@code .help} lists them. {@link #handle} is where they actually run. */
	private static final List<Spec> SPECS = List.of(
			new Spec("help", List.of("h", "?"), "", "list these commands", NOTHING),
			new Spec("toggle", List.of("t"), "<module>", "turn a module on/off",
					before -> before.isEmpty() ? moduleNames(true) : List.of()),
			new Spec("bind", List.of("b"), "<module> <key|none>", "set a keybind",
					before -> before.isEmpty() ? moduleNames(false) : before.size() == 1 ? List.of("none", "rshift", "lshift", "rctrl", "ralt") : List.of()),
			new Spec("set", List.of("s"), "<module> [setting] [value]", "change a setting", Commands::completeSet),
			new Spec("target", List.of(), "<player|nearest>", "set the target of every module that has one",
					before -> before.isEmpty() ? with("nearest", playerNames()) : List.of()),
			new Spec("follow", List.of(), "[player]", "follow someone with PlayerFollow",
					before -> before.isEmpty() ? playerNames() : List.of()),
			new Spec("morse", List.of(), "<message>", "spell a message with Morse", NOTHING),
			new Spec("friend", List.of("f"), "add|remove|list [name]", "manage friends", Commands::completeFriend),
			new Spec("macro", List.of("macros", "m"), "[list|run|stop|edit] [name]", "run macros, or open the editor", Commands::completeMacro),
			new Spec("chat", List.of("c"), "<message>", "talk in the chatroom: everyone running the client sees it", NOTHING),
			new Spec("mod", List.of(), "mute|unmute|unsay <player> | post <id> | thread <id> <action> | web", "moderate the chatroom and forum (moderators only)",
					Commands::completeMod),
			new Spec("update", List.of(), "", "check for a new version now", NOTHING),
			new Spec("modules", List.of("list"), "", "list every module", NOTHING),
			new Spec("gui", List.of("menu"), "", "open the menu", NOTHING),
			new Spec("save", List.of(), "", "write the config", NOTHING),
			new Spec("load", List.of(), "", "reload the config", NOTHING));

	private Commands() {
	}

	public static List<Spec> specs() {
		return SPECS;
	}

	/** The command {@code word} names, by name or alias, or null. */
	public static Spec spec(String word) {
		String lower = word.toLowerCase(Locale.ROOT);
		for (Spec s : SPECS) {
			if (s.answersTo(lower)) {
				return s;
			}
		}
		return null;
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
				case "chat", "c" -> chat(message, prefix);
				case "mod" -> Chatroom.moderate(Arrays.copyOfRange(args, 1, args.length));
				case "update" -> Updater.checkNow();
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
				default -> {
					Telemetry.command(cmd, false);
					error("Unknown command. Try " + prefix + "help");
					return true;
				}
			}
			Telemetry.command(cmd, true);
		} catch (RuntimeException e) {
			error("That didn't work: " + e.getMessage());
		}
		return true;
	}

	private static void help() {
		String p = prefix();
		info("Commands:");
		for (Spec spec : SPECS) {
			line(spec.usage(p), spec.description());
		}
	}

	/** Everything after the command word goes to the room as typed, spacing and all. */
	private static void chat(String message, String prefix) {
		String rest = message.substring(prefix.length()).trim();
		int space = rest.indexOf(' ');
		if (space < 0) {
			Chatroom.status();
		} else {
			Chatroom.say(rest.substring(space + 1).trim());
		}
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
		Telemetry.via("command", m::toggle);
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

	// ------------------------------------------------------------ completions, for the chat tips

	private static List<String> moduleNames(boolean toggleableOnly) {
		List<String> names = new ArrayList<>();
		for (Module m : ModuleManager.all()) {
			if (!toggleableOnly || m.isToggleable()) {
				names.add(m.getName());
			}
		}
		return names;
	}

	/** Everyone in the tab list but you. */
	private static List<String> playerNames() {
		Minecraft mc = Minecraft.getInstance();
		List<String> names = new ArrayList<>();
		if (mc.getConnection() != null && mc.player != null) {
			String me = mc.player.getGameProfile().name();
			mc.getConnection().getOnlinePlayers().forEach(info -> {
				String name = info.getProfile().name();
				if (!name.equalsIgnoreCase(me)) {
					names.add(name);
				}
			});
		}
		return names;
	}

	private static List<String> with(String first, List<String> rest) {
		List<String> all = new ArrayList<>();
		all.add(first);
		all.addAll(rest);
		return all;
	}

	private static List<String> completeSet(List<String> before) {
		if (before.isEmpty()) {
			return ModuleManager.all().stream().filter(m -> !m.getSettings().isEmpty()).map(Module::getName).toList();
		}
		Module m = ModuleManager.byName(before.get(0));
		if (m == null) {
			return List.of();
		}
		if (before.size() == 1) {
			return m.getSettings().stream().map(s -> s.getName().replace(" ", "")).toList();
		}
		Setting<?> setting = m.getSetting(before.get(1));
		if (before.size() != 2 || setting == null) {
			return List.of();
		}
		if (setting instanceof ModeSetting mode) {
			return mode.getModes();
		}
		if (setting instanceof BoolSetting) {
			return List.of("true", "false");
		}
		return List.of();
	}

	/** For the chat tips: what {@code .set <module> <setting>} is pointing at and what it's set to, or null. */
	public static String settingHint(List<String> before) {
		if (before.size() < 2) {
			return null;
		}
		Module m = ModuleManager.byName(before.get(0));
		Setting<?> setting = m == null ? null : m.getSetting(before.get(1));
		return setting == null ? null : m.getName() + "." + setting.getName() + " = " + setting.display() + "  -  " + setting.getDescription();
	}

	private static List<String> completeFriend(List<String> before) {
		if (before.isEmpty()) {
			return List.of("add", "remove", "list");
		}
		if (before.size() != 1) {
			return List.of();
		}
		return switch (before.get(0).toLowerCase(Locale.ROOT)) {
			case "add" -> playerNames().stream().filter(n -> !Friends.isFriend(n)).toList();
			case "remove", "del" -> new ArrayList<>(Friends.all());
			default -> List.of();
		};
	}

	private static List<String> completeMod(List<String> before) {
		if (before.isEmpty()) {
			return List.of("mute", "unmute", "unsay", "post", "thread", "web");
		}
		if (before.size() == 2 && before.get(0).equalsIgnoreCase("thread")) {
			return List.of("lock", "unlock", "pin", "unpin", "hide", "show");
		}
		return List.of();
	}

	private static List<String> completeMacro(List<String> before) {
		if (before.isEmpty()) {
			return List.of("list", "run", "stop", "edit");
		}
		String action = before.get(0).toLowerCase(Locale.ROOT);
		if (before.size() == 1 && (action.equals("run") || action.equals("start") || action.equals("stop") || action.equals("edit"))) {
			return MacroManager.macros().stream().map(Macro::getName).toList();
		}
		return List.of();
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
