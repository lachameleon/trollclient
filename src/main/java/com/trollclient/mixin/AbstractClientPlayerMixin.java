package com.trollclient.mixin;

import com.trollclient.util.SkinOverrides;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Client-side skins (SkinChanger and the showcase's stand-in players). */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void troll$overrideSkin(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin original = cir.getReturnValue();
		PlayerSkin skin = SkinOverrides.apply((AbstractClientPlayer) (Object) this, original);
		if (skin != original) {
			cir.setReturnValue(skin);
		}
	}
}
