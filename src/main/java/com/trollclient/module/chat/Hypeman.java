package com.trollclient.module.chat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Friends;

import java.util.concurrent.ThreadLocalRandom;

/** Reacts to everything anyone says: hypes them up, hates on them, agrees with all of it, or just doesn't get it. */
public class Hypeman extends Module {
	private final ModeSetting mode = add(new ModeSetting("Mode", "What kind of reaction everyone gets", "Hype",
			"Hype", "Hater", "Yes Man", "Confused", "Villager", "Custom"));
	private final TextSetting lines = add(new TextSetting("Lines", "Options split with |. {player} is who spoke",
			"based | {player} said what we were all thinking | and that's on that", 256)).visibleWhen(() -> mode.is("Custom"));
	private final NumberSetting chance = add(new NumberSetting("Chance", "Chance to react to any given message", 50, 5, 100, 5).unit("%"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Wait before reacting, like you're typing", 1200, 0, 8000, 100)
			.unit("ms"));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Minimum gap between reactions (servers kick spammers)", 5, 1, 60, 1)
			.unit("s"));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't react to friends", false));

	private String pending;
	private long sendAt;
	private long lastSent;
	private int reactions;

	public Hypeman() {
		super("Hypeman", "Reacts to every chat message: hypes people up, hates on them, agrees with everything or stays confused.",
				Category.CHAT);
	}

	@Override
	protected void onEnable() {
		pending = null;
	}

	@Override
	public void onWorldLeave() {
		pending = null;
	}

	@Override
	public void onChatMessage(String sender, String message) {
		if (ignoreFriends.get() && Friends.isFriend(sender)) {
			return;
		}
		// don't queue more reactions while still cooling down from the last one
		long now = System.currentTimeMillis();
		if (pending != null || now - lastSent < cooldown.get() * 1000
				|| ThreadLocalRandom.current().nextInt(100) >= chance.getInt()) {
			return;
		}
		pending = ChatUtil.format(ChatUtil.pick(options()), sender, 1);
		sendAt = now + delay.getInt();
	}

	private String options() {
		return switch (mode.get()) {
			case "Hater" -> "L | ratio | who asked {player} | nobody cares {player} | mid | cope | {player} fell off | skill issue | "
					+ "L + ratio | didn't ask";
			case "Yes Man" -> "agreed | exactly | couldn't have said it better {player} | yes | 100% | this | +1 | so true | "
					+ "{player} gets it | what {player} said";
			case "Confused" -> "what? | huh | wdym {player} | can you say that again | i don't get it | ? | wait what | "
					+ "{player} what does that mean | i'm lost";
			case "Villager" -> "hrmm | hmm | huh | hrm? | hmph | hrrm | *nods*";
			case "Custom" -> lines.get();
			default -> "W {player} | {player} spitting facts | real | so true {player} | LETS GOOO | {player} is him | facts | "
					+ "big W | massive | {player} never misses";
		};
	}

	@Override
	public void onTick() {
		if (pending == null || System.currentTimeMillis() < sendAt) {
			return;
		}
		lastSent = System.currentTimeMillis();
		reactions++;
		ChatUtil.send(pending);
		pending = null;
	}

	@Override
	public String getInfo() {
		return reactions > 0 ? Integer.toString(reactions) : mode.get();
	}
}
