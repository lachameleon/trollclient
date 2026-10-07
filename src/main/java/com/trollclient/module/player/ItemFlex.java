package com.trollclient.module.player;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.InventoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Flicks through your hotbar so everyone sees your held item strobe. */
public class ItemFlex extends Module {
	private final ModeSetting pattern = add(new ModeSetting("Pattern", "Order the slots are flashed in", "Cycle",
			"Cycle", "Bounce", "Random", "Flash"));
	private final NumberSetting interval = add(new NumberSetting("Interval", "Ticks between switches", 2, 1, 20, 1).unit("t"));
	private final BoolSetting skipEmpty = add(new BoolSetting("Skip Empty", "Only flash slots that hold something", true));
	private final BoolSetting pauseWhenBusy = add(new BoolSetting("Pause When Busy", "Stop while you use, attack or mine", true));
	private final BoolSetting restore = add(new BoolSetting("Restore Slot", "Go back to your slot when turned off", true));

	private int home = -1;
	private int ticks;
	private int step;
	private int dir = 1;
	private int lastSet = -1;

	public ItemFlex() {
		super("ItemFlex", "Rapidly cycles your hotbar so the item in your hand strobes for everyone.", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		home = mc.player != null ? InventoryUtil.selected() : -1;
		lastSet = -1;
		ticks = 0;
	}

	@Override
	protected void onDisable() {
		if (restore.get() && home >= 0 && mc.player != null) {
			InventoryUtil.select(home);
		}
	}

	@Override
	public void onTick() {
		int selected = InventoryUtil.selected();
		if (lastSet >= 0 && selected != lastSet) {
			// the player (or another module) picked a slot: make that the new home
			home = selected;
		}
		boolean busy = mc.player.isUsingItem() || mc.options.keyUse.isDown() || mc.options.keyAttack.isDown()
				|| mc.gui.screen() != null;
		if (pauseWhenBusy.get() && busy) {
			if (selected != home && home >= 0) {
				InventoryUtil.select(home);
			}
			lastSet = InventoryUtil.selected();
			return;
		}
		if (++ticks < interval.getInt()) {
			lastSet = selected;
			return;
		}
		ticks = 0;
		List<Integer> slots = slots();
		if (slots.size() < 2) {
			lastSet = selected;
			return;
		}
		int next = switch (pattern.get()) {
			case "Bounce" -> {
				step += dir;
				if (step >= slots.size() - 1 || step <= 0) {
					step = Math.max(0, Math.min(slots.size() - 1, step));
					dir = -dir;
				}
				yield slots.get(step);
			}
			case "Random" -> {
				int pick;
				do {
					pick = slots.get(ThreadLocalRandom.current().nextInt(slots.size()));
				} while (pick == selected);
				yield pick;
			}
			case "Flash" -> selected == home ? slots.get((slots.indexOf(home) + 1) % slots.size()) : home;
			default -> slots.get((step = (step + 1) % slots.size()));
		};
		InventoryUtil.select(next);
		lastSet = next;
	}

	private List<Integer> slots() {
		List<Integer> list = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			if (!skipEmpty.get() || !mc.player.getInventory().getItem(i).isEmpty() || i == home) {
				list.add(i);
			}
		}
		return list;
	}

	@Override
	public String getInfo() {
		return pattern.get();
	}
}
