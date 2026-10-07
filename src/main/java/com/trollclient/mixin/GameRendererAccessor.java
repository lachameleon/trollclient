package com.trollclient.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Post effects are normally only set by spectating mobs; the Shaders module sets its own. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("setPostEffect")
	void troll$setPostEffect(Identifier id);
}
