package com.trollclient.module.player;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.InventoryUtil;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Little idle twitches while you're away from the keyboard: glances around,
 * taps crouch, flicks through the hotbar. You look alive and never leave the spot.
 */
public class Fidget extends Module {
	private final NumberSetting idleAfter = add(new NumberSetting("Idle After", "Start fidgeting once you haven't touched anything for this long", 20, 3, 300, 1)
			.unit("s"));
	private final NumberSetting every = add(new NumberSetting("Every", "Average time between fidgets", 4, 1, 30, 0.5).unit("s"));
	private final BoolSetting look = add(new BoolSetting("Look Around", "Glance in random directions (your camera stays put)", true));
	private final BoolSetting crouch = add(new BoolSetting("Crouch", "Tap crouch once or twice", true));
	private final BoolSetting scroll = add(new BoolSetting("Scroll", "Flick to the next hotbar slot and back", true));
	private final BoolSetting swing = add(new BoolSetting("Swing", "Swing your arm at nothing", true));
	private final BoolSetting jump = add(new BoolSetting("Hop", "The occasional little jump", false));

	private enum Action { LOOK, CROUCH, SCROLL, SWING, HOP }

	private int idleTicks;
	private float lastYaw;
	private float lastPitch;
	private int cooldown;
	private Action action;
	private int actionTicks;
	private float lookYaw;
	private float lookPitch;
	private int scrolledFrom = -1;
	private int fidgets;

	public Fidget() {
		super("Fidget", "Idle twitches while you're AFK (glances, crouch taps, hotbar flicks) so you never look away from keyboard.",
				Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		idleTicks = 0;
		action = null;
		fidgets = 0;
		if (mc.player != null) {
			lastYaw = mc.player.getYRot();
			lastPitch = mc.player.getXRot();
		}
	}

	@Override
	protected void onDisable() {
		stop();
	}

	@Override
	public void onWorldLeave() {
		action = null;
		scrolledFrom = -1;
		idleTicks = 0;
	}

	@Override
	public void onTick() {
		if (touched()) {
			idleTicks = 0;
			stop();
			return;
		}
		if (++idleTicks < idleAfter.get() * 20) {
			return;
		}
		if (action != null) {
			tickAction();
			return;
		}
		if (--cooldown > 0) {
			return;
		}
		start();
	}

	/** Any key down, or the camera moved since last tick. Our own glances are silent, so they don't count. */
	private boolean touched() {
		float yaw = mc.player.getYRot();
		float pitch = mc.player.getXRot();
		boolean moved = Math.abs(yaw - lastYaw) > 0.01f || Math.abs(pitch - lastPitch) > 0.01f;
		lastYaw = yaw;
		lastPitch = pitch;
		if (moved || mc.gui.screen() != null) {
			return true;
		}
		KeyMapping[] keys = {mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump,
				mc.options.keyShift, mc.options.keyAttack, mc.options.keyUse, mc.options.keySprint};
		for (KeyMapping k : keys) {
			if (k.isDown()) {
				return true;
			}
		}
		return false;
	}

	private void start() {
		List<Action> options = new ArrayList<>();
		if (look.get()) {
			options.add(Action.LOOK);
			options.add(Action.LOOK);
		}
		if (crouch.get()) {
			options.add(Action.CROUCH);
		}
		if (scroll.get()) {
			options.add(Action.SCROLL);
		}
		if (swing.get()) {
			options.add(Action.SWING);
		}
		if (jump.get()) {
			options.add(Action.HOP);
		}
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		long avg = Math.round(every.get() * 20);
		cooldown = (int) (avg / 2 + rnd.nextLong(Math.max(1, avg)));
		if (options.isEmpty()) {
			return;
		}
		action = options.get(rnd.nextInt(options.size()));
		actionTicks = 0;
		fidgets++;
		switch (action) {
			case LOOK -> {
				lookYaw = Mth.wrapDegrees(mc.player.getYRot() + rnd.nextFloat(-50f, 50f));
				lookPitch = Mth.clamp(mc.player.getXRot() + rnd.nextFloat(-20f, 20f), -60f, 60f);
			}
			case SCROLL -> {
				scrolledFrom = InventoryUtil.selected();
				InventoryUtil.select((scrolledFrom + (rnd.nextBoolean() ? 1 : 8)) % 9);
				// tell the server now, so other players actually see the item change
				InventoryUtil.syncSelected();
			}
			case SWING -> mc.player.swing(InteractionHand.MAIN_HAND);
			case HOP -> MovementControl.jump();
			default -> {
			}
		}
	}

	private void tickAction() {
		actionTicks++;
		switch (action) {
			case LOOK -> {
				Rotations.request(lookYaw, lookPitch, -1, true);
				if (actionTicks >= 25) {
					action = null;
				}
			}
			case CROUCH -> {
				// tap, release, tap: a double crouch
				MovementControl.sneak(MovementControl.PRIORITY_FIDGET, actionTicks <= 3 || (actionTicks > 6 && actionTicks <= 9));
				if (actionTicks > 9) {
					action = null;
				}
			}
			case SCROLL -> {
				if (actionTicks >= 8) {
					restoreSlot();
					action = null;
				}
			}
			default -> action = null;
		}
	}

	private void stop() {
		restoreSlot();
		action = null;
	}

	private void restoreSlot() {
		if (scrolledFrom >= 0 && mc.player != null) {
			InventoryUtil.select(scrolledFrom);
			InventoryUtil.syncSelected();
		}
		scrolledFrom = -1;
	}

	@Override
	public String getInfo() {
		return idleTicks >= idleAfter.get() * 20 ? "afk" : null;
	}
}
