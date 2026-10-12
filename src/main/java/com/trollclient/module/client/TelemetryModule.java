package com.trollclient.module.client;

import com.trollclient.gui.Icon;
import com.trollclient.gui.hud.Notifications;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.telemetry.Telemetry;

/** Not a switch: the two things you can do about the anonymous usage stats, which are see them and start over. */
public class TelemetryModule extends Module {
	public TelemetryModule() {
		// one literal: tools/gen_site.py reads the description straight out of this call
		super("Telemetry", "Anonymous usage stats (modules, playtime, setup) for the analytics page on the website. A random id, never your account, chat, coordinates or IP.",
				Category.CLIENT);
		action("Copy Report", "Copy exactly what the next report would send", () -> {
			mc.keyboardHandler.setClipboard(Telemetry.preview());
			Notifications.post("Telemetry", "report copied to the clipboard", Icon.EYE, false);
		});
		action("Reset ID", "Start over with a new random install id", () -> {
			Telemetry.resetId();
			Notifications.post("Telemetry", "new install id", Icon.EYE, false);
		});
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public String getInfo() {
		return Telemetry.endpoint().isEmpty() ? "no endpoint" : null;
	}
}
