package com.trollclient.module.client;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
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

	/**
	 * The effect to add to this frame's list, or null. Vanilla rebuilds that list every frame (spectated mob,
	 * player effects...), so {@code GameRendererMixin} appends ours after it does.
	 */
	public static Identifier postEffect() {
		ShadersModule m = ModuleManager.get(ShadersModule.class);
		return m != null && m.isEnabled() ? m.wanted() : null;
	}

	@Override
	public String getInfo() {
		return shader.get();
	}
}
