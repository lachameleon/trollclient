package com.trollclient.module.player;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.InventoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;

/**
 * Tosses whatever you're holding back and forth between your hands (the F
 * key, very fast), so everyone sees you juggling. Always ends with both
 * hands holding what they started with.
 */
public class Juggle extends Module {
	private final NumberSetting interval = add(new NumberSetting("Interval", "Ticks between tosses", 4, 1, 20, 1).unit("t"));
	private final BoolSetting swing = add(new BoolSetting("Swing", "Swing the hand that catches it", true));
	private final BoolSetting pauseWhenBusy = add(new BoolSetting("Pause When Busy", "Stop while you use, attack or mine", true));

	/** True while the items are in each other's hands; {@link #slot} is the hotbar slot they were swapped with. */
	private boolean swapped;
	private int slot = -1;
	private int ticks;
	private int tosses;

	public Juggle() {
		super("Juggle", "Tosses your held item back and forth between your hands so everyone sees you juggling.", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		swapped = false;
		ticks = 0;
		tosses = 0;
	}

	@Override
	protected void onDisable() {
		if (swapped && mc.player != null && mc.getConnection() != null) {
			toss();
		}
		swapped = false;
	}

	@Override
	public void onWorldLeave() {
		swapped = false;
	}

	@Override
	public void onTick() {
		boolean busy = mc.player.isUsingItem() || mc.options.keyUse.isDown() || mc.options.keyAttack.isDown() || mc.gui.screen() != null;
		if (pauseWhenBusy.get() && busy) {
			// put things back while you need them
			if (swapped) {
				toss();
			}
			return;
		}
		if (mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().isEmpty() && !swapped) {
			return;
		}
		if (++ticks < interval.getInt()) {
			return;
		}
		ticks = 0;
		toss();
		if (swing.get()) {
			mc.player.swing(swapped ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		}
	}

	/**
	 * One swap with the offhand, always against the slot the juggling started
	 * on: if you scrolled away meanwhile, that slot is picked for a moment so
	 * the items land back where they came from.
	 */
	private void toss() {
		int current = InventoryUtil.selected();
		int target = swapped ? slot : current;
		if (target != current) {
			InventoryUtil.select(target);
		}
		// the swap packet doesn't carry the slot; make sure the server has the right one first
		InventoryUtil.syncSelected();
		mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
				BlockPos.ZERO, Direction.DOWN));
		if (target != current) {
			InventoryUtil.select(current);
		}
		slot = target;
		swapped = !swapped;
		tosses++;
	}

	@Override
	public String getInfo() {
		return tosses > 0 ? Integer.toString(tosses / 2) : null;
	}
}
