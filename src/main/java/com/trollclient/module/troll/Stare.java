package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import com.trollclient.util.Targets;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/** Locks eyes with a player and won't let go. Or nods along, shakes its head, looks them up and down... */
public class Stare extends Module {
	private final TextSetting target = add(new TextSetting("Target", "Player to stare at (blank = nearest)", "", 16)
			.suggestions(Targets::onlineNames).placeholder("nearest"));
	private final NumberSetting range = add(new NumberSetting("Range", "Only stare at players this close", 24, 4, 64, 1).unit("m"));
	private final ModeSetting mode = add(new ModeSetting("Mode", "What your head does while you stare", "Stare",
			"Stare", "Nod", "Shake", "Judge", "Side Eye", "Shy"));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast nods, shakes and glances go", 1.0, 0.25, 3.0, 0.05)
			.unit("x")).visibleWhen(() -> !mode.is("Stare") && !mode.is("Shy"));
	private final BoolSetting crouch = add(new BoolSetting("Crouch", "Crouch the whole time, for extra menace", false));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't pick friends as the nearest target", false));

	private Player current;
	private int ticks;
	/** Which way Side Eye looks away: 1 or -1, picked when it turns on. */
	private int side = 1;
	private boolean watched;

	public Stare() {
		super("Stare", "Stares a player down without blinking. Can nod, shake its head, side-eye, or get shy when they look back.",
				Category.TROLL);
	}

	@Override
	protected void onEnable() {
		ticks = 0;
		side = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
	}

	@Override
	public void onWorldLeave() {
		current = null;
	}

	@Override
	public void onTick() {
		current = Targets.resolve(target.get(), range.get(), ignoreFriends.get());
		watched = false;
		if (current == null) {
			return;
		}
		ticks++;
		float[] eyes = Rotations.lookAt(current.getEyePosition());
		float yaw = eyes[0];
		float pitch = eyes[1];
		float t = ticks * speed.getFloat();
		switch (mode.get()) {
			case "Nod" -> pitch += Mth.sin(t * 0.55f) * 22f;
			case "Shake" -> yaw += Mth.sin(t * 0.6f) * 28f;
			case "Judge" -> {
				// eyes, then slowly all the way down to their shoes and back up again
				float feet = Rotations.lookAt(current.position())[1];
				pitch = Mth.lerp(0.5f - 0.5f * Mth.cos(t * 0.06f), eyes[1], feet);
				yaw += Mth.sin(t * 0.13f) * 4f;
			}
			case "Side Eye" -> {
				// looking pointedly away, with a quick glance over every couple of seconds
				int cycle = Math.max(20, Math.round(50 / speed.getFloat()));
				if (ticks % cycle >= 8) {
					yaw += 65f * side;
					pitch = 8f;
				}
			}
			case "Shy" -> {
				watched = isWatchingMe(current);
				if (watched) {
					yaw += 160f;
					pitch = 50f;
				}
			}
			default -> {
			}
		}
		Rotations.request(Mth.wrapDegrees(yaw), pitch, 3, true);
		if (crouch.get()) {
			MovementControl.sneak(MovementControl.PRIORITY_SIGNAL, true);
		}
	}

	/** Their head is pointed within about 20 degrees of us. */
	private boolean isWatchingMe(Player p) {
		Vec3 look = Vec3.directionFromRotation(p.getXRot(), p.getYHeadRot());
		Vec3 toMe = mc.player.getEyePosition().subtract(p.getEyePosition());
		return toMe.lengthSqr() > 1e-6 && look.dot(toMe.normalize()) > 0.94;
	}

	@Override
	public Player getTarget() {
		return current;
	}

	@Override
	public String getInfo() {
		if (current == null) {
			return mode.get();
		}
		return Targets.name(current) + (watched ? " (eek)" : "");
	}
}
