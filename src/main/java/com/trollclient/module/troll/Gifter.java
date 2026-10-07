package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.util.Delay;
import com.trollclient.util.InventoryUtil;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/** Showers nearby players in presents nobody asked for. */
public class Gifter extends Module {
	private static final Set<Item> JUNK = Set.of(
			Items.DIRT, Items.COARSE_DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK, Items.GRAVEL,
			Items.SAND, Items.RED_SAND, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.CALCITE,
			Items.ROTTEN_FLESH, Items.POISONOUS_POTATO, Items.SPIDER_EYE, Items.BONE, Items.STRING, Items.STICK,
			Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.KELP, Items.DEAD_BUSH,
			Items.FEATHER, Items.LEATHER, Items.EGG, Items.SNOWBALL, Items.FLINT, Items.CLAY_BALL, Items.PAPER);

	private final NumberSetting range = add(new NumberSetting("Range", "Throw at players this close (items don't fly far)", 4, 1.5, 8, 0.5)
			.unit("m"));
	private final ModeSetting items = add(new ModeSetting("Items", "What gets thrown", "Junk", "Junk", "Cheapest Block", "Held Item"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between gifts", 400, 50, 3000, 50).unit("ms"));
	private final NumberSetting keep = add(new NumberSetting("Keep", "Never throw a stack below this many", 0, 0, 32, 1))
			.visibleWhen(() -> !items.is("Junk"));
	private final BoolSetting requireSight = add(new BoolSetting("Line Of Sight", "Only throw when nothing is in the way", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Friends get real presents instead", true));

	private final Delay timer = new Delay();
	private int gifts;

	public Gifter() {
		super("Gifter", "Throws junk from your hotbar at every nearby player. You're welcome.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		gifts = 0;
	}

	@Override
	public void onTick() {
		if (mc.gui.screen() != null || mc.player.isUsingItem() || !timer.passed(delay.getInt())) {
			return;
		}
		Player target = pickTarget();
		if (target == null) {
			return;
		}
		int slot = pickSlot();
		if (slot < 0) {
			return;
		}
		// items leave your hand slowly: aim a little higher the further away they are
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 chest = target.position().add(0, target.getBbHeight() * 0.6, 0);
		float[] look = Rotations.lookAt(eyes, chest);
		double dist = Math.hypot(chest.x - eyes.x, chest.z - eyes.z);
		float pitch = Mth.clamp(look[1] - (float) dist * 6f, -45f, 60f);
		Rotations.request(look[0], pitch, 4, true);
		// the server throws along the look it already has: turn first, drop on a later tick
		if (!Rotations.isFacing(look[0], pitch, 4f)) {
			return;
		}

		int previous = InventoryUtil.selected();
		InventoryUtil.select(slot);
		InventoryUtil.syncSelected();

		if (mc.player.drop(false)) {
			mc.player.swing(InteractionHand.MAIN_HAND);
			gifts++;
		}
		if (swapBack.get()) {
			InventoryUtil.select(previous);
		}
		timer.reset();
	}

	private Player pickTarget() {
		List<Player> near = Targets.playersWithin(range.get(), ignoreFriends.get());
		for (Player p : near) {
			if (!requireSight.get() || visible(p)) {
				return p;
			}
		}
		return null;
	}

	private boolean visible(Player p) {
		Vec3 from = mc.player.getEyePosition();
		Vec3 to = p.getEyePosition();
		return mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player))
				.getType() == HitResult.Type.MISS;
	}

	private int pickSlot() {
		return switch (items.get()) {
			case "Cheapest Block" -> {
				int slot = InventoryUtil.cheapestBlockSlot();
				yield slot >= 0 && enough(mc.player.getInventory().getItem(slot)) ? slot : -1;
			}
			case "Held Item" -> {
				ItemStack held = mc.player.getMainHandItem();
				yield !held.isEmpty() && enough(held) ? InventoryUtil.selected() : -1;
			}
			default -> InventoryUtil.findHotbar(s -> !s.isEmpty() && JUNK.contains(s.getItem()));
		};
	}

	private boolean enough(ItemStack stack) {
		return stack.getCount() > keep.getInt();
	}

	@Override
	public String getInfo() {
		return gifts > 0 ? Integer.toString(gifts) : items.get().toLowerCase(java.util.Locale.ROOT);
	}
}
