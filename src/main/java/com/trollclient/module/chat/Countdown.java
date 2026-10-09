package com.trollclient.module.chat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;

/** Counts down in chat towards something big. Then nothing happens. Switches itself off afterwards. */
public class Countdown extends Module {
	private static final String[] WORDS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
			"eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"};

	private final NumberSetting from = add(new NumberSetting("From", "Number to count down from", 5, 3, 20, 1));
	private final NumberSetting interval = add(new NumberSetting("Interval", "Time between numbers (servers kick fast talkers)", 2, 1, 10, 0.5)
			.unit("s"));
	private final ModeSetting style = add(new ModeSetting("Style", "How the numbers are said", "Dramatic",
			"Numbers", "Words", "Dramatic"));
	private final TextSetting intro = add(new TextSetting("Intro", "Said before the count starts. Options split with |",
			"self destruct sequence initiated | launch sequence started | something big is about to happen | everyone stay calm", 256));
	private final TextSetting finale = add(new TextSetting("Finale", "Said when it hits zero. Options split with |",
			"jk | ... | nothing happened | ok i forgot what was supposed to happen | boom (not really) | happy new year", 256));

	private int count;
	private boolean introDone;
	private long last;

	public Countdown() {
		super("Countdown", "Dramatically counts down in chat to... nothing. Turns itself off when it's done.", Category.CHAT);
	}

	@Override
	protected void onEnable() {
		count = from.getInt();
		introDone = intro.get().isBlank();
		// start straight away rather than one interval later
		last = 0;
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		if (now - last < interval.get() * 1000) {
			return;
		}
		last = now;
		if (!introDone) {
			introDone = true;
			ChatUtil.send(ChatUtil.pick(intro.get()));
			return;
		}
		if (count > 0) {
			ChatUtil.send(say(count));
			count--;
			return;
		}
		if (!finale.get().isBlank()) {
			ChatUtil.send(ChatUtil.pick(finale.get()));
		}
		setEnabled(false);
	}

	private String say(int n) {
		return switch (style.get()) {
			case "Words" -> (n < WORDS.length ? WORDS[n] : Integer.toString(n)) + "...";
			case "Dramatic" -> n == 1 ? "1..." : "T-minus " + n;
			default -> n + "...";
		};
	}

	@Override
	public String getInfo() {
		return isEnabled() && introDone ? Integer.toString(count) : null;
	}
}
