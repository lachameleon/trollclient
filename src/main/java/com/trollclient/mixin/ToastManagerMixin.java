package com.trollclient.mixin;

import com.trollclient.gui.clickgui.ClickGuiScreen;
import com.trollclient.gui.macro.MacroEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla toasts draw over every screen; keep them off the menu and the macro editor while they're open (they still tick). */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void troll$hideOverMenu(GuiGraphicsExtractor g, CallbackInfo ci) {
		if (Minecraft.getInstance().gui.screen() instanceof ClickGuiScreen || Minecraft.getInstance().gui.screen() instanceof MacroEditorScreen) {
			ci.cancel();
		}
	}
}
