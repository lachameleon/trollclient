package com.trollclient.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Item drops don't sync the hotbar slot on their own, unlike every other action. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Invoker("ensureHasSentCarriedItem")
	void troll$syncCarriedItem();
}
