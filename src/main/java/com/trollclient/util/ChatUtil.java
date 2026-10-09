package com.trollclient.util;

import com.mojang.authlib.GameProfile;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ChatFormatModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incoming chat gets normalised to (sender, text) before modules see it, and
 * outgoing chat goes through one place that strips anything the server would
 * kick you for.
 *
 * <p>Servers lay chat out every which way ("[Owner] ~Nick » hi", "Party > Steve: hi",
 * "<[VIP] Steve> hi"), so a line counts as a player's chat when someone in the
 * tab list (by account name or tab list nickname) is followed by a separator
 * and a message. {@link ChatFormatModule} adds exact layouts for anything weirder.</p>
 */
public final class ChatUtil {
	private static final int MAX_LENGTH = 256;
	private static boolean sending;
	private static String lastLine;
	/** Vanilla's own layout, which may have a rank inside the brackets: "<[VIP] Steve> hi". */
	private static final Pattern VANILLA = Pattern.compile("^<([^<>]{1,48})> (.+)$");
	/** Right after a name: closing brackets, then a separator, then the message. */
	private static final Pattern AFTER_NAME = Pattern.compile(
			"^[\\])}>]*\\s?(?::|»|>>|>|\\||➥|→|⇒|›|➤|➜|-)\\s*(.+)$");
	/** Formats are parsed once, then reused until the setting changes. */
	private static String compiledFrom;
	private static List<Pattern> custom = List.of();

	private ChatUtil() {
	}

	/** Fabric CHAT event: a signed (or unsigned) player chat message. */
	public static void onPlayerChat(Component message, PlayerChatMessage signed, GameProfile sender) {
		if (sender == null) {
			onSystemChat(message, false);
			return;
		}
		if (signed != null) {
			dispatch(sender.name(), signed.signedContent());
			return;
		}
		// unsigned chat arrives fully decorated: take the message back out of the layout if we can
		String raw = StringUtil.stripColor(message.getString()).trim();
		String[] parsed = parse(raw);
		dispatch(sender.name(), parsed != null ? parsed[1] : raw);
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
		lastLine = raw;
		String[] parsed = parse(raw);
		if (parsed != null) {
			dispatch(parsed[0], parsed[1]);
		}
	}

	/** The last line the server sent that wasn't our own feedback, for testing formats. */
	public static String lastLine() {
		return lastLine;
	}

	/** {account name, message} if {@code line} is a player talking, otherwise null. */
	public static String[] parse(String line) {
		Map<String, String> names = names();
		for (Pattern p : customFormats()) {
			Matcher m = p.matcher(line);
			if (m.matches()) {
				String who = cleanName(m.group("player"));
				String account = names.get(who.toLowerCase(Locale.ROOT));
				return new String[]{account != null ? account : who, m.group("message").trim()};
			}
		}
		Matcher vanilla = VANILLA.matcher(line);
		if (vanilla.matches()) {
			String[] words = vanilla.group(1).trim().split("\\s+");
			String account = names.get(cleanName(words[words.length - 1]).toLowerCase(Locale.ROOT));
			if (account != null) {
				return new String[]{account, vanilla.group(2).trim()};
			}
		}
		// the earliest known name that's followed by a separator; ranks and tags before it are ignored
		String lower = line.toLowerCase(Locale.ROOT);
		int bestAt = Integer.MAX_VALUE;
		String[] best = null;
		for (Map.Entry<String, String> e : names.entrySet()) {
			String name = e.getKey();
			for (int at = lower.indexOf(name); at >= 0 && at < Math.min(bestAt, 64); at = lower.indexOf(name, at + 1)) {
				int after = at + name.length();
				if ((at > 0 && isNameChar(lower.charAt(at - 1))) || (after < lower.length() && isNameChar(lower.charAt(after)))) {
					continue;
				}
				Matcher m = AFTER_NAME.matcher(line.substring(after));
				if (m.matches() && !m.group(1).isBlank()) {
					bestAt = at;
					best = new String[]{e.getValue(), m.group(1).trim()};
					break;
				}
			}
		}
		return best;
	}

	/** Lowercase account names and tab list nicknames of everyone online, each mapped to the account name. Longest first. */
	private static Map<String, String> names() {
		Minecraft mc = Minecraft.getInstance();
		List<String[]> pairs = new ArrayList<>();
		if (mc.getConnection() != null) {
			ChatFormatModule format = ModuleManager.get(ChatFormatModule.class);
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				String account = info.getProfile().name();
				pairs.add(new String[]{account.toLowerCase(Locale.ROOT), account});
				Component display = info.getTabListDisplayName();
				if (format.nicknames.get() && display != null) {
					String shown = StringUtil.stripColor(display.getString()).trim();
					String[] words = shown.split("\\s+");
					// "[Owner] ~Nick" -> "~nick" and "nick"
					String last = words[words.length - 1];
					for (String nick : new String[]{last, cleanName(last)}) {
						if (nick.length() >= 2) {
							pairs.add(new String[]{nick.toLowerCase(Locale.ROOT), account});
						}
					}
				}
			}
		}
		pairs.sort((a, b) -> b[0].length() - a[0].length());
		Map<String, String> map = new LinkedHashMap<>();
		for (String[] p : pairs) {
			map.putIfAbsent(p[0], p[1]);
		}
		return map;
	}

	private static boolean isNameChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	/** Drops brackets and nickname markers around a name: "[~Steve]" -> "Steve". */
	private static String cleanName(String s) {
		return s.replaceAll("^[^A-Za-z0-9_]+|[^A-Za-z0-9_]+$", "");
	}

	/** "[*] {player} » {message}" style templates from {@link ChatFormatModule}, as regexes. */
	private static List<Pattern> customFormats() {
		String setting = ModuleManager.get(ChatFormatModule.class).formats.get();
		if (setting.equals(compiledFrom)) {
			return custom;
		}
		List<Pattern> list = new ArrayList<>();
		for (String template : setting.split("\\|")) {
			template = template.trim();
			if (!template.contains("{player}") || !template.contains("{message}")) {
				continue;
			}
			StringBuilder regex = new StringBuilder("^");
			for (String part : template.split("(?=\\{player\\}|\\{message\\}|\\*)|(?<=\\{player\\}|\\{message\\}|\\*)")) {
				switch (part) {
					case "{player}" -> regex.append("(?<player>\\S{2,40}?)");
					case "{message}" -> regex.append("(?<message>.+)");
					case "*" -> regex.append(".*?");
					default -> regex.append(Pattern.quote(part));
				}
			}
			try {
				list.add(Pattern.compile(regex.append("$").toString()));
			} catch (RuntimeException ignored) {
				// a template with {player} twice, say: skip it rather than break chat
			}
		}
		custom = list;
		compiledFrom = setting;
		return list;
	}

	private static void dispatch(String sender, String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || sender.equalsIgnoreCase(mc.player.getGameProfile().name())) {
			return;
		}
		if (ModuleManager.get(ChatFormatModule.class).showParsed.get()) {
			com.trollclient.command.Commands.info(sender + ": " + text.trim());
		}
		ModuleManager.chatMessage(sender, text.trim());
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
