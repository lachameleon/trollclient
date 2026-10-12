package com.trollclient.module.client;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.ModeSetting;
import com.trollclient.update.Updater;

/** On by default: new versions arrive by themselves and load on the next launch. */
public class UpdaterModule extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode", "Install new versions for the next launch, or only say when there is one",
			"Install", "Install", "Notify"));

	public UpdaterModule() {
		// one literal: tools/gen_site.py reads the description straight out of this call
		super("Updater", "Gets new versions by itself: checks on launch, downloads the build for the Minecraft version you're running, and loads it next time you start the game. Never a build for another Minecraft version.",
				Category.CLIENT);
		action("Check Now", "Look for a new version right now", Updater::checkNow);
		restoreEnabled(true);
	}

	@Override
	public String getInfo() {
		String status = Updater.status();
		return status.isEmpty() ? null : status;
	}
}
