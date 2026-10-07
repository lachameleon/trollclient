package com.trollclient.module.client;

import com.trollclient.gui.clickgui.ClickGuiScreen;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import org.lwjgl.glfw.GLFW;

public class ClickGuiModule extends Module {
	public final NumberSetting scale = add(new NumberSetting("Scale", "Size of the menu window", 1.0, 0.6, 1.6, 0.05).unit("x"));
	public final ModeSetting openAnimation = add(new ModeSetting("Open Animation", "How the window appears",
			"CRT", "CRT", "Assemble", "Zoom", "Drop", "Glitch", "Fade"));
	public final NumberSetting animationSpeed = add(new NumberSetting("Animation Speed", "Speed of every animation", 1.0, 0.25, 3.0, 0.05)
			.unit("x"));
	public final ModeSetting background = add(new ModeSetting("Background", "What happens behind the menu",
			"Dim + Blur", "None", "Dim", "Blur", "Dim + Blur"));
	public final NumberSetting dim = add(new NumberSetting("Dim", "How dark the background gets", 55, 0, 100, 1).unit("%"))
			.visibleWhen(() -> background.get().contains("Dim"));
	public final ModeSetting backdrop = add(new ModeSetting("Backdrop", "Animated effect behind the window (Theme picks one to suit the preset)",
			"Rain", "None", "Theme", "Rain", "Grid", "Dust", "Stars", "Waves", "Halftone", "Fog"));
	public final BoolSetting sounds = add(new BoolSetting("Sounds", "Clicky feedback", true));
	public final BoolSetting tooltips = add(new BoolSetting("Tooltips", "Describe things on hover", true));
	public final BoolSetting rememberModule = add(new BoolSetting("Remember Selection", "Reopen on the last module you looked at", true));
	public final BoolSetting pause = add(new BoolSetting("Pause Singleplayer", "Pause the game while the menu is open", false));
	public final TextSetting prefix = add(new TextSetting("Command Prefix", "Chat prefix for client commands", ".", 3));

	public ClickGuiModule() {
		super("ClickGUI", "The menu you're looking at. Bound to Right Shift by default.", Category.CLIENT);
		setKey(GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	@Override
	public void toggle() {
		if (!(mc.gui.screen() instanceof ClickGuiScreen)) {
			mc.gui.setScreen(new ClickGuiScreen(mc.gui.screen()));
		}
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	public float speed() {
		return animationSpeed.getFloat();
	}
}
