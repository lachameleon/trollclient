package com.trollclient.mixin;

import com.trollclient.command.CommandTips;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tips and Tab completion for client commands in the chat box, the way vanilla does it for slash commands. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@Shadow
	protected EditBox input;

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void troll$completeCommand(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (input != null && CommandTips.keyPressed(input, event)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void troll$commandTips(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (input != null) {
			CommandTips.render(g, input);
		}
	}
}
