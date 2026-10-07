package com.trollclient.mixin;

import com.trollclient.dev.FrameRecorder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** End of a finished frame (world + GUI), where the showcase recorder grabs it. Inert unless recording. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void troll$afterFrame(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
		if (FrameRecorder.isActive()) {
			FrameRecorder.onFrame();
		}
	}
}
