package com.trollclient.mixin;

import com.trollclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps in silent rotations for the whole player tick: input, physics and the
 * movement packet all see the same yaw, exactly like a player who turned.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void troll$preTick(CallbackInfo ci) {
		Rotations.begin((LocalPlayer) (Object) this);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void troll$postTick(CallbackInfo ci) {
		Rotations.end((LocalPlayer) (Object) this);
	}
}
