package com.trollclient.module.combat;

import com.trollclient.command.Commands;
import com.trollclient.gui.Icon;
import com.trollclient.gui.hud.Notifications;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.Setting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Friends;
import com.trollclient.util.Targets;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Hit me and you'll regret it, mildly: whoever hurt you last becomes the
 * target of every module with a Target setting (Orbit, Stalker, Mimic...),
 * until the grudge wears off or they die.
 */
public class Grudge extends Module {
	private static final byte DEATH = 3;
	private static final long ANNOUNCE_GAP_MS = 5000;

	private final BoolSetting retarget = add(new BoolSetting("Retarget", "Point every module with a Target setting at them", true));
	private final ModeSetting payback = add(new ModeSetting("Payback", "Also switch this module on while the grudge lasts",
			"None", "None", "Orbit", "Stalker", "Mimic", "Goalie", "PlayerFollow", "Trapper", "Parrot"));
	private final NumberSetting forget = add(new NumberSetting("Forget After", "Let it go after this long (0 = never)", 60, 0, 600, 5)
			.unit("s"));
	private final BoolSetting settle = add(new BoolSetting("Settle On Death", "Let it go once they die", true));
	private final ModeSetting announce = add(new ModeSetting("Announce", "Tell them (Public), just you (Client), or nobody",
			"Client", "Off", "Client", "Public"));
	private final TextSetting message = add(new TextSetting("Message", "Options split with |. {player} is who hit you",
			"noted, {player} | {player} has been added to the list | you'll regret that, {player} | i will remember this, {player}", 256))
			.visibleWhen(() -> !announce.is("Off"));
	private final BoolSetting projectiles = add(new BoolSetting("Projectiles", "Arrows, tridents and thrown things count too", true));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Friendly fire is forgiven", true));

	/** Name of whoever we're mad at, or null. */
	private String holding;
	private long lastHit;
	private long lastAnnounce;
	/** What each module's Target was before we changed it, so letting go puts it back. */
	private final Map<Module, String> previousTargets = new HashMap<>();
	private Module switchedOn;
	private int grudges;

	public Grudge() {
		super("Grudge", "Whoever hits you becomes the target of every module with a Target, until it wears off.", Category.COMBAT);
	}

	@Override
	protected void onDisable() {
		letGo();
	}

	@Override
	public void onWorldLeave() {
		letGo();
	}

	@Override
	public void onDamage(Entity victim, DamageSource source) {
		if (victim != mc.player || !(source.getEntity() instanceof Player attacker) || attacker == mc.player) {
			return;
		}
		if (!projectiles.get() && source.getDirectEntity() != attacker) {
			return;
		}
		if (ignoreFriends.get() && Friends.isFriend(attacker)) {
			return;
		}
		String name = Targets.name(attacker);
		lastHit = System.currentTimeMillis();
		if (name.equalsIgnoreCase(holding)) {
			return; // still mad at them; the hit just resets the clock
		}
		letGo();
		holding = name;
		grudges++;
		if (retarget.get()) {
			for (Module m : ModuleManager.all()) {
				if (m != this && m.getSetting("Target") instanceof TextSetting target) {
					previousTargets.put(m, target.get());
					target.set(name);
				}
			}
		}
		Module pay = payback.is("None") ? null : ModuleManager.byName(payback.get());
		if (pay != null && !pay.isEnabled()) {
			pay.setEnabled(true);
			switchedOn = pay;
		}
		Notifications.post("Grudge", "against " + name, Icon.SKULL, true);
		String line = ChatUtil.format(ChatUtil.pick(message.get()), name, 1);
		if (announce.is("Client")) {
			Commands.info(line);
		} else if (announce.is("Public") && lastHit - lastAnnounce >= ANNOUNCE_GAP_MS) {
			// two players taking turns hitting you shouldn't turn into chat spam
			lastAnnounce = lastHit;
			ChatUtil.send(line);
		}
	}

	@Override
	public void onEntityEvent(Entity entity, byte event) {
		if (settle.get() && event == DEATH && entity instanceof Player p && Targets.name(p).equalsIgnoreCase(holding)) {
			letGo();
		}
	}

	@Override
	public void onTick() {
		if (holding != null && forget.getInt() > 0 && System.currentTimeMillis() - lastHit > forget.get() * 1000) {
			letGo();
		}
	}

	/** Forgives and forgets: targets go back to what they were and the payback module switches off. */
	private void letGo() {
		if (holding == null) {
			return;
		}
		for (Map.Entry<Module, String> e : previousTargets.entrySet()) {
			Setting<?> s = e.getKey().getSetting("Target");
			// leave it alone if the user picked someone else in the meantime
			if (s instanceof TextSetting target && target.get().equalsIgnoreCase(holding)) {
				target.set(e.getValue());
			}
		}
		previousTargets.clear();
		if (switchedOn != null) {
			switchedOn.setEnabled(false);
			switchedOn = null;
		}
		holding = null;
	}

	/** Who we're holding a grudge against, if anyone. */
	public String holding() {
		return holding;
	}

	@Override
	public Player getTarget() {
		return holding == null ? null : Targets.byName(holding);
	}

	@Override
	public String getInfo() {
		return holding != null ? holding : grudges > 0 ? Integer.toString(grudges) : null;
	}
}
