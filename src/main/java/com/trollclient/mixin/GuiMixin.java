package com.trollclient.mixin;

import com.trollclient.gui.title.TitleScreenGuard;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps main menus (vanilla, subclassed or modded) for ours while the TitleScreen module is on. */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen troll$replaceTitleScreen(Screen screen) {
		return TitleScreenGuard.filter(screen);
	}
}
