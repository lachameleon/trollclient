package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.InventoryUtil;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Blows a goat horn at people, like a clown car. Everyone within a couple hundred blocks hears it. */
public class Honk extends Module {
	private final ModeSetting trigger = add(new ModeSetting("Trigger", "Honk when someone walks up, whenever anyone's near, or nonstop",
			"Arrivals", "Arrivals", "Nearby", "Nonstop"));
	private final NumberSetting range = add(new NumberSetting("Range", "How close counts as near", 10, 2, 64, 1).unit("m"))
			.visibleWhen(() -> !trigger.is("Nonstop"));
	private final NumberSetting hold = add(new NumberSetting("Hold", "How long to keep the horn up to your mouth", 20, 2, 60, 1).unit("t"));
	private final BoolSetting face = add(new BoolSetting("Face Them", "Point the horn at whoever you're honking at", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't honk at friends", false));

	/** Players inside the range last tick, so we notice new arrivals. */
	private final Set<UUID> near = new HashSet<>();
	private final Deque<UUID> arrivals = new ArrayDeque<>();
	private Player honkingAt;
	private InteractionHand hand;
	private int previousSlot = -1;
	private int heldFor;
	private boolean honking;
	private boolean holdingUse;
	private int honks;

	public Honk() {
		super("Honk", "Blows a goat horn at players who walk up to you (or nonstop). Heard for miles.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		near.clear();
		arrivals.clear();
		honks = 0;
	}

	@Override
	protected void onDisable() {
		stopHonk();
	}

	@Override
	public void onWorldLeave() {
		holdingUse = false;
		honking = false;
		near.clear();
		arrivals.clear();
	}

	@Override
	public void onTick() {
		trackArrivals();
		if (honking) {
			tickHonk();
			return;
		}
		if (mc.gui.screen() != null || mc.player.isUsingItem() || mc.options.keyUse.isDown()) {
			return;
		}
		Player at = trigger.is("Arrivals") ? nextArrival() : nearest();
		if (at == null && !trigger.is("Nonstop")) {
			return;
		}
		if (startHonk(at) && trigger.is("Arrivals")) {
			arrivals.poll();
		}
	}

	private void trackArrivals() {
		Set<UUID> now = new HashSet<>();
		for (Player p : Targets.playersWithin(range.get(), ignoreFriends.get())) {
			now.add(p.getUUID());
			if (!near.contains(p.getUUID()) && arrivals.size() < 3 && !arrivals.contains(p.getUUID())) {
				arrivals.add(p.getUUID());
			}
		}
		near.clear();
		near.addAll(now);
	}

	/** Oldest arrival still in range; anyone who already left is dropped. */
	private Player nextArrival() {
		while (!arrivals.isEmpty()) {
			Player p = mc.level.getPlayerByUUID(arrivals.peek());
			if (p != null && near.contains(p.getUUID())) {
				return p;
			}
			arrivals.poll();
		}
		return null;
	}

	private Player nearest() {
		List<Player> list = Targets.playersWithin(trigger.is("Nonstop") ? 64 : range.get(), ignoreFriends.get());
		return list.isEmpty() ? null : list.get(0);
	}

	private boolean ready(ItemStack stack) {
		return stack.is(Items.GOAT_HORN) && !mc.player.getCooldowns().isOnCooldown(stack);
	}

	private boolean startHonk(Player at) {
		InteractionHand found = null;
		int slot = -1;
		if (ready(mc.player.getOffhandItem())) {
			found = InteractionHand.OFF_HAND;
		} else {
			slot = InventoryUtil.findHotbar(this::ready);
			if (slot >= 0) {
				found = InteractionHand.MAIN_HAND;
			}
		}
		if (found == null) {
			return false;
		}
		previousSlot = InventoryUtil.selected();
		if (found == InteractionHand.MAIN_HAND) {
			InventoryUtil.select(slot);
		}
		hand = found;
		if (!mc.gameMode.useItem(mc.player, hand).consumesAction()) {
			restoreSlot();
			return false;
		}
		// vanilla stops using an item the moment the use key isn't held
		mc.options.keyUse.setDown(true);
		holdingUse = true;
		honking = true;
		heldFor = 0;
		honkingAt = at;
		honks++;
		return true;
	}

	private void tickHonk() {
		if (face.get() && honkingAt != null && honkingAt.isAlive()) {
			float[] look = Rotations.lookAt(honkingAt.getEyePosition());
			Rotations.request(look[0], look[1], 4, true);
		}
		if (++heldFor >= hold.getInt() || !mc.player.isUsingItem()) {
			stopHonk();
		} else {
			mc.options.keyUse.setDown(true);
		}
	}

	private void stopHonk() {
		if (holdingUse) {
			mc.options.keyUse.setDown(false);
			holdingUse = false;
		}
		if (honking && mc.player != null && mc.player.isUsingItem() && mc.gameMode != null) {
			mc.gameMode.releaseUsingItem(mc.player);
		}
		if (honking) {
			restoreSlot();
		}
		honking = false;
		honkingAt = null;
	}

	private void restoreSlot() {
		if (swapBack.get() && previousSlot >= 0 && hand == InteractionHand.MAIN_HAND && mc.player != null) {
			InventoryUtil.select(previousSlot);
		}
		previousSlot = -1;
	}

	@Override
	public Player getTarget() {
		return honkingAt;
	}

	@Override
	public String getInfo() {
		return honks > 0 ? Integer.toString(honks) : trigger.get();
	}
}
