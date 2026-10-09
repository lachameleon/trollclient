package com.trollclient.module.client;

import com.trollclient.command.Commands;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;

/**
 * How the client reads other players' chat on servers that format it
 * themselves: ranks, prefixes, nicknames, party and guild channels. Every chat
 * module (Quizmaster, Parrot, AutoReply...) goes through this.
 */
public class ChatFormatModule extends Module {
	public final TextSetting formats = add(new TextSetting("Formats",
			"Your server's chat layouts, split with |. {player} = name, {message} = what they said, * = anything. "
					+ "Leave blank and most layouts are recognised on their own",
			"", 512).placeholder("auto"));
	public final BoolSetting nicknames = add(new BoolSetting("Nicknames", "Match the names servers show in the tab list, not just account names", true));
	public final BoolSetting showParsed = add(new BoolSetting("Show Parsed", "Print who said what for every line the client understood (for setting up Formats)", false));

	public ChatFormatModule() {
		super("ChatFormat", "Teaches the chat modules your server's chat layout: ranks, prefixes, nicknames and party chat.", Category.CLIENT);
		action("Test Last Line", "Show how the last chat line from the server was read", () -> {
			String line = ChatUtil.lastLine();
			if (line == null) {
				Commands.info("No chat lines yet.");
				return;
			}
			String[] parsed = ChatUtil.parse(line);
			Commands.info("\"" + line + "\" -> " + (parsed == null ? "not a player's chat" : parsed[0] + " said \"" + parsed[1] + "\""));
		});
	}

	@Override
	public boolean isToggleable() {
		return false;
	}
}
