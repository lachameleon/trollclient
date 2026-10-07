package com.trollclient.module.chat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Friends;
import com.trollclient.util.Targets;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Repeats what people say, but worse. */
public class Parrot extends Module {
	private record Pending(String text, long sendAt) {
	}

	private final TextSetting target = add(new TextSetting("Target", "Only copy this player (blank = everyone)", "", 16)
			.suggestions(Targets::onlineNames).placeholder("everyone"));
	private final ModeSetting style = add(new ModeSetting("Style", "How the copy gets mangled", "Mock",
			"Mock", "Echo", "Reverse", "Uwu", "Shout", "Whisper", "Random"));
	private final TextSetting prefix = add(new TextSetting("Prefix", "Put in front of every copy, e.g. \"> \"", "", 16)
			.placeholder("none"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Wait this long before replying", 1200, 0, 10000, 100)
			.unit("ms"));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Minimum gap between copies (servers kick spammers)", 4, 1, 60, 1)
			.unit("s"));
	private final NumberSetting chance = add(new NumberSetting("Chance", "Chance to copy any given message", 100, 5, 100, 5)
			.unit("%"));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Leave your friends' messages alone", true));
	private final BoolSetting skipLinks = add(new BoolSetting("Skip Links", "Don't repeat messages with links or commands", true));

	private final Deque<Pending> queue = new ArrayDeque<>();
	private long lastSent;

	public Parrot() {
		super("Parrot", "Repeats other players' chat back at them in mOcKiNg case (or worse).", Category.CHAT);
	}

	@Override
	protected void onEnable() {
		queue.clear();
	}

	@Override
	public void onWorldLeave() {
		queue.clear();
	}

	@Override
	public void onChatMessage(String sender, String message) {
		String who = target.get().trim();
		if (!who.isEmpty() && !who.equalsIgnoreCase(sender)) {
			return;
		}
		if (ignoreFriends.get() && Friends.isFriend(sender)) {
			return;
		}
		if (message.isBlank() || ThreadLocalRandom.current().nextInt(100) >= chance.getInt()) {
			return;
		}
		String lower = message.toLowerCase(Locale.ROOT);
		if (skipLinks.get() && (lower.contains("http") || lower.contains("www.") || message.startsWith("/"))) {
			return;
		}
		// one reply in flight at a time; anything newer replaces it
		queue.clear();
		queue.add(new Pending(transform(message), System.currentTimeMillis() + delay.getInt()));
	}

	@Override
	public void onTick() {
		Pending next = queue.peek();
		if (next == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < next.sendAt() || now - lastSent < cooldown.get() * 1000) {
			return;
		}
		queue.poll();
		lastSent = now;
		ChatUtil.send(prefix.get() + next.text());
	}

	public String transform(String message) {
		String mode = style.get();
		if (mode.equals("Random")) {
			String[] pool = {"Mock", "Echo", "Reverse", "Uwu", "Shout", "Whisper"};
			mode = pool[ThreadLocalRandom.current().nextInt(pool.length)];
		}
		return switch (mode) {
			case "Mock" -> mock(message);
			case "Reverse" -> new StringBuilder(message).reverse().toString();
			case "Uwu" -> uwu(message);
			case "Shout" -> message.toUpperCase(Locale.ROOT) + "!!";
			case "Whisper" -> "*" + message.toLowerCase(Locale.ROOT) + "*";
			default -> message;
		};
	}

	/** sPoNgEbOb CaSe, skipping non-letters so the rhythm stays even. */
	static String mock(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		boolean upper = false;
		for (char c : s.toCharArray()) {
			if (Character.isLetter(c)) {
				sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
				upper = !upper;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	static String uwu(String s) {
		String out = s.replaceAll("[rl]", "w").replaceAll("[RL]", "W")
				.replaceAll("n([aeiou])", "ny$1").replaceAll("N([aeiouAEIOU])", "Ny$1")
				.replace("ove", "uv");
		String[] faces = {" uwu", " owo", " >w<", " ^w^", " :3"};
		return out + faces[ThreadLocalRandom.current().nextInt(faces.length)];
	}

	@Override
	public String getInfo() {
		return style.get();
	}
}
