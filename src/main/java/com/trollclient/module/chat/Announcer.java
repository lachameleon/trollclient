package com.trollclient.module.chat;

import com.trollclient.command.Commands;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** "I just walked 64 blocks thanks to Troll Client!" Every hack client needs one. */
public class Announcer extends Module {
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between announcements", 15, 5, 120, 1).unit("s"));
	private final ModeSetting audience = add(new ModeSetting("Audience", "Brag to everyone, or just to yourself", "Public",
			"Public", "Client"));
	private final BoolSetting mined = add(new BoolSetting("Mined", "Announce blocks you break", true));
	private final BoolSetting placed = add(new BoolSetting("Placed", "Announce blocks you place", true));
	private final BoolSetting walked = add(new BoolSetting("Walked", "Announce distance travelled", true));
	private final BoolSetting jumped = add(new BoolSetting("Jumped", "Announce how often you jumped", true));
	private final NumberSetting minimum = add(new NumberSetting("Minimum", "Don't bother announcing less than this", 5, 1, 100, 1));
	private final TextSetting suffix = add(new TextSetting("Suffix", "Tacked onto every announcement", " thanks to Troll Client!", 48)
			.placeholder("none"));

	private final Map<Block, Integer> minedBlocks = new HashMap<>();
	private final Map<Block, Integer> placedBlocks = new HashMap<>();
	private double distance;
	private int jumps;
	private Vec3 lastPos;
	private boolean wasOnGround = true;
	private long lastAnnounce;
	private int announcements;

	public Announcer() {
		super("Announcer", "Tells chat what you've been up to: blocks mined and placed, distance walked, jumps.", Category.CHAT);
	}

	@Override
	protected void onEnable() {
		minedBlocks.clear();
		placedBlocks.clear();
		distance = 0;
		jumps = 0;
		lastPos = null;
		lastAnnounce = System.currentTimeMillis();
	}

	@Override
	public void onWorldLeave() {
		lastPos = null;
	}

	public void blockBroken(BlockState state) {
		minedBlocks.merge(state.getBlock(), 1, Integer::sum);
	}

	public void blockPlaced(Block block) {
		placedBlocks.merge(block, 1, Integer::sum);
	}

	@Override
	public void onTick() {
		Vec3 pos = mc.player.position();
		if (lastPos != null) {
			double step = Math.hypot(pos.x - lastPos.x, pos.z - lastPos.z);
			if (step < 2) {
				distance += step; // teleports don't count
			}
		}
		lastPos = pos;
		boolean onGround = mc.player.onGround();
		if (wasOnGround && !onGround && mc.player.getDeltaMovement().y > 0.2) {
			jumps++;
		}
		wasOnGround = onGround;

		long now = System.currentTimeMillis();
		if (now - lastAnnounce < delay.get() * 1000) {
			return;
		}
		String line = pickLine();
		if (line == null) {
			return;
		}
		lastAnnounce = now;
		announcements++;
		line += suffix.get();
		if (audience.is("Client")) {
			Commands.info(line);
		} else {
			ChatUtil.send(line);
		}
	}

	/** Announces whichever stat grew the most since last time, relative to its threshold. */
	private String pickLine() {
		int min = minimum.getInt();
		String best = null;
		double bestScore = 0;
		Map.Entry<Block, Integer> topMined = top(minedBlocks);
		if (mined.get() && topMined != null && topMined.getValue() >= min) {
			double score = topMined.getValue() / (double) min;
			if (score > bestScore) {
				bestScore = score;
				best = "I just mined " + topMined.getValue() + " " + name(topMined.getKey());
			}
		}
		Map.Entry<Block, Integer> topPlaced = top(placedBlocks);
		if (placed.get() && topPlaced != null && topPlaced.getValue() >= min) {
			double score = topPlaced.getValue() / (double) min;
			if (score > bestScore) {
				bestScore = score;
				best = "I just placed " + topPlaced.getValue() + " " + name(topPlaced.getKey());
			}
		}
		if (walked.get() && distance >= min * 4) {
			double score = distance / (min * 4);
			if (score > bestScore) {
				bestScore = score;
				best = "I just walked " + (int) distance + " blocks";
			}
		}
		if (jumped.get() && jumps >= min) {
			double score = jumps / (double) min;
			if (score > bestScore) {
				best = "I just jumped " + jumps + " times";
			}
		}
		if (best == null) {
			return null;
		}
		// start counting again for the next brag
		if (best.startsWith("I just mined")) {
			minedBlocks.clear();
		} else if (best.startsWith("I just placed")) {
			placedBlocks.clear();
		} else if (best.startsWith("I just walked")) {
			distance = 0;
		} else {
			jumps = 0;
		}
		return best;
	}

	private static Map.Entry<Block, Integer> top(Map<Block, Integer> map) {
		Map.Entry<Block, Integer> best = null;
		for (Map.Entry<Block, Integer> e : map.entrySet()) {
			if (best == null || e.getValue() > best.getValue()) {
				best = e;
			}
		}
		return best;
	}

	private static String name(Block block) {
		return block.getName().getString().toLowerCase(Locale.ROOT);
	}

	@Override
	public String getInfo() {
		return announcements > 0 ? Integer.toString(announcements) : null;
	}
}
