package com.trollclient.module.chat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Friends;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Answers chat for you, with all the wisdom of a Magic 8-Ball. */
public class AutoReply extends Module {
	private static final String[] EIGHT_BALL = {
			"it is certain", "without a doubt", "yes, definitely", "you may rely on it", "as i see it, yes", "most likely",
			"outlook good", "yes", "signs point to yes", "reply hazy, try again", "ask again later", "better not tell you now",
			"cannot predict now", "concentrate and ask again", "don't count on it", "my reply is no", "my sources say no",
			"outlook not so good", "very doubtful"
	};

	private final ModeSetting trigger = add(new ModeSetting("Trigger", "Answer when someone says your name, asks a question, or both",
			"Both", "Mentions", "Questions", "Both"));
	private final ModeSetting answers = add(new ModeSetting("Answers", "Magic 8-Ball, your own lines, or the classic",
			"8-Ball", "8-Ball", "Custom", "No U"));
	private final TextSetting lines = add(new TextSetting("Lines", "Options split with |. {player} is who spoke",
			"who asked | source? | ok and? | that's crazy {player} | no comment | ratio | skill issue", 256))
			.visibleWhen(() -> answers.is("Custom"));
	private final BoolSetting address = add(new BoolSetting("Address Them", "Start the reply with their name", true));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Wait before answering, like you're typing", 1500, 0, 10000, 100)
			.unit("ms"));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Minimum gap between replies (servers kick spammers)", 5, 1, 60, 1)
			.unit("s"));
	private final NumberSetting chance = add(new NumberSetting("Chance", "Chance to answer any given message", 100, 5, 100, 5).unit("%"));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Let friends talk in peace", true));

	private String pending;
	private long sendAt;
	private long lastSent;
	private int replies;

	public AutoReply() {
		super("AutoReply", "Answers people who say your name or ask a question: Magic 8-Ball, your lines, or \"no u\".", Category.CHAT);
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
		boolean mention = mentionsMe(message);
		boolean question = message.trim().endsWith("?");
		boolean wanted = switch (trigger.get()) {
			case "Mentions" -> mention;
			case "Questions" -> question;
			default -> mention || question;
		};
		if (!wanted || ThreadLocalRandom.current().nextInt(100) >= chance.getInt()) {
			return;
		}
		String reply = switch (answers.get()) {
			case "Custom" -> ChatUtil.format(ChatUtil.pick(lines.get()), sender, 1);
			case "No U" -> "no u";
			default -> EIGHT_BALL[ThreadLocalRandom.current().nextInt(EIGHT_BALL.length)];
		};
		// one reply in flight at a time; anything newer replaces it
		pending = address.get() ? sender + ", " + reply : reply;
		sendAt = System.currentTimeMillis() + delay.getInt();
	}

	/** Our name as a whole word, so "steve" doesn't trigger on "steven". */
	private boolean mentionsMe(String message) {
		String me = mc.player == null ? "" : mc.player.getGameProfile().name();
		if (me.isEmpty()) {
			return false;
		}
		return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(me.toLowerCase(Locale.ROOT)) + "(?![A-Za-z0-9_])")
				.matcher(message.toLowerCase(Locale.ROOT)).find();
	}

	@Override
	public void onTick() {
		if (pending == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < sendAt || now - lastSent < cooldown.get() * 1000) {
			return;
		}
		lastSent = now;
		replies++;
		ChatUtil.send(pending);
		pending = null;
	}

	@Override
	public String getInfo() {
		return replies > 0 ? Integer.toString(replies) : answers.get();
	}
}
