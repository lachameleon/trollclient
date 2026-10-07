package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

import java.util.concurrent.ThreadLocalRandom;

public class Twerk extends Module {
	private final ModeSetting mode = add(new ModeSetting("Mode", "How your body flails around", "Twerk",
			"Twerk", "Spin", "Headbang", "Jitter", "Worm", "Chaos"));
	private final BoolSetting sneak = add(new BoolSetting("Shift Spam", "Rapidly crouch and stand", true));
	private final NumberSetting sneakSpeed = add(new NumberSetting("Shift Interval", "Ticks between crouch toggles", 2, 1, 10, 1)
			.unit("t")).visibleWhen(sneak::get);
	private final NumberSetting spinSpeed = add(new NumberSetting("Speed", "Degrees per tick for turning motions", 35, 5, 90, 1)
			.unit("°"));
	private final BoolSetting swing = add(new BoolSetting("Flail Arms", "Swing your hands constantly", false));
	private final BoolSetting sway = add(new BoolSetting("Sway", "Shuffle left and right on the spot", false));
	private final BoolSetting visible = add(new BoolSetting("Show In F5", "Also turn your body for yourself in third person", true));

	private int ticks;
	private float yaw;
	private float pitch;
	private String chaosMode = "Spin";

	public Twerk() {
		super("Twerk", "Moves your body in weird ways constantly: turning, shifting, flailing.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		ticks = 0;
		if (mc.player != null) {
			yaw = mc.player.getYRot();
			pitch = 0;
		}
	}

	@Override
	public void onTick() {
		ticks++;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		float speed = spinSpeed.getFloat();
		float base = mc.player.getYRot();

		String active = mode.get();
		if (active.equals("Chaos")) {
			if (ticks % 40 == 0) {
				String[] pool = {"Twerk", "Spin", "Headbang", "Jitter", "Worm"};
				chaosMode = pool[rnd.nextInt(pool.length)];
			}
			active = chaosMode;
		}

		switch (active) {
			case "Twerk" -> {
				// face away, bob the hips: small yaw wiggle with the head down
				yaw = base + 180f + Mth.sin(ticks * speed * 0.02f) * 35f;
				pitch = 35f + Mth.sin(ticks * 0.9f) * 25f;
			}
			case "Spin" -> {
				yaw += speed;
				pitch = Mth.sin(ticks * 0.15f) * 20f;
			}
			case "Headbang" -> {
				yaw = base;
				pitch = (ticks % 4 < 2) ? 90f : -60f;
			}
			case "Jitter" -> {
				yaw = base + rnd.nextFloat(-180f, 180f);
				pitch = rnd.nextFloat(-90f, 90f);
			}
			case "Worm" -> {
				yaw = base + Mth.sin(ticks * speed * 0.01f) * 90f;
				pitch = Mth.cos(ticks * speed * 0.01f) * 70f;
			}
			default -> {
			}
		}
		Rotations.request(Mth.wrapDegrees(yaw), pitch, 0, visible.get());

		if (sneak.get()) {
			int interval = Math.max(1, sneakSpeed.getInt());
			MovementControl.sneak(MovementControl.PRIORITY_TWERK, (ticks / interval) % 2 == 0);
		}
		if (swing.get() && ticks % 2 == 0) {
			mc.player.swing(ticks % 4 == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
		}
		if (sway.get() && !MovementControl.isMoveRequested()) {
			float rad = mc.player.getYRot() * Mth.DEG_TO_RAD;
			double side = (ticks / 3) % 2 == 0 ? 1 : -1;
			MovementControl.move(MovementControl.PRIORITY_TWERK, Math.cos(rad) * side, Math.sin(rad) * side, false);
		}
	}

	@Override
	public String getInfo() {
		return mode.get();
	}
}
