package com.trollclient.mixin;

import com.trollclient.gui.tools.GuiToolsPanel;
import com.trollclient.macro.MacroManager;
import com.trollclient.module.ModuleManager;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Module keybinds fire in game with no screen open, like vanilla hotkeys; macro keys also work in container GUIs. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "keyPress", at = @At("HEAD"))
	private void troll$onKey(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (action != GLFW.GLFW_PRESS || window != minecraft.getWindow().handle()) {
			return;
		}
		if (minecraft.player == null) {
			return;
		}
		Screen screen = minecraft.gui.screen();
		if (screen == null) {
			ModuleManager.keyPressed(event.key());
		}
		// macros also fire inside container GUIs, where the GUI-packet ones are needed, unless you're typing
		// (the tools panel's chat line, a recipe book search...)
		if (screen == null || (screen instanceof AbstractContainerScreen<?> && !GuiToolsPanel.isTyping()
				&& !(screen.getFocused() instanceof EditBox))) {
			MacroManager.keyPressed(event.key());
		}
	}
}
