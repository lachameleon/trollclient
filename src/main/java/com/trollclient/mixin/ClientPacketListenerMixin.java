package com.trollclient.mixin;

import com.trollclient.module.ModuleManager;
import com.trollclient.module.troll.Graffiti;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards entity events (totem pops, deaths) and damage events to modules,
 * and lets Graffiti fill in the signs it places. TAIL and the openTextEdit call
 * only run on the client thread: the first, off-thread call bails out by
 * throwing before them.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleEntityEvent", at = @At("TAIL"))
	private void troll$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Entity entity = packet.getEntity(mc.level);
		if (entity != null) {
			ModuleManager.entityEvent(entity, packet.getEventId());
		}
	}

	@Inject(method = "handleDamageEvent", at = @At("TAIL"))
	private void troll$onDamageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Entity victim = mc.level.getEntity(packet.entityId());
		if (victim != null) {
			ModuleManager.damage(victim, packet.getSource(mc.level));
		}
	}

	/** The server opens the sign editor after every placement; Graffiti writes its own signs without showing it. */
	@Inject(method = "handleOpenSignEditor", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;openTextEdit(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Z)V"),
			cancellable = true)
	private void troll$onOpenSignEditor(ClientboundOpenSignEditorPacket packet, CallbackInfo ci) {
		Graffiti graffiti = ModuleManager.get(Graffiti.class);
		if (graffiti != null && graffiti.claimEditor(packet.getPos(), packet.isFrontText())) {
			ci.cancel();
		}
	}
}
