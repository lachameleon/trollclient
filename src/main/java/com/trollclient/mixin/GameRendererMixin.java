package com.trollclient.mixin;

import com.trollclient.dev.FrameRecorder;
import com.trollclient.module.client.ShadersModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * End of a finished frame (world + GUI), where the showcase recorder grabs it, inert unless recording; and the
 * frame's post effect list, where the Shaders module adds its own.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	public abstract List<Identifier> getRequestedPostEffects();

	@Inject(method = "update", at = @At("TAIL"))
	private void troll$addPostEffect(DeltaTracker delta, CallbackInfo ci) {
		Identifier effect = ShadersModule.postEffect();
		if (effect != null) {
			getRequestedPostEffects().add(effect);
		}
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void troll$afterFrame(CallbackInfo ci) {
		if (FrameRecorder.isActive()) {
			FrameRecorder.onFrame();
		}
	}
}
