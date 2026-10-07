package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.InventoryUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;

import java.util.concurrent.ThreadLocalRandom;

public class FoodAnnoy extends Module {
	private final NumberSetting minBite = add(new NumberSetting("Min Bite", "Shortest nibble", 6, 2, 30, 1).unit("t"));
	private final NumberSetting maxBite = add(new NumberSetting("Max Bite", "Longest nibble (always stops before the last bite)", 22, 2, 30, 1)
			.unit("t"));
	private final NumberSetting pause = add(new NumberSetting("Pause", "Ticks between nibbles", 3, 0, 40, 1).unit("t"));
	private final BoolSetting drinks = add(new BoolSetting("Drinks Too", "Also sip potions, milk and honey", false));
	private final BoolSetting preferOffhand = add(new BoolSetting("Prefer Offhand", "Use food in your offhand first", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot after each nibble", true));
	private final BoolSetting onlyIdle = add(new BoolSetting("Only When Idle", "Pause while you're using or attacking", true));

	private enum State { IDLE, EATING, PAUSED }

	private State state = State.IDLE;
	private int ticks;
	private int biteLength;
	private int previousSlot = -1;
	private InteractionHand hand;
	private boolean holdingUse;
	private int nibbles;

	public FoodAnnoy() {
		super("FoodAnnoy", "Constantly starts eating but never finishes, just for the noise.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		state = State.IDLE;
		nibbles = 0;
	}

	@Override
	protected void onDisable() {
		stopEating();
	}

	@Override
	public void onWorldLeave() {
		holdingUse = false;
		state = State.IDLE;
	}

	@Override
	public void onTick() {
		switch (state) {
			case IDLE -> tryStart();
			case EATING -> tickEating();
			case PAUSED -> {
				if (++ticks >= pause.getInt()) {
					state = State.IDLE;
				}
			}
		}
	}

	private void tryStart() {
		if (onlyIdle.get() && (mc.player.isUsingItem() || mc.options.keyUse.isDown() || mc.options.keyAttack.isDown())) {
			return;
		}
		if (mc.gui.screen() != null) {
			return;
		}
		InteractionHand found = null;
		int slot = -1;
		if (preferOffhand.get() && canNibble(mc.player.getOffhandItem())) {
			found = InteractionHand.OFF_HAND;
		} else if (canNibble(mc.player.getMainHandItem())) {
			found = InteractionHand.MAIN_HAND;
			slot = InventoryUtil.selected();
		} else {
			slot = InventoryUtil.findHotbar(this::canNibble);
			if (slot >= 0) {
				found = InteractionHand.MAIN_HAND;
			} else if (canNibble(mc.player.getOffhandItem())) {
				found = InteractionHand.OFF_HAND;
			}
		}
		if (found == null) {
			return;
		}
		previousSlot = InventoryUtil.selected();
		if (found == InteractionHand.MAIN_HAND) {
			InventoryUtil.select(slot);
		}
		hand = found;
		ItemStack stack = mc.player.getItemInHand(hand);
		int full = stack.getUseDuration(mc.player);
		int lo = Math.min(minBite.getInt(), maxBite.getInt());
		int hi = Math.max(minBite.getInt(), maxBite.getInt());
		biteLength = Math.min(lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1), full - 3);
		if (biteLength < 1) {
			restoreSlot();
			return;
		}
		if (mc.gameMode.useItem(mc.player, hand).consumesAction()) {
			// vanilla cancels item use the moment the use key isn't held
			mc.options.keyUse.setDown(true);
			holdingUse = true;
			ticks = 0;
			state = State.EATING;
		} else {
			restoreSlot();
		}
	}

	private void tickEating() {
		ticks++;
		boolean stillUsing = mc.player.isUsingItem();
		// hard stop a few ticks before the item would be consumed
		boolean nearlyDone = stillUsing && mc.player.getUseItemRemainingTicks() <= 3;
		if (!stillUsing || ticks >= biteLength || nearlyDone) {
			stopEating();
			nibbles++;
			ticks = 0;
			state = State.PAUSED;
		} else {
			mc.options.keyUse.setDown(true);
		}
	}

	private void stopEating() {
		if (holdingUse) {
			mc.options.keyUse.setDown(false);
			holdingUse = false;
		}
		if (mc.player != null && mc.player.isUsingItem() && mc.gameMode != null) {
			mc.gameMode.releaseUsingItem(mc.player);
		}
		restoreSlot();
	}

	private void restoreSlot() {
		if (swapBack.get() && previousSlot >= 0 && hand == InteractionHand.MAIN_HAND && mc.player != null) {
			InventoryUtil.select(previousSlot);
		}
		previousSlot = -1;
	}

	private boolean canNibble(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable == null || consumable.consumeTicks() < 8) {
			return false;
		}
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food != null) {
			return mc.player.canEat(food.canAlwaysEat());
		}
		return drinks.get() && stack.getUseAnimation() == ItemUseAnimation.DRINK;
	}

	@Override
	public String getInfo() {
		return nibbles > 0 ? Integer.toString(nibbles) : null;
	}
}
