package com.trollclient.setting;

import com.trollclient.config.ConfigManager;

/** Tiny hook so any setting change schedules a debounced config save. */
public final class SettingEvents {
	private SettingEvents() {
	}

	static void changed(Setting<?> setting) {
		ConfigManager.markDirty();
	}
}
