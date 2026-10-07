package com.trollclient.module.chat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Rewrites everything you say in chat: fancy letters, leetspeak, a signature... */
public class ChatStyle extends Module {
	private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
	private static final String BUBBLE = "ⓐⓑⓒⓓⓔⓕⓖⓗⓘⓙⓚⓛⓜⓝⓞⓟⓠⓡⓢⓣⓤⓥⓦⓧⓨⓩ";

	private final ModeSetting style = add(new ModeSetting("Style", "How your messages get rewritten", "Small Caps",
			"None", "Small Caps", "Fullwidth", "Bubble", "Leet", "Mock", "Uwu", "Reverse"));
	private final TextSetting prefix = add(new TextSetting("Prefix", "Added in front of every message", "", 24)
			.placeholder("none"));
	private final TextSetting suffix = add(new TextSetting("Suffix", "Added after every message", " | troll client", 32)
			.placeholder("none"));
	private final BoolSetting styleSuffix = add(new BoolSetting("Style Suffix", "Apply the style to the prefix and suffix too", false));

	public ChatStyle() {
		super("ChatStyle", "Rewrites your own chat: ꜱᴍᴀʟʟ ᴄᴀᴘꜱ, ｆｕｌｌｗｉｄｔｈ, l33t, uwu, plus a signature.", Category.CHAT);
	}

	/** Fabric MODIFY_CHAT hook. Client commands never get here, they're cancelled earlier. */
	public String modify(String message) {
		if (!isEnabled() || message.isBlank()) {
			return message;
		}
		String body = apply(message);
		String pre = styleSuffix.get() ? apply(prefix.get()) : prefix.get();
		String post = styleSuffix.get() ? apply(suffix.get()) : suffix.get();
		return ChatUtil.sanitize(pre + body + post);
	}

	public String apply(String s) {
		return switch (style.get()) {
			case "Small Caps" -> mapLetters(s, SMALL_CAPS);
			case "Bubble" -> mapLetters(s, BUBBLE);
			case "Fullwidth" -> fullwidth(s);
			case "Leet" -> leet(s);
			case "Mock" -> Parrot.mock(s);
			case "Uwu" -> Parrot.uwu(s);
			case "Reverse" -> new StringBuilder(s).reverse().toString();
			default -> s;
		};
	}

	private static String mapLetters(String s, String table) {
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
			sb.append(c >= 'a' && c <= 'z' ? table.charAt(c - 'a') : c);
		}
		return sb.toString();
	}

	/** ASCII 0x21..0x7E have fullwidth twins at 0xFF01..0xFF5E; space becomes an ideographic space. */
	private static String fullwidth(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toCharArray()) {
			if (c == ' ') {
				sb.append('　');
			} else if (c >= '!' && c <= '~') {
				sb.append((char) (c - '!' + 0xFF01));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String leet(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (char c : s.toCharArray()) {
			char l = Character.toLowerCase(c);
			String r = switch (l) {
				case 'a' -> "4";
				case 'e' -> "3";
				case 'i' -> "1";
				case 'o' -> "0";
				case 's' -> "5";
				case 't' -> "7";
				case 'b' -> "8";
				case 'g' -> rnd.nextBoolean() ? "9" : "g";
				default -> String.valueOf(c);
			};
			sb.append(r);
		}
		return sb.toString();
	}

	@Override
	public String getInfo() {
		return style.get();
	}
}
