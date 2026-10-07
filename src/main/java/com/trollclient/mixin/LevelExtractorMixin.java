package com.trollclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.trollclient.dev.CameraRig;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla never draws your own player while the camera is attached to another
 * entity (spectating a mob). The showcase recorder's free camera needs it drawn.
 * Inert unless the camera rig is running.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@ModifyExpressionValue(method = "extractVisibleEntities",
			at = @At(value = "CONSTANT", args = "classValue=net/minecraft/client/player/LocalPlayer"))
	private boolean troll$drawLocalPlayerForRig(boolean isLocalPlayer) {
		return isLocalPlayer && !CameraRig.isActive();
	}
}
