package com.trollclient.macro;

import com.trollclient.macro.steps.FlowSteps;
import com.trollclient.macro.steps.InteractSteps;
import com.trollclient.macro.steps.InventorySteps;
import com.trollclient.macro.steps.MovementSteps;
import com.trollclient.macro.steps.PacketSteps;
import com.trollclient.macro.steps.WaitSteps;

import java.util.ArrayList;
import java.util.List;

/** Every kind of step, in the order (and categories) the editor's palette shows them. */
public final class Steps {
	public static final List<String> CATEGORIES = List.of("flow", "packets", "inventory", "interact", "movement", "wait");

	private static final List<StepType> ALL = List.of(
			type("delay", "Delay", "flow", "Wait some milliseconds or ticks", FlowSteps.Delay::new),
			type("repeat", "Repeat", "flow", "Run the next few steps several times", FlowSteps.Repeat::new),
			type("label", "Label", "flow", "A named spot for Goto to jump to", FlowSteps.Label::new),
			type("goto", "Goto", "flow", "Jump to a label (a few times, or forever)", FlowSteps.Goto::new),
			type("stop", "Stop", "flow", "End the macro here", FlowSteps.Stop::new),
			type("chat", "Chat", "flow", "Say something or run a /command", FlowSteps.Chat::new),
			type("notify", "Notify", "flow", "Print a line in your own chat", FlowSteps.Notify::new),
			type("module", "Module", "flow", "Switch a Troll Client module on or off", FlowSteps.ToggleModule::new),
			type("run_macro", "Run Macro", "flow", "Start another macro, and wait for it", FlowSteps.RunMacro::new),

			type("send_packets", "Send Packets", "packets", "Turn sending GUI packets on or off", PacketSteps.Send::new),
			type("delay_packets", "Delay Packets", "packets", "Queue GUI packets until flushed", PacketSteps.Delay::new),
			type("flush", "Flush Queue", "packets", "Send every queued packet now", PacketSteps.Flush::new),
			type("clear", "Clear Queue", "packets", "Throw the queued packets away", PacketSteps.Clear::new),
			type("desync", "De-sync", "packets", "Tell the server the GUI closed, keep it open here", PacketSteps.Desync::new),
			type("close_gui", "Close GUI", "packets", "Close the GUI, with or without telling the server", PacketSteps.CloseGui::new),
			type("save_gui", "Save GUI", "packets", "Remember the open GUI for later", PacketSteps.SaveGui::new),
			type("restore_gui", "Restore GUI", "packets", "Reopen the saved GUI without asking the server", PacketSteps.RestoreGui::new),
			type("disconnect", "Disconnect", "packets", "Leave the server, sending the queue first", PacketSteps.Disconnect::new),

			type("click_slot", "Click Slot", "inventory", "Click a slot in the open GUI", InventorySteps.ClickSlot::new),
			type("select_slot", "Select Slot", "inventory", "Pick a hotbar slot by number or item", InventorySteps.SelectSlot::new),
			type("drop", "Drop", "inventory", "Drop the held item or stack", InventorySteps.Drop::new),
			type("swap_hands", "Swap Hands", "inventory", "Swap main and offhand (the F key)", InventorySteps.SwapHands::new),
			type("open_inventory", "Open Inventory", "inventory", "Open your inventory screen", InventorySteps.OpenInventory::new),

			type("use_item", "Use Item", "interact", "Right click with an item, or hold it", InteractSteps.UseItem::new),
			type("use_block", "Use Block", "interact", "Right click the block you're looking at", InteractSteps.UseBlock::new),
			type("attack", "Attack", "interact", "Hit whatever is under the crosshair", InteractSteps.Attack::new),
			type("swing", "Swing", "interact", "Swing an arm", InteractSteps.Swing::new),
			type("rotate", "Rotate", "interact", "Turn to a direction, or by an amount", InteractSteps.Rotate::new),
			type("look_at_player", "Look At Player", "interact", "Face the nearest player", InteractSteps.LookAtPlayer::new),

			type("sneak", "Sneak", "movement", "Tap, hold or let go of sneak", MovementSteps.Sneak::new),
			type("jump", "Jump", "movement", "Jump once", MovementSteps.Jump::new),
			type("sprint", "Sprint", "movement", "Start or stop sprinting", MovementSteps.Sprint::new),
			type("walk", "Walk", "movement", "Walk a direction for a while", MovementSteps.Walk::new),

			type("wait_gui", "Wait GUI", "wait", "Wait for a GUI to open or close", WaitSteps.Gui::new),
			type("wait_chat", "Wait Chat", "wait", "Wait for a chat line with some text", WaitSteps.Chat::new));

	private Steps() {
	}

	private static StepType type(String id, String name, String category, String description,
								 java.util.function.Supplier<MacroStep> factory) {
		return new StepType(id, name, category, description, factory);
	}

	public static List<StepType> all() {
		return ALL;
	}

	public static List<StepType> inCategory(String category) {
		List<StepType> out = new ArrayList<>();
		for (StepType t : ALL) {
			if (t.category().equals(category)) {
				out.add(t);
			}
		}
		return out;
	}

	public static StepType byId(String id) {
		for (StepType t : ALL) {
			if (t.id().equals(id)) {
				return t;
			}
		}
		return null;
	}
}
