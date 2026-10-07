package com.trollclient.mixin;

import com.trollclient.dev.CameraRig;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The showcase camera rig places shots exactly; don't let third-person collision pull them into walls. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
	private void troll$noCollisionForRig(float maxZoom, CallbackInfoReturnable<Float> cir) {
		if (CameraRig.isActive()) {
			cir.setReturnValue(maxZoom);
		}
	}
}
