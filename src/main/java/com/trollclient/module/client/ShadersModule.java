package com.trollclient.module.client;

import com.trollclient.mixin.GameRendererAccessor;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.ModeSetting;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/** Black & white post-processing for the world itself (the HUD and menus stay crisp). */
public class ShadersModule extends Module {
	private final ModeSetting shader = add(new ModeSetting("Shader", "Which look to give the world", "Noir",
			"Noir", "CRT", "Dither", "Halftone", "Ink"));

	public ShadersModule() {
		super("Shaders", "Post-processing for the world: film noir, monochrome CRT, 1-bit dither, halftone or pen and ink.",
				Category.CLIENT);
	}

	private Identifier wanted() {
		return Identifier.fromNamespaceAndPath("trollclient", shader.get().toLowerCase(Locale.ROOT));
	}

	private static boolean ours(Identifier id) {
		return id != null && id.getNamespace().equals("trollclient");
	}

	@Override
	public void onTick() {
		// re-applied every tick: vanilla clears post effects whenever the camera entity changes
		Identifier want = wanted();
		if (!want.equals(mc.gameRenderer.currentPostEffect())) {
			((GameRendererAccessor) mc.gameRenderer).troll$setPostEffect(want);
		}
	}

	@Override
	protected void onDisable() {
		if (mc.gameRenderer != null && ours(mc.gameRenderer.currentPostEffect())) {
			mc.gameRenderer.clearPostEffect();
		}
	}

	@Override
	public String getInfo() {
		return shader.get();
	}
}
