package com.trollclient.macro.steps;

import com.trollclient.macro.MacroRun;
import com.trollclient.macro.MacroStep;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.InventoryUtil;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.Locale;

/** Clicking slots in whatever GUI is open, plus the hotbar, dropping and swapping hands. */
public final class InventorySteps {
	private InventorySteps() {
	}

	public static final class ClickSlot extends MacroStep {
		private final NumberSetting slot = add(new NumberSetting("Slot",
				"Slot number in the open GUI (the GUI tools panel shows the one under your mouse)", 0, 0, 99, 1));
		private final ModeSetting mode = add(new ModeSetting("Mode", "What kind of click", "Pickup",
				"Pickup", "Quick Move", "Swap", "Throw", "Clone", "Pickup All"));
		private final NumberSetting button = add(new NumberSetting("Button",
				"0 = left, 1 = right. Swap: hotbar slot 0-8, 40 = offhand. Throw: 0 = one, 1 = the stack", 0, 0, 40, 1));

		@Override
		public Result tick(MacroRun run) {
			AbstractContainerMenu menu = mc.player.containerMenu;
			if (slot.getInt() >= menu.slots.size()) {
				run.fail("slot " + slot.getInt() + " isn't in this GUI (it has " + menu.slots.size() + ")");
				return Result.STOP;
			}
			ContainerInput input = switch (mode.get()) {
				case "Quick Move" -> ContainerInput.QUICK_MOVE;
				case "Swap" -> ContainerInput.SWAP;
				case "Throw" -> ContainerInput.THROW;
				case "Clone" -> ContainerInput.CLONE;
				case "Pickup All" -> ContainerInput.PICKUP_ALL;
				default -> ContainerInput.PICKUP;
			};
			// the click packet goes through the GUI tools' gate like a real click
			mc.gameMode.handleContainerInput(menu.containerId, slot.getInt(), button.getInt(), input, mc.player);
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "slot " + slot.getInt() + " " + mode.get().toLowerCase(Locale.ROOT) + (button.getInt() > 0 ? " " + button.getInt() : "");
		}
	}

	public static final class SelectSlot extends MacroStep {
		private final ModeSetting by = add(new ModeSetting("By", "Pick a hotbar slot by number or by what's in it", "Number", "Number", "Item"));
		private final NumberSetting slot = add(new NumberSetting("Slot", "Hotbar slot", 1, 1, 9, 1))
				.visibleWhen(() -> by.is("Number"));
		private final TextSetting item = add(new TextSetting("Item", "Part of the item's name, e.g. sword", "", 32).placeholder("item name"))
				.visibleWhen(() -> by.is("Item"));

		@Override
		public Result tick(MacroRun run) {
			int target = slot.getInt() - 1;
			if (by.is("Item")) {
				String needle = item.get().toLowerCase(Locale.ROOT).trim();
				target = InventoryUtil.findHotbar(s -> !s.isEmpty() && !needle.isEmpty()
						&& s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle));
				if (target < 0) {
					run.fail("no \"" + item.get() + "\" in the hotbar");
					return Result.STOP;
				}
			}
			InventoryUtil.select(target);
			InventoryUtil.syncSelected();
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return by.is("Number") ? "slot " + slot.getInt() : "\"" + item.get() + "\"";
		}
	}

	public static final class Drop extends MacroStep {
		private final ModeSetting amount = add(new ModeSetting("Drop", "One item from your hand, or the whole stack", "One", "One", "Stack"));

		@Override
		public Result tick(MacroRun run) {
			InventoryUtil.syncSelected();
			if (mc.player.drop(amount.is("Stack"))) {
				mc.player.swing(InteractionHand.MAIN_HAND);
			}
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return amount.get().toLowerCase(Locale.ROOT);
		}
	}

	public static final class SwapHands extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			InventoryUtil.syncSelected();
			mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
					BlockPos.ZERO, Direction.DOWN));
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "";
		}
	}

	public static final class OpenInventory extends MacroStep {
		@Override
		public Result tick(MacroRun run) {
			mc.gui.setScreen(new InventoryScreen(mc.player));
			return Result.NEXT;
		}

		@Override
		public String summary() {
			return "";
		}
	}
}
