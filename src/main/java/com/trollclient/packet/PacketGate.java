package com.trollclient.packet;

import com.trollclient.gui.clickgui.TerminalLog;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.GuiToolsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds back the packets GUIs send: container clicks and buttons, trades,
 * renames, signs, books, item use and drops, chat and commands. With sending
 * off they're dropped; with delay on they wait in a queue until flushed.
 * Keep-alives, movement and the container close packet always go through,
 * which is the whole point: the server sees the close before the clicks.
 */
public final class PacketGate {
	/** A held-back packet, with a readable name for the queue list. */
	public record Queued(Packet<?> packet, String name, String detail, long at) {
	}

	private static final List<Queued> QUEUE = new CopyOnWriteArrayList<>();
	private static volatile boolean sending = true;
	private static volatile boolean delaying;
	/** Set while we send queued packets ourselves, so they don't land straight back in the queue. */
	private static volatile boolean bypass;
	private static int dropped;

	private PacketGate() {
	}

	private static boolean active() {
		GuiToolsModule tools = ModuleManager.get(GuiToolsModule.class);
		return tools != null && tools.isEnabled();
	}

	/** Mixin hook on every outgoing packet: true means it doesn't go out now (dropped, or queued). */
	public static boolean intercept(Packet<?> packet) {
		if (bypass || (sending && !delaying) || !isGuiPacket(packet) || !active()) {
			return false;
		}
		if (!sending) {
			dropped++;
			return true;
		}
		QUEUE.add(new Queued(packet, name(packet), detail(packet), System.currentTimeMillis()));
		return true;
	}

	public static boolean isSending() {
		return sending;
	}

	public static boolean isDelaying() {
		return delaying;
	}

	/** True while anything is being held back or thrown away, for the HUD warning. */
	public static boolean isHolding() {
		return active() && (!sending || delaying || !QUEUE.isEmpty());
	}

	public static void setSending(boolean value) {
		if (sending == value) {
			return;
		}
		sending = value;
		dropped = 0;
		TerminalLog.push("packets --send " + (value ? "on" : "off"));
	}

	/**
	 * Turns delay on or off. Turning it off sends whatever was queued when the
	 * GuiTools "Flush On Release" setting is on, otherwise the queue stays put.
	 *
	 * @return how many queued packets went out
	 */
	public static int setDelaying(boolean value) {
		if (delaying == value) {
			return 0;
		}
		delaying = value;
		int sent = 0;
		if (!value && ModuleManager.get(GuiToolsModule.class).flushOnRelease.get()) {
			sent = flush();
		}
		TerminalLog.push("packets --delay " + (value ? "on" : "off") + (sent > 0 ? " (sent " + sent + ")" : ""));
		return sent;
	}

	/** Sends every queued packet in order. Returns how many went out. */
	public static int flush() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null || QUEUE.isEmpty()) {
			return 0;
		}
		List<Queued> batch = new ArrayList<>(QUEUE);
		QUEUE.clear();
		bypass = true;
		try {
			for (Queued q : batch) {
				mc.getConnection().send(q.packet());
			}
		} finally {
			bypass = false;
		}
		TerminalLog.push("queue --flush " + batch.size());
		return batch.size();
	}

	public static int clear() {
		int n = QUEUE.size();
		QUEUE.clear();
		if (n > 0) {
			TerminalLog.push("queue --clear " + n);
		}
		return n;
	}

	/** Drops one queued packet. */
	public static void remove(Queued q) {
		if (QUEUE.remove(q)) {
			TerminalLog.push("queue --drop " + q.name());
		}
	}

	public static List<Queued> queue() {
		return List.copyOf(QUEUE);
	}

	public static int queued() {
		return QUEUE.size();
	}

	/** Packets thrown away since sending was switched off. */
	public static int dropped() {
		return dropped;
	}

	/** Back to normal: sending on, delay off, queue emptied (it can't be sent anywhere after leaving). */
	public static void reset() {
		sending = true;
		delaying = false;
		dropped = 0;
		QUEUE.clear();
	}

	public static boolean isGuiPacket(Packet<?> p) {
		return p instanceof ServerboundContainerClickPacket || p instanceof ServerboundContainerButtonClickPacket
				|| p instanceof ServerboundContainerSlotStateChangedPacket || p instanceof ServerboundSetCreativeModeSlotPacket
				|| p instanceof ServerboundPlaceRecipePacket || p instanceof ServerboundRenameItemPacket
				|| p instanceof ServerboundSelectTradePacket || p instanceof ServerboundSetBeaconPacket
				|| p instanceof ServerboundSignUpdatePacket || p instanceof ServerboundEditBookPacket
				|| p instanceof ServerboundPlayerActionPacket || p instanceof ServerboundUseItemPacket
				|| p instanceof ServerboundChatPacket || p instanceof ServerboundChatCommandPacket
				|| p instanceof ServerboundChatCommandSignedPacket || p instanceof ServerboundCustomClickActionPacket;
	}

	public static String name(Packet<?> p) {
		return switch (p) {
			case ServerboundContainerClickPacket c -> "click";
			case ServerboundContainerButtonClickPacket b -> "button";
			case ServerboundSetCreativeModeSlotPacket c -> "creative";
			case ServerboundRenameItemPacket r -> "rename";
			case ServerboundSelectTradePacket t -> "trade";
			case ServerboundSignUpdatePacket s -> "sign";
			case ServerboundEditBookPacket b -> "book";
			case ServerboundPlayerActionPacket a -> "action";
			case ServerboundUseItemPacket u -> "use";
			case ServerboundChatPacket c -> "chat";
			case ServerboundChatCommandPacket c -> "cmd";
			case ServerboundChatCommandSignedPacket c -> "cmd";
			default -> words(p.getClass().getSimpleName().replace("Serverbound", "").replace("Packet", ""));
		};
	}

	public static String detail(Packet<?> p) {
		return switch (p) {
			case ServerboundContainerClickPacket c -> "slot " + c.slotNum() + " " + lower(c.containerInput().name()) + " " + c.buttonNum();
			case ServerboundContainerButtonClickPacket b -> "id " + b.buttonId();
			case ServerboundSetCreativeModeSlotPacket c -> "slot " + c.slotNum();
			case ServerboundRenameItemPacket r -> "\"" + r.getName() + "\"";
			case ServerboundSelectTradePacket t -> "#" + t.getItem();
			case ServerboundSignUpdatePacket s -> String.join("/", s.getLines()).replaceAll("/+$", "");
			case ServerboundPlayerActionPacket a -> lower(a.getAction().name());
			case ServerboundUseItemPacket u -> lower(u.getHand().name());
			case ServerboundChatPacket c -> c.message();
			case ServerboundChatCommandPacket c -> "/" + c.command();
			case ServerboundChatCommandSignedPacket c -> "/" + c.command();
			default -> "";
		};
	}

	private static String lower(String enumName) {
		return enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	/** "ContainerSlotStateChanged" -> "container slot state changed". */
	private static String words(String camel) {
		return camel.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
	}
}
