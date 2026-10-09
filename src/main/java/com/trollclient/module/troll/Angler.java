package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.Delay;
import com.trollclient.util.InventoryUtil;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Casts a fishing rod at people and reels them in: a little yank towards you
 * every time. Zero damage, maximum annoyance.
 */
public class Angler extends Module {
	/**
	 * A cast leaves along the look direction at 0.6 + 0.5 / cos(pitch) b/t
	 * (FishingHook's constructor); every tick it falls 0.03, moves, then keeps 92%.
	 */
	private static final double GRAVITY = 0.03;
	private static final double DRAG = 0.92;
	/** Ticks to wait for the server to spawn our bobber before calling the cast a dud. */
	private static final int SPAWN_TIMEOUT = 12;

	private final NumberSetting range = add(new NumberSetting("Range", "Cast at players within this distance (rods don't go far)", 9, 3, 12, 0.5)
			.unit("m"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between casts", 900, 200, 5000, 50).unit("ms"));
	private final NumberSetting reelAfter = add(new NumberSetting("Reel After", "How long to keep someone on the hook before yanking", 6, 1, 40, 1)
			.unit("t"));
	private final NumberSetting giveUp = add(new NumberSetting("Give Up After", "Reel in a cast that hasn't hooked anyone after this long", 25, 8, 80, 1)
			.unit("t"));
	private final BoolSetting predict = add(new BoolSetting("Predict", "Lead moving targets", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot after each yank", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Friends don't get hooked", true));

	private enum State { IDLE, CAST, REELING }

	private final Delay timer = new Delay();
	private State state = State.IDLE;
	private InteractionHand hand;
	private int previousSlot = -1;
	private int lineTicks;
	private int hookedTicks;
	private Player aimingAt;
	private int casts;
	private int yanks;

	public Angler() {
		super("Angler", "Casts a fishing rod at nearby players and reels them in for a yank. Solves the cast's arc.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		state = State.IDLE;
		casts = 0;
		yanks = 0;
	}

	@Override
	protected void onDisable() {
		if (state == State.CAST && mc.player != null && mc.player.fishing != null && mc.gameMode != null) {
			use();
		}
		if (state != State.IDLE) {
			restoreSlot();
		}
		state = State.IDLE;
		aimingAt = null;
	}

	@Override
	public void onWorldLeave() {
		state = State.IDLE;
		previousSlot = -1;
		aimingAt = null;
	}

	@Override
	public void onTick() {
		FishingHook hook = mc.player.fishing;
		switch (state) {
			case CAST -> {
				lineTicks++;
				if (hook == null) {
					// never spawned, or the server took it away (we stopped holding the rod)
					if (lineTicks > SPAWN_TIMEOUT) {
						finish();
					}
					return;
				}
				if (hook.getHookedIn() instanceof Player) {
					if (++hookedTicks >= reelAfter.getInt()) {
						use();
						yanks++;
						state = State.REELING;
					}
				} else if (lineTicks >= giveUp.getInt() || hook.onGround()) {
					use();
					state = State.REELING;
				}
			}
			case REELING -> {
				if (hook == null || ++lineTicks > giveUp.getInt() + 40) {
					finish();
				}
			}
			default -> tryCast(hook);
		}
	}

	private void tryCast(FishingHook hook) {
		aimingAt = null;
		// someone's fishing by hand (or a stale line's still out): leave it alone
		if (hook != null || !timer.passed(delay.getInt()) || mc.player.isUsingItem() || mc.gui.screen() != null) {
			return;
		}
		int slot = -1;
		InteractionHand found;
		if (mc.player.getOffhandItem().is(Items.FISHING_ROD)) {
			found = InteractionHand.OFF_HAND;
		} else {
			slot = InventoryUtil.findHotbar(Items.FISHING_ROD);
			if (slot < 0) {
				return;
			}
			found = InteractionHand.MAIN_HAND;
		}
		List<Player> targets = Targets.playersWithin(range.get(), ignoreFriends.get());
		for (Player target : targets) {
			float[] aim = solve(target);
			if (aim == null) {
				continue;
			}
			aimingAt = target;
			// turn first; cast once the server has us aimed, since the bobber follows its idea of our look
			Rotations.request(aim[0], aim[1], 8, true);
			if (Rotations.isFacing(aim[0], aim[1], 1.5f)) {
				cast(found, slot);
			}
			return;
		}
	}

	private void cast(InteractionHand found, int slot) {
		previousSlot = InventoryUtil.selected();
		if (found == InteractionHand.MAIN_HAND) {
			InventoryUtil.select(slot);
		}
		hand = found;
		use();
		mc.player.swing(hand);
		casts++;
		state = State.CAST;
		lineTicks = 0;
		hookedTicks = 0;
	}

	/** Right-clicks the rod with the rotation the server already has, like a real click would. */
	private void use() {
		ItemStack held = mc.player.getItemInHand(hand);
		if (!held.is(Items.FISHING_ROD)) {
			return;
		}
		float realYaw = mc.player.getYRot();
		float realPitch = mc.player.getXRot();
		mc.player.setYRot(Rotations.serverYaw());
		mc.player.setXRot(Rotations.serverPitch());
		mc.gameMode.useItem(mc.player, hand);
		mc.player.setYRot(realYaw);
		mc.player.setXRot(realPitch);
	}

	private void finish() {
		restoreSlot();
		state = State.IDLE;
		timer.reset();
	}

	private void restoreSlot() {
		if (swapBack.get() && previousSlot >= 0 && hand == InteractionHand.MAIN_HAND && mc.player != null) {
			InventoryUtil.select(previousSlot);
		}
		previousSlot = -1;
	}

	/** Yaw/pitch that lands the bobber on the target's middle, or null if a cast can't reach them. */
	private float[] solve(Player target) {
		Vec3 from = mc.player.getEyePosition();
		Vec3 aim = target.position().add(0, target.getBbHeight() * 0.5, 0);
		Vec3 velocity = new Vec3(target.getX() - target.xo, 0, target.getZ() - target.zo);
		Float pitch = null;
		// solve, then re-solve against where they'll be once the bobber gets there
		for (int pass = 0; pass < 3; pass++) {
			double horizontal = Math.hypot(aim.x - from.x, aim.z - from.z);
			pitch = solvePitch(horizontal, aim.y - from.y);
			if (pitch == null) {
				return null;
			}
			if (!predict.get()) {
				break;
			}
			aim = target.position().add(0, target.getBbHeight() * 0.5, 0).add(velocity.scale(flightTicks(horizontal, pitch)));
		}
		return new float[]{Rotations.lookAt(from, aim)[0], pitch};
	}

	/** Binary search over the flat arc, 50 degrees up to 40 down; aiming higher lands higher. */
	private static Float solvePitch(double horizontal, double dy) {
		float lo = -50f;
		float hi = 40f;
		for (int i = 0; i < 24; i++) {
			float mid = (lo + hi) / 2f;
			double h = heightAt(horizontal, mid);
			if (Double.isNaN(h) || h < dy) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		float pitch = (lo + hi) / 2f;
		double h = heightAt(horizontal, pitch);
		return Double.isNaN(h) || Math.abs(h - dy) > 0.7 ? null : pitch;
	}

	/** Height relative to the eyes when the bobber has covered {@code horizontal} blocks, NaN if it never gets there. */
	private static double heightAt(double horizontal, float pitch) {
		double tan = Mth.clamp(Math.tan(Math.toRadians(pitch)), -5, 5);
		double speed = 0.6 / Math.sqrt(1 + tan * tan) + 0.5;
		double vx = speed;
		double vy = -tan * speed;
		double x = 0;
		double y = 0;
		for (int t = 0; t < 80; t++) {
			vy -= GRAVITY;
			double nx = x + vx;
			double ny = y + vy;
			if (nx >= horizontal) {
				return y + (ny - y) * (horizontal - x) / (nx - x);
			}
			x = nx;
			y = ny;
			vx *= DRAG;
			vy *= DRAG;
			if (y < -32) {
				break;
			}
		}
		return Double.NaN;
	}

	private static int flightTicks(double horizontal, float pitch) {
		double tan = Mth.clamp(Math.tan(Math.toRadians(pitch)), -5, 5);
		double vx = 0.6 / Math.sqrt(1 + tan * tan) + 0.5;
		double x = 0;
		for (int t = 1; t < 80; t++) {
			x += vx;
			vx *= DRAG;
			if (x >= horizontal) {
				return t;
			}
		}
		return 80;
	}

	@Override
	public Player getTarget() {
		if (state == State.CAST && mc.player != null && mc.player.fishing != null
				&& mc.player.fishing.getHookedIn() instanceof Player hooked) {
			return hooked;
		}
		return aimingAt;
	}

	@Override
	public String getInfo() {
		return yanks > 0 ? Integer.toString(yanks) : null;
	}
}
