package com.trollclient.mixin;

import com.trollclient.packet.PacketGate;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every packet the client sends passes the GUI tools' gate, which may drop or queue it. */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
			at = @At("HEAD"), cancellable = true)
	private void troll$gate(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		// only our own connection to the server; the integrated server's connections send the other way
		if (((Connection) (Object) this).getSending() == PacketFlow.SERVERBOUND && PacketGate.intercept(packet)) {
			ci.cancel();
		}
	}
}
