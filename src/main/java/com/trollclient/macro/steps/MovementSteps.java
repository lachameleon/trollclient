package com.trollclient.macro.steps;

import com.trollclient.macro.MacroRun;
import com.trollclient.macro.MacroStep;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.MovementControl;
import net.minecraft.util.Mth;

import java.util.Locale;

/** Sneaking, jumping, sprinting and walking, through the same movement control the modules use. */
public final class MovementSteps {
	private MovementSteps() {
	}

	public static final class Sneak extends MacroStep {
		private final ModeSetting mode = add(new ModeSetting("Mode", "Tap it, hold it until a Release step, or let go",
				"Tap", "Tap", "Hold", "Release"));
		private final NumberSetting ticks = add(new NumberSetting("Ticks", "How long the tap lasts", 4, 1, 100, 1).unit("t"))
				.visibleWhen(() -> mode.is("Tap"));

		@Override
		public Result tick(MacroRun run) {
			switch (mode.get()) {
				case "Hold" -> run.setSneak(true);
				case "Release" -> run.setSneak(null);
				default -> {
					MovementControl.sneak(MovementControl.PRIORITY_MACRO, true);
					return run.stepTicks() + 1 >= ticks.getInt() ? Result.NEXT : Result.WAIT;
				}
			}
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return mode.is("Tap") ? "tap " + ticks.display() : mode.get().toLowerCase(Locale.ROOT);
		}
	}

	public static final class Jump extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			MovementControl.jump();
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "";
		}
	}

	public static final class Sprint extends MacroStep {
		private final ModeSetting state = add(new ModeSetting("Sprint", "Start or stop sprinting", "On", "On", "Off"));

		@Override
		public Result tick(MacroRun run) {
			mc.player.setSprinting(state.is("On"));
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return state.get().toLowerCase(Locale.ROOT);
		}
	}

	public static final class Walk extends MacroStep {
		private final ModeSetting direction = add(new ModeSetting("Direction", "Which way, relative to where you're looking",
				"Forward", "Forward", "Back", "Left", "Right"));
		private final NumberSetting ticks = add(new NumberSetting("Ticks", "How long to walk", 20, 1, 400, 1).unit("t"));
		private final BoolSetting sprint = add(new BoolSetting("Sprint", "Sprint (forward only)", false));

		@Override
		public Result tick(MacroRun run) {
			float rad = mc.player.getYRot() * Mth.DEG_TO_RAD;
			double fx = -Mth.sin(rad), fz = Mth.cos(rad);
			// left is forward turned a quarter to the left
			double lx = Mth.cos(rad), lz = Mth.sin(rad);
			double[] dir = switch (direction.get()) {
				case "Back" -> new double[]{-fx, -fz};
				case "Left" -> new double[]{lx, lz};
				case "Right" -> new double[]{-lx, -lz};
				default -> new double[]{fx, fz};
			};
			MovementControl.move(MovementControl.PRIORITY_MACRO, dir[0], dir[1], sprint.get() && direction.is("Forward"));
			return run.stepTicks() + 1 >= ticks.getInt() ? Result.NEXT : Result.WAIT;
		}

		@Override
		public String summary() {
			return direction.get().toLowerCase(Locale.ROOT) + " " + ticks.display() + (sprint.get() ? " sprint" : "");
		}
	}
}
