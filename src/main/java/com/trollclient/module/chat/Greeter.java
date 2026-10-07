package com.trollclient.module.chat;

import com.trollclient.command.Commands;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The overly friendly one: welcomes everyone who joins, waves at everyone who walks up, waves everyone off. */
public class Greeter extends Module {
	/** Don't greet the same walk-up twice in this long. */
	private static final long REGREET_MS = 120_000;
	private static final int BASELINE_TICKS = 100;

	private final BoolSetting joins = add(new BoolSetting("Joins", "Welcome players who join the server", true));
	private final TextSetting joinLines = add(new TextSetting("Join Lines", "Options split with |. {player} is who joined",
			"welcome {player}! | hi {player} :) | {player} has entered the troll zone | oh no, it's {player}", 256)).visibleWhen(joins::get);
	private final BoolSetting leaves = add(new BoolSetting("Leaves", "Say bye when players leave", true));
	private final TextSetting leaveLines = add(new TextSetting("Leave Lines", "Options split with |. {player} is who left",
			"bye {player} | {player} couldn't handle it | cya {player} | and {player} is gone", 256)).visibleWhen(leaves::get);
	private final BoolSetting walkUps = add(new BoolSetting("Walk Ups", "Greet players who come close", true));
	private final NumberSetting range = add(new NumberSetting("Range", "How close counts as walking up", 6, 2, 32, 1).unit("m"))
			.visibleWhen(walkUps::get);
	private final TextSetting walkUpLines = add(new TextSetting("Walk Up Lines", "Options split with |. {player} is who came over",
			"hey {player} | oh hi {player} | {player}! fancy seeing you here | sup {player}", 256)).visibleWhen(walkUps::get);
	private final BoolSetting wave = add(new BoolSetting("Wave", "Turn and wave at players who walk up", true)).visibleWhen(walkUps::get);
	private final ModeSetting audience = add(new ModeSetting("Audience", "Say it in chat, or just show it to you", "Public",
			"Public", "Client"));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Minimum gap between greetings (servers kick spammers)", 4, 1, 60, 1)
			.unit("s"));

	private final Deque<String> queue = new ArrayDeque<>();
	/** Tab list names last time we looked; null until we've seen the server's list once. */
	private Set<String> online;
	private final Set<String> near = new HashSet<>();
	private final Map<String, Long> greetedAt = new HashMap<>();
	private Player waveAt;
	private int waveTicks;
	private long lastSent;
	private int ticks;
	private int greetings;

	public Greeter() {
		super("Greeter", "Welcomes players who join, says hi (and waves) when they walk up, and bye when they leave.", Category.CHAT);
	}

	@Override
	protected void onEnable() {
		onWorldLeave();
	}

	@Override
	public void onWorldLeave() {
		queue.clear();
		online = null;
		near.clear();
		waveTicks = 0;
		ticks = 0;
	}

	@Override
	public void onTick() {
		if (++ticks % 10 == 0) {
			checkTabList();
		}
		if (walkUps.get()) {
			checkWalkUps();
		}
		if (waveTicks > 0) {
			waveTicks--;
			if (waveAt != null && waveAt.isAlive()) {
				float[] look = Rotations.lookAt(waveAt.getEyePosition());
				Rotations.request(look[0], look[1], 4, true);
			}
			if (waveTicks % 5 == 0) {
				mc.player.swing(InteractionHand.MAIN_HAND);
			}
		}
		long now = System.currentTimeMillis();
		if (!queue.isEmpty() && now - lastSent >= cooldown.get() * 1000) {
			lastSent = now;
			greetings++;
			String line = queue.poll();
			if (audience.is("Client")) {
				Commands.info(line);
			} else {
				ChatUtil.send(line);
			}
		}
	}

	/**
	 * Joins and leaves, from changes to the tab list. The first few seconds in a
	 * world only build the baseline: some servers fill the tab list in bit by bit
	 * after you log in, and none of those people just joined.
	 */
	private void checkTabList() {
		Set<String> current = new HashSet<>(Targets.onlineNames());
		if (online != null && ticks > BASELINE_TICKS) {
			for (String name : current) {
				if (joins.get() && !online.contains(name)) {
					say(joinLines.get(), name);
				}
			}
			for (String name : online) {
				if (leaves.get() && !current.contains(name)) {
					say(leaveLines.get(), name);
				}
			}
		}
		online = current;
	}

	private void checkWalkUps() {
		Set<String> now = new HashSet<>();
		long time = System.currentTimeMillis();
		for (Player p : Targets.playersWithin(range.get(), false)) {
			String name = Targets.name(p).toLowerCase(Locale.ROOT);
			now.add(name);
			if (near.contains(name) || time - greetedAt.getOrDefault(name, 0L) < REGREET_MS) {
				continue;
			}
			greetedAt.put(name, time);
			say(walkUpLines.get(), Targets.name(p));
			if (wave.get()) {
				waveAt = p;
				waveTicks = 20;
			}
		}
		near.clear();
		near.addAll(now);
	}

	private void say(String options, String name) {
		// a burst of joins (a server restart, say) shouldn't turn into a minute of greetings
		if (queue.size() < 3) {
			queue.add(ChatUtil.format(ChatUtil.pick(options), name, 1));
		}
	}

	@Override
	public Player getTarget() {
		return waveTicks > 0 ? waveAt : null;
	}

	@Override
	public String getInfo() {
		return greetings > 0 ? Integer.toString(greetings) : null;
	}
}
