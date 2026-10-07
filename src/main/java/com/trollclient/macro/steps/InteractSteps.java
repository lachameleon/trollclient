package com.trollclient.macro.steps;

import com.trollclient.macro.MacroRun;
import com.trollclient.macro.MacroStep;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Locale;

/** Using, hitting and looking. */
public final class InteractSteps {
	private InteractSteps() {
	}

	private static ModeSetting hand() {
		return new ModeSetting("Hand", "Which hand", "Main", "Main", "Off");
	}

	private static InteractionHand of(ModeSetting hand) {
		return hand.is("Off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
	}

	public static final class UseItem extends MacroStep {
		private final ModeSetting hand = add(hand());
		private final NumberSetting hold = add(new NumberSetting("Hold",
				"Keep using for this many ticks (eat, draw a bow...). 0 = a single click", 0, 0, 200, 1).unit("t"));
		private boolean holding;

		@Override
		public void start(MacroRun run) {
			holding = false;
		}

		@Override
		public Result tick(MacroRun run) {
			if (run.stepTicks() == 0) {
				if (mc.gameMode.useItem(mc.player, of(hand)).consumesAction()) {
					mc.player.swing(of(hand));
				}
				if (hold.getInt() == 0) {
					return Result.NEXT;
				}
				holding = true;
			}
			if (run.stepTicks() < hold.getInt()) {
				// vanilla lets go of an item the moment the use key isn't held
				mc.options.keyUse.setDown(true);
				return Result.WAIT;
			}
			release();
			return Result.NEXT;
		}

		@Override
		public void abort() {
			release();
		}

		private void release() {
			if (!holding) {
				return;
			}
			holding = false;
			mc.options.keyUse.setDown(false);
			if (mc.player != null && mc.player.isUsingItem() && mc.gameMode != null) {
				mc.gameMode.releaseUsingItem(mc.player);
			}
		}

		@Override
		public String summary() {
			return hand.get().toLowerCase(Locale.ROOT) + (hold.getInt() > 0 ? " " + hold.display() : "");
		}
	}

	public static final class UseBlock extends MacroStep {
		private final ModeSetting hand = add(hand());

		@Override
		public Result tick(MacroRun run) {
			if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
					&& mc.gameMode.useItemOn(mc.player, of(hand), hit).consumesAction()) {
				mc.player.swing(of(hand));
			}
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "under the crosshair";
		}
	}

	public static final class Attack extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			if (mc.hitResult instanceof EntityHitResult hit) {
				mc.gameMode.attack(mc.player, hit.getEntity());
			}
			mc.player.swing(InteractionHand.MAIN_HAND);
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "under the crosshair";
		}
	}

	public static final class Swing extends MacroStep {
		private final ModeSetting hand = add(hand());

		@Override
		public Result tick(MacroRun run) {
			mc.player.swing(of(hand));
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return hand.get().toLowerCase(Locale.ROOT);
		}
	}

	/** Turns the camera, or just what the server sees for a while (silent). */
	private abstract static class Turn extends MacroStep {
		protected BoolSetting silent;
		protected NumberSetting hold;

		/** Called last by subclass constructors, so these two come after the step's own settings. */
		protected void addTurnSettings(boolean silentByDefault) {
			silent = add(new BoolSetting("Silent", "Only the server sees you turn; your camera stays put", silentByDefault));
			hold = add(new NumberSetting("Hold", "Ticks to keep a silent rotation", 10, 1, 200, 1).unit("t")).visibleWhen(silent::get);
		}

		/** Yaw and pitch to face, or null for nowhere (the step is skipped). */
		protected abstract float[] aim(MacroRun run);

		@Override
		public Result tick(MacroRun run) {
			float[] look = aim(run);
			if (look == null) {
				return Result.NEXT;
			}
			if (!silent.get()) {
				mc.player.setYRot(look[0]);
				mc.player.setXRot(look[1]);
				mc.player.yRotO = look[0];
				mc.player.xRotO = look[1];
				return Result.NEXT;
			}
			Rotations.request(look[0], look[1], 25, true);
			return run.stepTicks() + 1 >= hold.getInt() ? Result.NEXT : Result.WAIT;
		}
	}

	public static final class Rotate extends Turn {
		private final ModeSetting mode = add(new ModeSetting("Mode", "Face an exact direction, or turn by an amount", "Absolute",
				"Absolute", "Relative"));
		private final NumberSetting yaw = add(new NumberSetting("Yaw", "Left/right. Absolute: 0 south, 90 west, 180 north, -90 east",
				0, -180, 180, 1).unit("°"));
		private final NumberSetting pitch = add(new NumberSetting("Pitch", "Up/down: -90 straight up, 90 straight down", 0, -90, 90, 1)
				.unit("°"));
		private float[] target;

		public Rotate() {
			addTurnSettings(false);
		}

		@Override
		public void start(MacroRun run) {
			float baseYaw = silent.get() ? Rotations.serverYaw() : mc.player.getYRot();
			float basePitch = silent.get() ? Rotations.serverPitch() : mc.player.getXRot();
			target = mode.is("Absolute")
					? new float[]{yaw.getFloat(), pitch.getFloat()}
					: new float[]{baseYaw + yaw.getFloat(), Mth.clamp(basePitch + pitch.getFloat(), -90f, 90f)};
		}

		@Override
		protected float[] aim(MacroRun run) {
			return target;
		}

		@Override
		public String summary() {
			return (mode.is("Relative") ? "by " : "") + yaw.display() + " " + pitch.display() + (silent.get() ? " silent" : "");
		}
	}

	public static final class LookAtPlayer extends Turn {
		private final NumberSetting range = add(new NumberSetting("Range", "Look at the nearest player this close", 16, 2, 64, 1).unit("m"));

		public LookAtPlayer() {
			addTurnSettings(true);
		}

		@Override
		protected float[] aim(MacroRun run) {
			List<Player> near = Targets.playersWithin(range.get(), false);
			return near.isEmpty() ? null : Rotations.lookAt(near.get(0).getEyePosition());
		}

		@Override
		public String summary() {
			return "within " + range.display() + (silent.get() ? " silent" : "");
		}
	}
}
