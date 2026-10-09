package com.trollclient.setting;

import com.trollclient.config.ConfigManager;
import com.trollclient.telemetry.Telemetry;

/** Tiny hook so any setting change schedules a debounced config save (and gets counted for telemetry). */
public final class SettingEvents {
	private SettingEvents() {
	}

	static void changed(Setting<?> setting) {
		ConfigManager.markDirty();
		Telemetry.settingChanged(setting);
	}
}
