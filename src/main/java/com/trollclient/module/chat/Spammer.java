package com.trollclient.module.chat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Targets;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** The classic: say the same handful of things forever. */
public class Spammer extends Module {
	private final TextSetting messages = add(new TextSetting("Messages", "Lines to send, split with |. {player} = a random online player",
			"troll client on top | {player} is looking kinda sus | have you tried turning it off and on again", 256));
	private final ModeSetting order = add(new ModeSetting("Order", "Which line comes next", "Random", "Random", "In Order"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between messages (servers kick fast spammers)", 8, 2, 120, 1)
			.unit("s"));
	private final BoolSetting antiRepeat = add(new BoolSetting("Anti Repeat", "Add a few random characters so duplicate filters let it through", true));
	private final NumberSetting limit = add(new NumberSetting("Stop After", "Turn off after this many messages (0 = never)", 0, 0, 100, 1));

	private long last;
	private int index;
	private int sent;

	public Spammer() {
		super("Spammer", "Sends lines from a list on a timer. With an anti-repeat suffix so filters don't catch it.", Category.CHAT);
	}

	@Override
	protected void onEnable() {
		// first message right away feels broken; wait one full delay
		last = System.currentTimeMillis();
		sent = 0;
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		if (now - last < delay.get() * 1000) {
			return;
		}
		last = now;
		String[] lines = messages.get().split("\\|");
		if (lines.length == 0 || messages.get().isBlank()) {
			return;
		}
		String line = order.is("In Order") ? lines[index++ % lines.length] : lines[ThreadLocalRandom.current().nextInt(lines.length)];
		line = line.trim();
		if (line.contains("{player}")) {
			List<String> names = Targets.onlineNames();
			line = line.replace("{player}", names.isEmpty() ? "nobody" : names.get(ThreadLocalRandom.current().nextInt(names.size())));
		}
		if (antiRepeat.get() && !line.startsWith("/")) {
			line += " " + randomTag();
		}
		ChatUtil.send(line);
		sent++;
		if (limit.getInt() > 0 && sent >= limit.getInt()) {
			setEnabled(false);
		}
	}

	private static String randomTag() {
		String pool = "abcdefghijklmnopqrstuvwxyz0123456789";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < 4; i++) {
			sb.append(pool.charAt(ThreadLocalRandom.current().nextInt(pool.length())));
		}
		return sb.append(']').toString();
	}

	@Override
	public String getInfo() {
		return sent > 0 ? Integer.toString(sent) : null;
	}
}
