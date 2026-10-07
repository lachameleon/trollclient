package com.trollclient.mixin;

import com.trollclient.module.ModuleManager;
import com.trollclient.module.chat.Announcer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Notices successful block placements (for Announcer). */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Unique
	private Block troll$placing;

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void troll$beforeUseOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		ItemStack stack = player.getItemInHand(hand);
		troll$placing = stack.getItem() instanceof BlockItem block ? block.getBlock() : null;
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void troll$afterUseOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Block placed = troll$placing;
		troll$placing = null;
		if (placed != null && cir.getReturnValue().consumesAction()) {
			Announcer announcer = ModuleManager.get(Announcer.class);
			if (announcer != null && announcer.isEnabled()) {
				announcer.blockPlaced(placed);
			}
		}
	}
}
