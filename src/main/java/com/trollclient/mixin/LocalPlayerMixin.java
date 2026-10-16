package com.trollclient.mixin;

import com.trollclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps in silent rotations from the start of the player tick until the movement packet has gone out: input,
 * physics and the packet all see the same yaw, exactly like a player who turned. Since 26.3 the packet isn't
 * sent from {@code tick} but from {@code sendChanges}, which Minecraft calls after every entity has ticked.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void troll$preTick(CallbackInfo ci) {
		Rotations.begin((LocalPlayer) (Object) this);
	}

	@Inject(method = "sendChanges", at = @At("RETURN"))
	private void troll$afterSend(CallbackInfo ci) {
		Rotations.end((LocalPlayer) (Object) this);
	}
}
