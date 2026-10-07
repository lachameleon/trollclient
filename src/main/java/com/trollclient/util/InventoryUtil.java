package com.trollclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

public final class InventoryUtil {
	/** Rough "how sad would I be to lose this" score. Lower is cheaper. */
	private static final Map<Item, Integer> COSTS = new HashMap<>();

	static {
		cost(1, Items.DIRT, Items.COARSE_DIRT, Items.NETHERRACK, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
				Items.ROOTED_DIRT, Items.MUD, Items.DIORITE, Items.ANDESITE, Items.GRANITE, Items.TUFF,
				Items.CALCITE, Items.BASALT, Items.SMOOTH_BASALT, Items.BLACKSTONE, Items.DRIPSTONE_BLOCK);
		cost(2, Items.STONE, Items.DEEPSLATE, Items.END_STONE, Items.MOSSY_COBBLESTONE, Items.SANDSTONE,
				Items.RED_SANDSTONE, Items.TERRACOTTA, Items.PACKED_MUD, Items.MUD_BRICKS, Items.CLAY,
				Items.SOUL_SOIL, Items.MOSS_BLOCK, Items.GRASS_BLOCK, Items.PODZOL, Items.MYCELIUM);
		cost(3, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
				Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS,
				Items.CRIMSON_PLANKS, Items.WARPED_PLANKS, Items.STONE_BRICKS, Items.BRICKS, Items.GLASS,
				Items.NETHER_BRICKS, Items.DEEPSLATE_BRICKS, Items.POLISHED_ANDESITE, Items.POLISHED_DIORITE,
				Items.POLISHED_GRANITE, Items.SMOOTH_STONE);
		cost(5, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG,
				Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG, Items.CRIMSON_STEM, Items.WARPED_STEM,
				Items.QUARTZ_BLOCK, Items.PRISMARINE, Items.PURPUR_BLOCK, Items.HAY_BLOCK);
		cost(20, Items.OBSIDIAN);
		cost(25, Items.CRYING_OBSIDIAN, Items.RESPAWN_ANCHOR);
		cost(60, Items.ENDER_CHEST, Items.IRON_BLOCK, Items.REDSTONE_BLOCK, Items.LAPIS_BLOCK,
				Items.COAL_BLOCK);
		cost(250, Items.GOLD_BLOCK, Items.EMERALD_BLOCK, Items.DIAMOND_BLOCK, Items.ANCIENT_DEBRIS, Items.BEACON,
				Items.ENCHANTING_TABLE);
		cost(1000, Items.NETHERITE_BLOCK, Items.TNT, Items.SPAWNER, Items.DRAGON_EGG, Items.BEDROCK,
				Items.COMMAND_BLOCK, Items.BARRIER);
	}

	private static void cost(int value, Item... items) {
		for (Item item : items) {
			COSTS.put(item, value);
		}
	}

	private InventoryUtil() {
	}

	/** Cost of placing this stack as a full, solid block, or -1 if unusable. */
	public static int blockCost(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
			return -1;
		}
		Block block = blockItem.getBlock();
		BlockState state = block.defaultBlockState();
		if (!Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) {
			return -1;
		}
		Integer known = COSTS.get(stack.getItem());
		if (known != null) {
			return known >= 1000 ? -1 : known;
		}
		int cost = 8;
		if (state.hasBlockEntity()) {
			cost += 200;
		}
		if (block instanceof FallingBlock) {
			cost += 40; // sand and gravel slide away when unsupported
		}
		if (block.getExplosionResistance() >= 100) {
			cost += 15;
		}
		return cost;
	}

	/** Hotbar slot holding the cheapest full block, or -1. */
	public static int cheapestBlockSlot() {
		LocalPlayer p = Minecraft.getInstance().player;
		int best = -1;
		int bestCost = Integer.MAX_VALUE;
		for (int i = 0; i < 9; i++) {
			int c = blockCost(p.getInventory().getItem(i));
			if (c >= 0 && c < bestCost) {
				bestCost = c;
				best = i;
			}
		}
		return best;
	}

	public static int findHotbar(Predicate<ItemStack> predicate) {
		LocalPlayer p = Minecraft.getInstance().player;
		for (int i = 0; i < 9; i++) {
			if (predicate.test(p.getInventory().getItem(i))) {
				return i;
			}
		}
		return -1;
	}

	public static int findHotbar(Item item) {
		return findHotbar(s -> s.is(item));
	}

	public static int selected() {
		return Minecraft.getInstance().player.getInventory().getSelectedSlot();
	}

	/** Client-side slot switch; MultiPlayerGameMode syncs it with the server before any use. */
	public static void select(int slot) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (slot >= 0 && slot < 9 && p.getInventory().getSelectedSlot() != slot) {
			p.getInventory().setSelectedSlot(slot);
		}
	}

	/** Tells the server about a slot switch right now (needed before dropping items). */
	public static void syncSelected() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode != null) {
			((com.trollclient.mixin.MultiPlayerGameModeAccessor) mc.gameMode).troll$syncCarriedItem();
		}
	}

	public static InteractionHand handHolding(Predicate<ItemStack> predicate) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (predicate.test(p.getMainHandItem())) {
			return InteractionHand.MAIN_HAND;
		}
		if (predicate.test(p.getOffhandItem())) {
			return InteractionHand.OFF_HAND;
		}
		return null;
	}
}
