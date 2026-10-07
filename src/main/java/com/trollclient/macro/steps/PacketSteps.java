package com.trollclient.macro.steps;

import com.trollclient.macro.MacroRun;
import com.trollclient.macro.MacroStep;
import com.trollclient.packet.GuiTools;
import com.trollclient.packet.PacketGate;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** The GUI tools panel's buttons as steps: packet gate switches, the queue, and the screen tricks. */
public final class PacketSteps {
	private PacketSteps() {
	}

	private static boolean resolve(ModeSetting state, boolean current) {
		return switch (state.get()) {
			case "On" -> true;
			case "Off" -> false;
			default -> !current;
		};
	}

	public static final class Send extends MacroStep {
		private final ModeSetting state = add(new ModeSetting("Send", "Send GUI packets (on), drop them (off), or flip it",
				"Off", "On", "Off", "Toggle"));

		@Override
		public Result tick(MacroRun run) {
			PacketGate.setSending(resolve(state, PacketGate.isSending()));
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return state.get().toLowerCase(Locale.ROOT);
		}
	}

	public static final class Delay extends MacroStep {
		private final ModeSetting state = add(new ModeSetting("Delay", "Queue GUI packets (on), stop queueing (off), or flip it",
				"On", "On", "Off", "Toggle"));

		@Override
		public Result tick(MacroRun run) {
			PacketGate.setDelaying(resolve(state, PacketGate.isDelaying()));
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return state.get().toLowerCase(Locale.ROOT);
		}
	}

	public static final class Flush extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			PacketGate.flush();
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "";
		}
	}

	public static final class Clear extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			PacketGate.clear();
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "";
		}
	}

	public static final class Desync extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			GuiTools.desync();
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "";
		}
	}

	public static final class CloseGui extends MacroStep {
		private final BoolSetting packet = add(new BoolSetting("Send Packet",
				"Tell the server too, like a normal close. Off closes it on your side only", false));

		@Override
		public Result tick(MacroRun run) {
			if (!packet.get()) {
				GuiTools.closeWithoutPacket();
			} else if (GuiTools.containerOpen()) {
				mc.player.closeContainer();
			} else if (mc.gui.screen() != null) {
				mc.gui.setScreen(null);
			}
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return packet.get() ? "with packet" : "no packet";
		}
	}

	public static final class SaveGui extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			GuiTools.save();
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "";
		}
	}

	public static final class RestoreGui extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			if (!GuiTools.restore()) {
				run.fail("no saved GUI to restore");
				return Result.STOP;
			}
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "";
		}
	}

	public static final class Disconnect extends MacroStep {
		private final BoolSetting sendQueue = add(new BoolSetting("Send Queue First", "Flush the delay queue right before leaving", true));

		@Override
		public Result tick(MacroRun run) {
			if (sendQueue.get()) {
				GuiTools.disconnectAndSend();
			} else if (mc.getConnection() != null) {
				mc.getConnection().getConnection().disconnect(Component.literal("Disconnected by a Troll Client macro"));
			}
			return Result.STOP;
		}

		@Override
		public String summary() {
			return sendQueue.get() ? "after flush" : "";
		}
	}
}
