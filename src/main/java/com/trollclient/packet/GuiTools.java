package com.trollclient.packet;

import com.trollclient.gui.clickgui.TerminalLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * The screen half of the GUI tools: close a GUI without telling the server,
 * tell the server it closed while it stays open here, and stash a GUI to
 * bring back later.
 */
public final class GuiTools {
	private static Screen savedScreen;
	private static AbstractContainerMenu savedMenu;

	private GuiTools() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** True if a real (server-side) container is open, not just the player's own inventory. */
	public static boolean containerOpen() {
		Minecraft mc = mc();
		return mc.player != null && mc.player.containerMenu != null && mc.player.containerMenu != mc.player.inventoryMenu;
	}

	/** Closes the GUI on our side only; as far as the server knows, it's still open. */
	public static boolean closeWithoutPacket() {
		Minecraft mc = mc();
		if (mc.player == null || mc.gui.screen() == null) {
			return false;
		}
		if (containerOpen()) {
			// vanilla's close minus the ServerboundContainerClosePacket
			mc.player.clientSideCloseContainer();
		} else {
			mc.gui.setScreen(null);
		}
		TerminalLog.push("gui --close --no-packet");
		return true;
	}

	/** Sends the close packet but keeps the GUI open here: we can still click, the server thinks it's shut. */
	public static boolean desync() {
		Minecraft mc = mc();
		if (mc.getConnection() == null || !containerOpen()) {
			return false;
		}
		mc.getConnection().send(new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
		TerminalLog.push("gui --desync #" + mc.player.containerMenu.containerId);
		return true;
	}

	public static boolean save() {
		Minecraft mc = mc();
		if (mc.player == null || mc.gui.screen() == null) {
			return false;
		}
		savedScreen = mc.gui.screen();
		savedMenu = mc.player.containerMenu;
		TerminalLog.push("gui --save \"" + title(savedScreen) + "\"");
		return true;
	}

	/** Reopens the saved GUI on its old container, without the server being asked. */
	public static boolean restore() {
		Minecraft mc = mc();
		if (savedScreen == null || savedMenu == null || mc.player == null) {
			return false;
		}
		mc.player.containerMenu = savedMenu;
		mc.gui.setScreen(savedScreen);
		TerminalLog.push("gui --restore \"" + title(savedScreen) + "\"");
		return true;
	}

	public static boolean hasSaved() {
		return savedScreen != null;
	}

	public static String savedTitle() {
		return savedScreen == null ? null : title(savedScreen);
	}

	/** The GUI's title (plain text) to the clipboard, for plugins that name their menus. */
	public static boolean copyTitle() {
		Minecraft mc = mc();
		if (mc.gui.screen() == null) {
			return false;
		}
		String title = title(mc.gui.screen());
		mc.keyboardHandler.setClipboard(title);
		TerminalLog.push("gui --title | pbcopy");
		return true;
	}

	/** Sends the delay queue, then drops the connection right behind it. Returns how many packets went out. */
	public static int disconnectAndSend() {
		Minecraft mc = mc();
		if (mc.getConnection() == null) {
			return 0;
		}
		int sent = PacketGate.flush();
		PacketGate.reset();
		mc.getConnection().getConnection().disconnect(Component.literal("Disconnected by Troll Client (" + sent + " packets sent)"));
		return sent;
	}

	/** Forget the saved GUI; it belonged to the world we just left. */
	public static void reset() {
		savedScreen = null;
		savedMenu = null;
	}

	private static String title(Screen screen) {
		return screen.getTitle() == null ? "" : screen.getTitle().getString();
	}
}
