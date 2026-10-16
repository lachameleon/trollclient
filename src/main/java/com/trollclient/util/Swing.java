package com.trollclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;

/**
 * Arm swings since 26.3, which dropped the serverbound swing packet. The server now swings for you when it
 * handles a use or an attack, so after one of those the client only plays the animation, as vanilla does. A
 * swing with nothing behind it reaches other players only as a punch (what a click at thin air sends), and
 * there's no such thing for the off hand: that one stays on your own screen.
 */
public final class Swing {
	private Swing() {
	}

	/** The animation alone, for after a use or attack the server already swings for. */
	public static void local(InteractionHand hand) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.swing(hand, mc.player.getItemInHand(hand).getInteractAnimation(), false);
		}
	}

	/** A swing other players can see: a punch for the main hand (it resets the attack cooldown, like a miss). */
	public static void visible(InteractionHand hand) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		// the server plays a punch with the held item's attack animation, so show the same one here
		mc.player.swing(hand, mc.player.getItemInHand(hand).getAttackAnimation(), false);
		if (hand == InteractionHand.MAIN_HAND) {
			mc.player.connection.send(ServerboundPunchPacket.INSTANCE);
		}
	}
}
