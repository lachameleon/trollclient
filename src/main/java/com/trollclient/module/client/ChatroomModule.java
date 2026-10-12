package com.trollclient.module.client;

import com.trollclient.chat.Chatroom;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;

/** On by default: the room everyone running the client is in. Off closes the connection. */
public class ChatroomModule extends Module {
	public final BoolSetting verifiedOnly = add(new BoolSetting("Verified Only", "Hide people whose Minecraft name couldn't be checked with Mojang", false));
	public final BoolSetting joinNote = add(new BoolSetting("Join Note", "Say how many people are in the room when you join a world", true));

	public ChatroomModule() {
		// one literal: tools/gen_site.py reads the description straight out of this call
		super("Chatroom", "One chat room for everyone running Troll Client, on any server or none. .chat <message> talks in it, and what others say shows up in your chat tagged [room]. Your Minecraft name is only sent when you talk.",
				Category.CLIENT);
		restoreEnabled(true);
	}

	@Override
	protected void onDisable() {
		Chatroom.disconnect();
	}

	@Override
	public String getInfo() {
		return Chatroom.connected() ? Chatroom.online() + " online" : null;
	}
}
