package com.trollclient.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
	@Accessor("yRotLast")
	float troll$getYRotLast();

	@Accessor("xRotLast")
	float troll$getXRotLast();
}
