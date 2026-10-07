package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.BlockPlacer;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Delay;
import com.trollclient.util.InventoryUtil;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Leaves signs with your messages where players will read them. A standing
 * sign faces whoever placed it, so it goes somewhere the reader is on our
 * side of; the server's sign editor is answered straight away and never shows.
 */
public class Graffiti extends Module {
	/** Regular signs fit 90px of text per line (SignBlockEntity#getMaxTextLineWidth). */
	private static final int LINE_WIDTH = 90;
	private static final int LINES = 4;
	private static final long EDITOR_TIMEOUT = 3000;

	private final TextSetting messages = add(new TextSetting("Messages", "Options split with |, sign lines with /. {player} and {me} work",
			"{player}/was here | troll client/on top | look/behind/you | ez | {me}/wuz/here | this sign/intentionally/left blank", 256));
	private final ModeSetting where = add(new ModeSetting("Where", "Somewhere a nearby player will read it, or just in front of you",
			"Near Players", "Near Players", "In Front"));
	private final NumberSetting range = add(new NumberSetting("Range", "Leave notes for players this close", 10, 3, 32, 1).unit("m"))
			.visibleWhen(() -> where.is("Near Players"));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Time between signs", 5, 0.5, 60, 0.5).unit("s"));
	private final NumberSetting limit = add(new NumberSetting("Stop After", "Turn off after this many signs (0 = never)", 0, 0, 64, 1));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Return to your previous hotbar slot", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Friends get no fan mail", true));

	private final Delay timer = new Delay();
	/** Floor block we're lining up on, kept for a few ticks so a moving reader doesn't keep resetting the aim. */
	private BlockPos aim;
	private Player reader;
	private int aimTicks;
	/** The sign we just placed and the text it gets once the server asks for it. */
	private BlockPos pending;
	private String[] pendingText;
	private long pendingAt;
	private int signs;

	public Graffiti() {
		super("Graffiti", "Places signs with your messages right where nearby players will read them.", Category.TROLL);
	}

	@Override
	protected void onEnable() {
		aim = null;
		signs = 0;
	}

	@Override
	public void onWorldLeave() {
		aim = null;
		pending = null;
	}

	@Override
	public void onTick() {
		if (mc.gui.screen() != null || mc.player.isUsingItem() || waitingForEditor() || !timer.passed((long) (delay.get() * 1000))) {
			aim = null;
			return;
		}
		int slot = InventoryUtil.findHotbar(s -> s.getItem() instanceof SignItem && !(s.getItem() instanceof HangingSignItem));
		if (slot < 0) {
			aim = null;
			return;
		}
		if (aim == null || ++aimTicks > 15 || !stillGood(aim)) {
			aimTicks = 0;
			reader = where.is("Near Players") ? pickReader() : null;
			aim = where.is("Near Players") && reader == null ? null : findSpot(reader);
			if (aim == null) {
				return;
			}
		}
		Vec3 top = Vec3.atCenterOf(aim).add(0, 0.5, 0);
		float[] look = Rotations.lookAt(top);
		Rotations.request(look[0], look[1], 15, false);
		// the sign turns to face the way the server thinks we look: click only once it has us on that face
		BlockHitResult seen = Rotations.serverRayHit(aim, mc.level.getBlockState(aim).getShape(mc.level, aim), BlockPlacer.reach());
		if (seen == null || seen.getDirection() != Direction.UP) {
			return;
		}
		String[] text = compose(reader);
		int previous = InventoryUtil.selected();
		InventoryUtil.select(slot);
		if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, seen).consumesAction()) {
			mc.player.swing(InteractionHand.MAIN_HAND);
			pending = aim.above();
			pendingText = text;
			pendingAt = System.currentTimeMillis();
			signs++;
		}
		if (swapBack.get()) {
			InventoryUtil.select(previous);
		}
		timer.reset();
		aim = null;
		if (limit.getInt() > 0 && signs >= limit.getInt()) {
			setEnabled(false);
		}
	}

	private boolean waitingForEditor() {
		return pending != null && System.currentTimeMillis() - pendingAt <= EDITOR_TIMEOUT;
	}

	/**
	 * Mixin hook: the server wants text for a sign. If it's the one we just
	 * placed, write ours and skip the editor screen.
	 */
	public boolean claimEditor(BlockPos pos, boolean front) {
		if (!waitingForEditor() || !pending.equals(pos) || mc.getConnection() == null) {
			return false;
		}
		mc.getConnection().send(new ServerboundSignUpdatePacket(pos, front, pendingText[0], pendingText[1], pendingText[2], pendingText[3]));
		pending = null;
		return true;
	}

	private Player pickReader() {
		List<Player> near = Targets.playersWithin(range.get(), ignoreFriends.get());
		return near.isEmpty() ? null : near.get(0);
	}

	private boolean stillGood(BlockPos floor) {
		Vec3 top = Vec3.atCenterOf(floor).add(0, 0.5, 0);
		return BlockPlacer.isFree(floor.above()) && top.distanceTo(mc.player.getEyePosition()) <= BlockPlacer.reach() - 0.4
				&& (reader == null || reader.isAlive());
	}

	/**
	 * Floor block whose top gets the sign: in reach, room above it, and, with
	 * a reader, somewhere they're on our side of (so they see the front) and
	 * preferably in their view.
	 */
	private BlockPos findSpot(Player reader) {
		Vec3 eyes = mc.player.getEyePosition();
		double reach = BlockPlacer.reach() - 0.4;
		int r = (int) Math.ceil(reach);
		BlockPos centre = mc.player.blockPosition();
		Vec3 myLook = flat(Vec3.directionFromRotation(0, mc.player.getYRot()));
		BlockPos best = null;
		double bestScore = -Double.MAX_VALUE;
		for (BlockPos floor : BlockPos.betweenClosed(centre.offset(-r, -3, -r), centre.offset(r, 1, r))) {
			Vec3 top = Vec3.atCenterOf(floor).add(0, 0.5, 0);
			// a real click only lands on a top face seen from above
			if (top.distanceTo(eyes) > reach || eyes.y <= top.y + 0.05) {
				continue;
			}
			BlockState state = mc.level.getBlockState(floor);
			if (!state.isSolid() || BlockPlacer.isInteractable(state) || !BlockPlacer.isFree(floor.above())) {
				continue;
			}
			Vec3 sign = top.add(0, 0.5, 0);
			Vec3 toMe = flat(eyes.subtract(sign));
			if (toMe.length() < 1.2) {
				continue; // not underfoot
			}
			double score;
			if (reader == null) {
				// a couple of blocks out, straight ahead
				score = myLook.dot(toMe.normalize().scale(-1)) * 2 - Math.abs(toMe.length() - 2.5);
			} else {
				Vec3 toReader = flat(reader.getEyePosition().subtract(sign));
				double readerDist = toReader.length();
				if (readerDist < 1.2 || readerDist > 8) {
					continue;
				}
				// the sign faces us, so they can read it when they're roughly on our side of it
				double facing = toMe.normalize().dot(toReader.normalize());
				if (facing < 0.35) {
					continue;
				}
				Vec3 theirLook = flat(Vec3.directionFromRotation(0, reader.getYHeadRot()));
				double inView = theirLook.dot(toReader.normalize().scale(-1));
				score = facing * 2 + inView * 1.5 - readerDist * 0.3;
			}
			if (score > bestScore) {
				bestScore = score;
				best = floor.immutable();
			}
		}
		return best;
	}

	private static Vec3 flat(Vec3 v) {
		Vec3 f = new Vec3(v.x, 0, v.z);
		return f.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : f;
	}

	/** Four sign lines from one of the options: "/" breaks lines, long ones wrap, short notes sit in the middle. */
	private String[] compose(Player reader) {
		String who = reader != null ? Targets.name(reader) : nearestName();
		String option = ChatUtil.format(ChatUtil.pick(messages.get()), who, signs + 1);
		List<String> rows = new ArrayList<>();
		for (String part : option.split("/")) {
			rows.addAll(wrap(ChatUtil.sanitize(part)));
		}
		String[] out = {"", "", "", ""};
		int start = rows.size() >= LINES ? 0 : (LINES - rows.size()) / 2;
		for (int i = 0; i < rows.size() && start + i < LINES; i++) {
			out[start + i] = rows.get(i);
		}
		return out;
	}

	private String nearestName() {
		List<Player> near = Targets.playersWithin(64, false);
		return near.isEmpty() ? "you" : Targets.name(near.get(0));
	}

	/** Greedy word wrap to the sign's line width; words too long for a line get cut. */
	private List<String> wrap(String text) {
		List<String> rows = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (word.isEmpty()) {
				continue;
			}
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (mc.font.width(candidate) <= LINE_WIDTH) {
				line = new StringBuilder(candidate);
				continue;
			}
			if (!line.isEmpty()) {
				rows.add(line.toString());
			}
			line = new StringBuilder();
			for (char c : word.toCharArray()) {
				if (mc.font.width(line.toString() + c) > LINE_WIDTH) {
					rows.add(line.toString());
					line = new StringBuilder();
				}
				line.append(c);
			}
		}
		if (!line.isEmpty()) {
			rows.add(line.toString());
		}
		return rows;
	}

	@Override
	public Player getTarget() {
		return aim != null ? reader : null;
	}

	@Override
	public String getInfo() {
		return signs > 0 ? Integer.toString(signs) : null;
	}
}
