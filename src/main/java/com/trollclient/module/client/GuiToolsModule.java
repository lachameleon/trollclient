package com.trollclient.module.client;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.packet.GuiTools;
import com.trollclient.packet.PacketGate;
import com.trollclient.setting.BoolSetting;

public class GuiToolsModule extends Module {
	public final BoolSetting panel = add(new BoolSetting("Panel", "Show the tools panel on inventories and containers", true));
	public final BoolSetting chat = add(new BoolSetting("Chat Box", "A chat line in the panel, so you can talk without closing the GUI", true))
			.visibleWhen(panel::get);
	public final BoolSetting flushOnRelease = add(new BoolSetting("Flush On Release",
			"Turning delay off sends whatever was queued (off: the queue waits for flush)", true));
	public final BoolSetting resetOnLeave = add(new BoolSetting("Reset On Leave",
			"Sending back on, delay off and the queue emptied when you leave a world", true));
	public final BoolSetting warning = add(new BoolSetting("HUD Warning", "A badge at the top of the screen while packets are held or dropped", true));

	public GuiToolsModule() {
		super("GuiTools", "Packet tools on every container: drop or delay GUI packets, de-sync, close without a packet, save a GUI.",
				Category.CLIENT);
		action("Flush Queue", "Send every queued packet now", PacketGate::flush);
		restoreEnabled(true);
	}

	@Override
	protected void onDisable() {
		PacketGate.setSending(true);
		PacketGate.setDelaying(false);
	}

	@Override
	public void onWorldLeave() {
		if (resetOnLeave.get()) {
			PacketGate.reset();
		}
		GuiTools.reset();
	}

	@Override
	public String getInfo() {
		if (!PacketGate.isSending()) {
			return "dropping";
		}
		return PacketGate.isDelaying() || PacketGate.queued() > 0 ? PacketGate.queued() + " held" : null;
	}
}
