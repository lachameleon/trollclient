package com.trollclient.module.client;

import com.trollclient.gui.Icon;
import com.trollclient.gui.hud.Notifications;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.telemetry.Telemetry;

/** On by default: anonymous usage stats for the analytics page. Switching it off stops counting too. */
public class TelemetryModule extends Module {
	public TelemetryModule() {
		super("Telemetry", "Anonymous usage stats (modules, playtime, setup) for the analytics page on the website. "
				+ "A random id, never your account, chat, coordinates or IP.", Category.CLIENT);
		action("Copy Report", "Copy exactly what the next report would send", () -> {
			mc.keyboardHandler.setClipboard(Telemetry.preview());
			Notifications.post("Telemetry", "report copied to the clipboard", Icon.EYE, false);
		});
		action("Reset ID", "Start over with a new random install id", () -> {
			Telemetry.resetId();
			Notifications.post("Telemetry", "new install id", Icon.EYE, false);
		});
		restoreEnabled(true);
	}

	@Override
	protected void onDisable() {
		Telemetry.clear();
	}

	@Override
	public String getInfo() {
		return Telemetry.endpoint().isEmpty() ? "no endpoint" : null;
	}
}
