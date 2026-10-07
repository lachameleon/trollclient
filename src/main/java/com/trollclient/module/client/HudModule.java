package com.trollclient.module.client;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;

public class HudModule extends Module {
	public final BoolSetting watermark = add(new BoolSetting("Watermark", "Client name in the corner", true));
	public final ModeSetting watermarkStyle = add(new ModeSetting("Watermark Style", "Look of the watermark",
			"Bracket", "Bracket", "Block", "Terminal")).visibleWhen(watermark::get);
	public final TextSetting watermarkText = add(new TextSetting("Watermark Text", "What the watermark says", "Troll Client", 24))
			.visibleWhen(watermark::get);
	public final BoolSetting arrayList = add(new BoolSetting("Array List", "List of enabled modules", true));
	public final ModeSetting side = add(new ModeSetting("Side", "Which edge the list hugs", "Right", "Right", "Left"))
			.visibleWhen(arrayList::get);
	public final ModeSetting listStyle = add(new ModeSetting("List Style", "Look of the array list", "Line", "Line", "Box", "Minimal"))
			.visibleWhen(arrayList::get);
	public final BoolSetting showInfo = add(new BoolSetting("Module Info", "Show extra details next to names", true))
			.visibleWhen(arrayList::get);
	public final BoolSetting lowercase = add(new BoolSetting("Lowercase", "lowercase everything", false))
			.visibleWhen(arrayList::get);
	public final BoolSetting targetHud = add(new BoolSetting("Target HUD", "Card next to the crosshair for whoever a module is targeting", true));
	public final BoolSetting radar = add(new BoolSetting("Radar", "Top-down sweep radar of nearby players", true));
	public final NumberSetting radarSize = add(new NumberSetting("Radar Size", "Diameter of the radar", 64, 40, 120, 2).unit("px"))
			.visibleWhen(radar::get);
	public final NumberSetting radarRange = add(new NumberSetting("Radar Range", "Blocks from the centre to the edge", 32, 8, 128, 4)
			.unit("m")).visibleWhen(radar::get);
	public final BoolSetting notifications = add(new BoolSetting("Notifications", "Pop-ups when modules toggle", true));
	public final BoolSetting coords = add(new BoolSetting("Coordinates", "XYZ in the bottom corner", true));
	public final BoolSetting fps = add(new BoolSetting("FPS", "Frames per second under the watermark", true));

	public HudModule() {
		super("HUD", "Watermark, array list, notifications and info lines.", Category.CLIENT);
		restoreEnabled(true);
	}
}
