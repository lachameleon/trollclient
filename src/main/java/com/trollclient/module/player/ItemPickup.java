package com.trollclient.module.player;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.pathing.PathFinder;
import com.trollclient.pathing.PathFollower;
import com.trollclient.pathing.Walkability;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ItemPickup extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "Chase items up to this far away", 16, 4, 48, 1)
			.unit("m"));
	private final BoolSetting onlyPlayerDrops = add(new BoolSetting("Only Player Drops", "Ignore items that weren't dropped by a player", true));
	private final BoolSetting ignoreOwn = add(new BoolSetting("Ignore Own", "Don't chase items you dropped yourself", true));
	private final NumberSetting maxAge = add(new NumberSetting("Forget After", "Stop chasing an item after this long", 30, 5, 300, 5)
			.unit("s"));
	private final BoolSetting sprint = add(new BoolSetting("Sprint", "Sprint to the item", true));
	private final BoolSetting faceMovement = add(new BoolSetting("Face Movement", "Turn your camera along the path", true));

	/** Where every other player has been recently, to attribute drops to them. */
	private record Seen(Vec3 feet, Vec3 eyes, long time, boolean self) {
	}

	private final Deque<Seen> history = new ArrayDeque<>();
	private final Map<Integer, Long> tracked = new HashMap<>();
	private final PathFollower follower = new PathFollower();
	private int targetId = -1;
	private int ticksSincePath;

	public ItemPickup() {
		super("ItemPickup", "Instantly runs over and grabs any item another player drops.", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		tracked.clear();
		history.clear();
		follower.clear();
		targetId = -1;
	}

	@Override
	public void onWorldLeave() {
		onEnable();
	}

	@Override
	public void onEntityAdded(Entity entity) {
		if (!(entity instanceof ItemEntity item) || mc.player == null) {
			return;
		}
		if (item.distanceTo(mc.player) > range.get() * 1.5) {
			return;
		}
		Seen dropper = findDropper(item.position());
		if (dropper == null && onlyPlayerDrops.get()) {
			return;
		}
		if (dropper != null && dropper.self() && ignoreOwn.get()) {
			return;
		}
		tracked.put(item.getId(), System.currentTimeMillis());
	}

	@Override
	public void onTick() {
		recordPlayers();
		long now = System.currentTimeMillis();
		long ttl = maxAge.getInt() * 1000L;

		ItemEntity best = null;
		double bestDist = Double.MAX_VALUE;
		Iterator<Map.Entry<Integer, Long>> it = tracked.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Long> entry = it.next();
			Entity e = mc.level.getEntity(entry.getKey());
			if (!(e instanceof ItemEntity item) || !item.isAlive() || now - entry.getValue() > ttl) {
				it.remove();
				continue;
			}
			double d = item.distanceTo(mc.player);
			if (d <= range.get() && d < bestDist) {
				bestDist = d;
				best = item;
			}
		}
		if (best == null) {
			targetId = -1;
			follower.clear();
			return;
		}

		Vec3 itemPos = best.position();
		if (bestDist < 1.6) {
			// close enough to just walk into it
			Vec3 d = itemPos.subtract(mc.player.position());
			MovementControl.move(MovementControl.PRIORITY_PICKUP, d.x, d.z, false);
			if (d.y > 0.6 && mc.player.onGround()) {
				MovementControl.jump();
			}
			return;
		}

		ticksSincePath++;
		boolean retarget = best.getId() != targetId;
		if (retarget || follower.isDone() || follower.isStuck() || ticksSincePath > 15) {
			targetId = best.getId();
			ticksSincePath = 0;
			BlockPos start = mc.player.blockPosition();
			if (!Walkability.isStandable(mc.level, start)) {
				int y = Walkability.findStandableY(mc.level, start.getX(), start.getY(), start.getZ(), 1, 3);
				if (y != Integer.MIN_VALUE) {
					start = new BlockPos(start.getX(), y, start.getZ());
				}
			}
			List<BlockPos> path = PathFinder.find(mc.level, start,
					pos -> Vec3.atBottomCenterOf(pos).distanceTo(itemPos) < 1.2,
					pos -> Vec3.atBottomCenterOf(pos).distanceTo(itemPos),
					null, PathFinder.Options.defaults());
			follower.set(path);
		}
		if (!follower.tick(MovementControl.PRIORITY_PICKUP, sprint.get(), faceMovement.get())) {
			Vec3 d = itemPos.subtract(mc.player.position());
			MovementControl.move(MovementControl.PRIORITY_PICKUP, d.x, d.z, sprint.get());
			if (faceMovement.get()) {
				Rotations.faceClient(mc.player, (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f, 25f);
			}
		}
	}

	private void recordPlayers() {
		long now = System.currentTimeMillis();
		while (!history.isEmpty() && now - history.peekFirst().time() > 2500) {
			history.pollFirst();
		}
		for (Player p : mc.level.players()) {
			if (!p.isSpectator()) {
				history.addLast(new Seen(p.position(), p.getEyePosition(), now, p == mc.player));
			}
		}
	}

	/** Thrown items spawn just below the thrower's eyes; death drops spawn at their feet. */
	private Seen findDropper(Vec3 itemPos) {
		Seen best = null;
		double bestDist = 2.6;
		for (Seen s : history) {
			double d = Math.min(s.eyes().distanceTo(itemPos), s.feet().distanceTo(itemPos));
			if (d < bestDist) {
				bestDist = d;
				best = s;
			}
		}
		// live positions too, since this tick's spawn can beat our history update
		for (Player p : mc.level.players()) {
			double d = Math.min(p.getEyePosition().distanceTo(itemPos), p.position().distanceTo(itemPos));
			if (d < bestDist) {
				bestDist = d;
				best = new Seen(p.position(), p.getEyePosition(), System.currentTimeMillis(), p == mc.player);
			}
		}
		return best;
	}

	@Override
	public String getInfo() {
		return tracked.isEmpty() ? null : Integer.toString(tracked.size());
	}
}
