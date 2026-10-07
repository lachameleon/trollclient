package com.trollclient.module.chat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gets a word wrong in what you say, then corrects it a moment later, like a real person. */
public class Typo extends Module {
	private static final Pattern WORD = Pattern.compile("(?<![A-Za-z0-9_/.:])[A-Za-z]{4,}(?![A-Za-z0-9_/.:])");
	/** QWERTY rows, for fat-finger neighbours. */
	private static final String[] ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};

	private final NumberSetting chance = add(new NumberSetting("Chance", "Chance a message gets a typo", 50, 5, 100, 5).unit("%"));
	private final ModeSetting kind = add(new ModeSetting("Kind", "What sort of mistake", "Mixed",
			"Mixed", "Swap", "Fat Finger", "Double", "Drop"));
	private final BoolSetting correct = add(new BoolSetting("Correct", "Follow up with a correction", true));
	private final TextSetting correction = add(new TextSetting("Correction", "How the fix is sent. {word} is the right spelling",
			"*{word}", 32)).visibleWhen(correct::get);
	private final NumberSetting delay = add(new NumberSetting("Correction Delay", "How long until you notice", 1500, 200, 8000, 100)
			.unit("ms")).visibleWhen(correct::get);
	private final BoolSetting onlyTyped = add(new BoolSetting("Only Typed", "Leave lines other modules send alone", true));

	private String pending;
	private long sendAt;
	private boolean correcting;
	private int typos;

	public Typo() {
		super("Typo", "Misspells a word in your messages, then sends a \"*correction\" a moment later.", Category.CHAT);
	}

	@Override
	protected void onEnable() {
		pending = null;
	}

	@Override
	public void onWorldLeave() {
		pending = null;
	}

	/** Fabric MODIFY_CHAT hook, registered before ChatStyle so the typo gets styled too. */
	public String modify(String message) {
		if (!isEnabled() || correcting || message.isBlank() || (onlyTyped.get() && ChatUtil.isSending())) {
			return message;
		}
		if (ThreadLocalRandom.current().nextInt(100) >= chance.getInt()) {
			return message;
		}
		List<int[]> words = new ArrayList<>();
		Matcher m = WORD.matcher(message);
		while (m.find()) {
			words.add(new int[]{m.start(), m.end()});
		}
		if (words.isEmpty()) {
			return message;
		}
		int[] pick = words.get(ThreadLocalRandom.current().nextInt(words.size()));
		String word = message.substring(pick[0], pick[1]);
		String wrong = mistype(word);
		if (wrong.equals(word)) {
			return message;
		}
		typos++;
		if (correct.get()) {
			pending = correction.get().replace("{word}", word);
			sendAt = System.currentTimeMillis() + delay.getInt();
		}
		return message.substring(0, pick[0]) + wrong + message.substring(pick[1]);
	}

	public String mistype(String word) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		String mode = kind.get();
		if (mode.equals("Mixed")) {
			String[] pool = {"Swap", "Fat Finger", "Double", "Drop"};
			mode = pool[rnd.nextInt(pool.length)];
		}
		// leave the first letter alone so the word stays recognisable
		int i = 1 + rnd.nextInt(word.length() - 1);
		StringBuilder sb = new StringBuilder(word);
		switch (mode) {
			case "Swap" -> {
				int j = i == word.length() - 1 ? i - 1 : i;
				if (j < 1 || sb.charAt(j) == sb.charAt(j + 1)) {
					sb.insert(i, sb.charAt(i)); // nothing to swap: double it instead
				} else {
					char c = sb.charAt(j);
					sb.setCharAt(j, sb.charAt(j + 1));
					sb.setCharAt(j + 1, c);
				}
			}
			case "Fat Finger" -> sb.setCharAt(i, neighbour(sb.charAt(i)));
			case "Double" -> sb.insert(i, sb.charAt(i));
			default -> sb.deleteCharAt(i);
		}
		return sb.toString();
	}

	/** A key next to {@code c} on the same row, keeping its case. */
	private static char neighbour(char c) {
		char lower = Character.toLowerCase(c);
		for (String row : ROWS) {
			int at = row.indexOf(lower);
			if (at < 0) {
				continue;
			}
			int next = at == 0 ? 1 : at == row.length() - 1 ? at - 1 : at + (ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
			char n = row.charAt(next);
			return Character.isUpperCase(c) ? Character.toUpperCase(n) : n;
		}
		return c;
	}

	@Override
	public void onTick() {
		if (pending == null || System.currentTimeMillis() < sendAt) {
			return;
		}
		correcting = true;
		try {
			ChatUtil.send(pending);
		} finally {
			correcting = false;
			pending = null;
		}
	}

	@Override
	public String getInfo() {
		return typos > 0 ? Integer.toString(typos) : kind.get();
	}
}
