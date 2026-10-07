package com.trollclient.module.combat;

import com.trollclient.command.Commands;
import com.trollclient.gui.Icon;
import com.trollclient.gui.hud.Notifications;
import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Friends;
import com.trollclient.util.Targets;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;

/** Counts totem pops per player and makes sure everyone hears about it. */
public class PopCounter extends Module {
	private static final byte TOTEM_POP = 35;
	private static final byte DEATH = 3;

	private final NumberSetting range = add(new NumberSetting("Range", "Only count pops within this distance", 64, 8, 256, 8)
			.unit("m"));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Pop-up on the HUD for every pop", true));
	private final ModeSetting announce = add(new ModeSetting("Announce", "Tell the chat: nobody, just you, or everyone",
			"Client", "Off", "Client", "Public"));
	private final TextSetting popMessage = add(new TextSetting("Pop Message", "Sent on a pop. {player} {count} {s}, split options with |",
			"{player} popped {count} totem{s} | {player} is running low on totems ({count}) | pop #{count} for {player}", 256))
			.visibleWhen(() -> !announce.is("Off"));
	private final BoolSetting announceDeaths = add(new BoolSetting("Announce Deaths", "Also announce when a counted player dies", true))
			.visibleWhen(() -> !announce.is("Off"));
	private final TextSetting deathMessage = add(new TextSetting("Death Message", "Sent when they die after popping. Split options with |",
			"{player} died after popping {count} totem{s} | {count} totem{s} weren't enough for {player}", 256))
			.visibleWhen(() -> !announce.is("Off") && announceDeaths.get());
	private final BoolSetting countSelf = add(new BoolSetting("Count Self", "Track your own pops too", false));
	private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore Friends", "Don't announce friends' pops publicly", true));

	private final Map<String, Integer> pops = new HashMap<>();
	private int total;

	public PopCounter() {
		super("PopCounter", "Counts totem pops for every player nearby and announces them.", Category.COMBAT);
	}

	@Override
	protected void onEnable() {
		pops.clear();
		total = 0;
	}

	@Override
	public void onWorldLeave() {
		pops.clear();
	}

	@Override
	public void onEntityEvent(Entity entity, byte event) {
		if (!(entity instanceof Player player) || (event != TOTEM_POP && event != DEATH)) {
			return;
		}
		boolean self = player == mc.player;
		if ((self && !countSelf.get()) || mc.player == null || (!self && player.distanceTo(mc.player) > range.get())) {
			return;
		}
		String name = Targets.name(player);
		if (event == TOTEM_POP) {
			int count = pops.merge(name, 1, Integer::sum);
			total++;
			if (notify.get()) {
				Notifications.post(name, "popped " + count + " totem" + (count == 1 ? "" : "s"), Icon.STAR, true);
			}
			say(player, popMessage.get(), name, count);
		} else {
			Integer count = pops.remove(name);
			if (count != null && announceDeaths.get()) {
				if (notify.get()) {
					Notifications.post(name, "died after " + count + " pop" + (count == 1 ? "" : "s"), Icon.CLOSE, false);
				}
				say(player, deathMessage.get(), name, count);
			}
		}
	}

	private void say(Player player, String template, String name, int count) {
		String text = ChatUtil.format(ChatUtil.pick(template), name, count);
		switch (announce.get()) {
			case "Client" -> Commands.info(text);
			case "Public" -> {
				// never brag publicly about yourself or your friends
				if (player != mc.player && !(ignoreFriends.get() && Friends.isFriend(player))) {
					ChatUtil.send(text);
				} else {
					Commands.info(text);
				}
			}
			default -> {
			}
		}
	}

	public int getPops(String name) {
		return pops.getOrDefault(name, 0);
	}

	@Override
	public String getInfo() {
		return total > 0 ? Integer.toString(total) : null;
	}
}
