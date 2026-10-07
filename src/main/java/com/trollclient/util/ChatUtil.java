package com.trollclient.util;

import com.mojang.authlib.GameProfile;
import com.trollclient.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.util.StringUtil;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incoming chat gets normalised to (sender, text) before modules see it, and
 * outgoing chat goes through one place that strips anything the server would
 * kick you for.
 */
public final class ChatUtil {
	private static final int MAX_LENGTH = 256;
	private static boolean sending;
	/** Common plugin chat formats: "<name> hi", "[rank] name: hi", "name » hi". */
	private static final Pattern[] SYSTEM_FORMATS = {
			Pattern.compile("^<([A-Za-z0-9_]{2,16})> (.+)$"),
			Pattern.compile("^(?:\\[[^\\]]{1,24}\\] ?)*([A-Za-z0-9_]{2,16}) ?(?::|»|>>|\\|) (.+)$"),
	};

	private ChatUtil() {
	}

	/** Fabric CHAT event: a signed (or unsigned) player chat message. */
	public static void onPlayerChat(Component message, PlayerChatMessage signed, GameProfile sender) {
		if (sender == null) {
			onSystemChat(message, false);
			return;
		}
		String text = signed != null ? signed.signedContent() : message.getString();
		dispatch(sender.name(), text);
	}

	/** Fabric GAME event: servers that format chat themselves send it like this. */
	public static void onSystemChat(Component message, boolean overlay) {
		if (overlay) {
			return;
		}
		String raw = StringUtil.stripColor(message.getString()).trim();
		if (raw.startsWith("[troll]")) {
			return; // our own command feedback
		}
		for (Pattern p : SYSTEM_FORMATS) {
			Matcher m = p.matcher(raw);
			if (m.matches() && isOnline(m.group(1))) {
				dispatch(m.group(1), m.group(2));
				return;
			}
		}
	}

	private static void dispatch(String sender, String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || sender.equalsIgnoreCase(mc.player.getGameProfile().name())) {
			return;
		}
		ModuleManager.chatMessage(sender, text.trim());
	}

	private static boolean isOnline(String name) {
		for (String n : Targets.onlineNames()) {
			if (n.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/** Sends a public chat line, or a command if it starts with a slash. */
	public static void send(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getConnection() == null) {
			return;
		}
		String clean = sanitize(text);
		if (clean.isEmpty()) {
			return;
		}
		sending = true;
		try {
			if (clean.startsWith("/")) {
				mc.getConnection().sendCommand(clean.substring(1));
			} else {
				mc.getConnection().sendChat(clean);
			}
		} finally {
			sending = false;
		}
	}

	/** True while a module's line is going out through {@link #send}, as opposed to something the player typed. */
	public static boolean isSending() {
		return sending;
	}

	public static String sanitize(String text) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (StringUtil.isAllowedChatCharacter(c)) {
				sb.append(c);
			}
		}
		String s = sb.toString().trim();
		return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
	}

	/** Fills {player}, {me} and {count} in user templates. */
	public static String format(String template, String player, int count) {
		Minecraft mc = Minecraft.getInstance();
		String me = mc.player == null ? "me" : mc.player.getGameProfile().name();
		return template.replace("{player}", player).replace("{me}", me)
				.replace("{count}", Integer.toString(count))
				.replace("{s}", count == 1 ? "" : "s");
	}

	/** Picks one option from a "a | b | c" list. */
	public static String pick(String options) {
		String[] parts = options.split("\\|");
		String choice = parts[java.util.concurrent.ThreadLocalRandom.current().nextInt(parts.length)].trim();
		return choice.isEmpty() ? options.trim() : choice;
	}

	public static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
