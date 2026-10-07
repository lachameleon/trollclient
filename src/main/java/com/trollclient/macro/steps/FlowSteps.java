package com.trollclient.macro.steps;

import com.trollclient.command.Commands;
import com.trollclient.gui.clickgui.TerminalLog;
import com.trollclient.macro.Macro;
import com.trollclient.macro.MacroManager;
import com.trollclient.macro.MacroRun;
import com.trollclient.macro.MacroStep;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Targets;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Steps that shape the run itself: waits, repeats, jumps, chat, other modules and macros. */
public final class FlowSteps {
	private FlowSteps() {
	}

	public static final class Delay extends MacroStep {
		private final ModeSetting unit = add(new ModeSetting("Unit", "Count the wait in milliseconds or game ticks (20 a second)",
				"Milliseconds", "Milliseconds", "Ticks"));
		private final NumberSetting ms = add(new NumberSetting("Time", "How long to wait", 500, 0, 30000, 50).unit("ms"))
				.visibleWhen(() -> unit.is("Milliseconds"));
		private final NumberSetting ticks = add(new NumberSetting("Ticks", "How many game ticks to wait", 20, 0, 1200, 1).unit("t"))
				.visibleWhen(() -> unit.is("Ticks"));

		public void setTicks(int value) {
			unit.set("Ticks");
			ticks.set((double) value);
		}

		@Override
		public Result tick(MacroRun run) {
			boolean done = unit.is("Ticks") ? run.stepTicks() >= ticks.getInt() : run.stepMillis() >= ms.getInt();
			return done ? Result.NEXT : Result.WAIT;
		}

		@Override
		public String summary() {
			return unit.is("Ticks") ? ticks.display() : ms.display();
		}
	}

	public static final class Repeat extends MacroStep {
		private final NumberSetting count = add(new NumberSetting("Steps", "How many of the steps after this one to repeat", 1, 1, 64, 1));
		private final NumberSetting times = add(new NumberSetting("Times", "How many times those steps run in all", 3, 2, 500, 1).unit("x"));

		public void set(int steps, int total) {
			count.set((double) steps);
			times.set((double) total);
		}

		@Override
		public Result tick(MacroRun run) {
			run.repeatNext(count.getInt(), times.getInt() - 1);
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return (count.getInt() == 1 ? "next step" : "next " + count.getInt()) + " x" + times.getInt();
		}
	}

	public static final class Label extends MacroStep {
		private final TextSetting name = add(new TextSetting("Name", "What Goto steps call this spot", "start", 24));

		public String name() {
			return name.get().trim();
		}

		@Override
		public Result tick(MacroRun run) {
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return name() + ":";
		}
	}

	public static final class Goto extends MacroStep {
		private final TextSetting label = add(new TextSetting("Label", "Label step to jump to", "start", 24)
				.suggestions(MacroManager::editingLabels));
		private final NumberSetting times = add(new NumberSetting("Times", "Jump this many times per pass, then carry on past it (0 = every time)",
				0, 0, 1000, 1));

		@Override
		public Result tick(MacroRun run) {
			int target = run.macro().indexOfLabel(label.get());
			if (target < 0) {
				run.fail("there's no label called \"" + label.get().trim() + "\"");
				return Result.STOP;
			}
			if (times.getInt() > 0 && run.jumpsOf(this) >= times.getInt()) {
				return Result.NEXT;
			}
			run.countJump(this);
			run.jump(target);
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "-> " + label.get().trim() + (times.getInt() > 0 ? " x" + times.getInt() : "");
		}
	}

	public static final class Stop extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			return Result.STOP;
		}

		@Override
		public String summary() {
			return "";
		}
	}

	public static final class Chat extends MacroStep {
		private final TextSetting message = add(new TextSetting("Message",
				"A chat line or a /command. {player} is the nearest player, {me} you, {count} the loop number", "hello", 256));

		@Override
		public Result tick(MacroRun run) {
			List<Player> near = Targets.playersWithin(64, false);
			String who = near.isEmpty() ? "nobody" : Targets.name(near.get(0));
			ChatUtil.send(ChatUtil.format(message.get(), who, run.loop() + 1));
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return message.get();
		}
	}

	public static final class Notify extends MacroStep {
		private final TextSetting text = add(new TextSetting("Text", "Shown in your chat only, handy for checking how far a macro got", "done", 128));

		public void set(String value) {
			text.set(value);
		}

		@Override
		public Result tick(MacroRun run) {
			Commands.info(text.get());
			TerminalLog.push("echo \"" + text.get() + "\"");
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return text.get();
		}
	}

	public static final class ToggleModule extends MacroStep {
		private final TextSetting module = add(new TextSetting("Module", "Module to switch", "Twerk", 32).suggestions(ToggleModule::names));
		private final ModeSetting action = add(new ModeSetting("Action", "Flip it, or set it on or off", "Toggle", "Toggle", "On", "Off"));

		private static List<String> names() {
			List<String> out = new ArrayList<>();
			for (Module m : ModuleManager.all()) {
				if (m.isToggleable()) {
					out.add(m.getName());
				}
			}
			return out;
		}

		@Override
		public Result tick(MacroRun run) {
			Module m = ModuleManager.byName(module.get());
			if (m == null || !m.isToggleable()) {
				run.fail("there's no module called \"" + module.get() + "\" to switch");
				return Result.STOP;
			}
			switch (action.get()) {
				case "On" -> m.setEnabled(true);
				case "Off" -> m.setEnabled(false);
				default -> m.toggle();
			}
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return action.get().toLowerCase(Locale.ROOT) + " " + module.get();
		}
	}

	public static final class RunMacro extends MacroStep {
		private final TextSetting macro = add(new TextSetting("Macro", "Macro to start", "", 32)
				.suggestions(MacroManager::names).placeholder("macro name"));
		private final BoolSetting await = add(new BoolSetting("Wait", "Wait for it to finish before going on", true));
		private MacroRun child;

		@Override
		public void start(MacroRun run) {
			child = null;
			Macro m = MacroManager.byName(macro.get());
			if (m == null) {
				run.fail("there's no macro called \"" + macro.get() + "\"");
			} else if (m == run.macro()) {
				run.fail("a macro can't start itself (use Goto to loop)");
			} else {
				child = MacroManager.start(m);
			}
		}

		@Override
		public Result tick(MacroRun run) {
			return !await.get() || child == null || child.isFinished() ? Result.NEXT : Result.WAIT;
		}

		@Override
		public void abort() {
			if (child != null && await.get()) {
				MacroManager.stop(child.macro());
			}
		}

		@Override
		public String summary() {
			return macro.get() + (await.get() ? "" : " (no wait)");
		}
	}
}
