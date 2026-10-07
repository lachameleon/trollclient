package com.trollclient.macro.steps;

import com.trollclient.macro.MacroManager;
import com.trollclient.macro.MacroRun;
import com.trollclient.macro.MacroStep;
import com.trollclient.packet.GuiTools;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;

import java.util.Locale;

/** Steps that hold the macro until something happens: a GUI opening or closing, or a chat line. */
public final class WaitSteps {
	private WaitSteps() {
	}

	private static NumberSetting timeout() {
		return new NumberSetting("Timeout", "Give up after this long (0 = wait forever)", 10, 0, 120, 1).unit("s");
	}

	private static String limit(NumberSetting timeout) {
		return timeout.getInt() > 0 ? " (" + timeout.display() + ")" : "";
	}

	public static final class Gui extends MacroStep {
		private final ModeSetting event = add(new ModeSetting("For", "A new container opening, the open one closing, or a GUI with a title",
				"Open", "Open", "Close", "Title"));
		private final TextSetting title = add(new TextSetting("Title", "Part of the GUI's title, e.g. chest", "", 64).placeholder("chest"))
				.visibleWhen(() -> event.is("Title"));
		private final NumberSetting timeout = add(timeout());
		private final ModeSetting onTimeout = add(new ModeSetting("On Timeout", "Stop the macro, or carry on anyway", "Stop", "Stop", "Continue"))
				.visibleWhen(() -> timeout.getInt() > 0);
		/** Container open when the step started; "Open" waits for a different one. */
		private int startId;

		@Override
		public void start(MacroRun run) {
			startId = GuiTools.containerOpen() ? mc.player.containerMenu.containerId : -1;
		}

		@Override
		public Result tick(MacroRun run) {
			boolean done = switch (event.get()) {
				case "Close" -> !GuiTools.containerOpen();
				case "Title" -> mc.gui.screen() != null && mc.gui.screen().getTitle().getString().toLowerCase(Locale.ROOT)
						.contains(title.get().toLowerCase(Locale.ROOT));
				default -> GuiTools.containerOpen() && mc.player.containerMenu.containerId != startId;
			};
			return done ? Result.NEXT : timedOut(run, timeout, onTimeout);
		}

		@Override
		public String summary() {
			return (event.is("Title") ? "\"" + title.get() + "\"" : event.get().toLowerCase(Locale.ROOT)) + limit(timeout);
		}
	}

	public static final class Chat extends MacroStep {
		private final TextSetting text = add(new TextSetting("Contains", "Wait until a chat line has this in it", "", 128).placeholder("text"));
		private final NumberSetting timeout = add(timeout());
		private final ModeSetting onTimeout = add(new ModeSetting("On Timeout", "Stop the macro, or carry on anyway", "Stop", "Stop", "Continue"))
				.visibleWhen(() -> timeout.getInt() > 0);
		private int since;

		@Override
		public void start(MacroRun run) {
			since = MacroManager.chatCount();
		}

		@Override
		public Result tick(MacroRun run) {
			return MacroManager.chatSince(since, text.get()) ? Result.NEXT : timedOut(run, timeout, onTimeout);
		}

		@Override
		public String summary() {
			return "\"" + text.get() + "\"" + limit(timeout);
		}
	}
}
