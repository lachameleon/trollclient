package com.trollclient.mixin;

import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies {@link MovementControl} requests right after vanilla reads the keyboard.
 *
 * <p>Everything ends up as plain key presses and the exact move vector vanilla
 * derives from them, so the input packet, the physics and what the server
 * simulates all agree. This runs inside the player tick, so {@code getYRot()}
 * is already the silent yaw when one is active.</p>
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Unique
	private int[] troll$lastKeys;

	@Inject(method = "tick", at = @At("TAIL"))
	private void troll$overrideMovement(CallbackInfo ci) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		Input keys = this.keyPresses;
		boolean jump = keys.jump() || MovementControl.isJumpRequested();
		boolean shift = MovementControl.isSneakRequested() ? MovementControl.getSneak() : keys.shift();
		boolean sprint = keys.sprint();
		int strafe = impulse(keys.left(), keys.right());
		int forward = impulse(keys.forward(), keys.backward());

		boolean steered = true;
		double[] dir = MovementControl.direction();
		float yaw = player.getYRot();
		if (dir != null) {
			int[] k = MovementControl.keysFor(yaw, dir[0], dir[1], troll$lastKeys);
			strafe = k[0];
			forward = k[1];
			sprint = MovementControl.getSprint() && forward > 0;
		} else if (Rotations.isSwapped() && (strafe != 0 || forward != 0)) {
			// we're facing somewhere else for the server: press whichever keys walk where the camera means to go
			float cam = Rotations.cameraYaw(player);
			float sin = Mth.sin(cam * Mth.DEG_TO_RAD);
			float cos = Mth.cos(cam * Mth.DEG_TO_RAD);
			double dx = -forward * sin + strafe * cos;
			double dz = forward * cos + strafe * sin;
			int[] k = MovementControl.keysFor(yaw, dx, dz, troll$lastKeys);
			strafe = k[0];
			forward = k[1];
			sprint &= forward > 0;
		} else {
			steered = false;
		}
		troll$lastKeys = new int[]{strafe, forward};

		if (steered) {
			this.keyPresses = new Input(forward > 0, forward < 0, strafe > 0, strafe < 0, jump, shift, sprint);
			this.moveVector = new Vec2(strafe, forward).normalized();
		} else {
			// the player's own keys, untouched (holding W and S together stays exactly that)
			this.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), jump, shift, sprint);
		}
	}

	@Unique
	private static int impulse(boolean positive, boolean negative) {
		return positive == negative ? 0 : positive ? 1 : -1;
	}
}
