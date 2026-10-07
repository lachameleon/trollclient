package com.trollclient.module.client;

import com.trollclient.gui.macro.MacroEditorScreen;
import com.trollclient.macro.MacroManager;
import com.trollclient.module.Category;
import com.trollclient.module.Module;

/** Settings-only entry for the macro editor; its key (".bind macros m") opens the editor. */
public class MacrosModule extends Module {
	public MacrosModule() {
		super("Macros", "Chains of steps (clicks, packets, chat, waits...) you bind to keys, run from the GUI tools or with .macro run.",
				Category.CLIENT);
		action("Open Editor", "Build, edit and run macros", MacroEditorScreen::open);
		action("Stop All", "Stop every running macro", MacroManager::stopAll);
	}

	@Override
	public void toggle() {
		MacroEditorScreen.open();
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public String getInfo() {
		int running = MacroManager.runs().size();
		return running > 0 ? running + " running" : null;
	}
}
