package com.trollclient.module;

import com.trollclient.TrollClient;
import com.trollclient.module.client.ClickGuiModule;
import com.trollclient.module.client.GuiToolsModule;
import com.trollclient.module.client.MacrosModule;
import com.trollclient.module.client.HudModule;
import com.trollclient.module.client.ShadersModule;
import com.trollclient.module.client.ThemeModule;
import com.trollclient.module.client.TitleScreenModule;
import com.trollclient.module.combat.ArrowDodge;
import com.trollclient.module.combat.CrystalCancel;
import com.trollclient.module.combat.Grudge;
import com.trollclient.module.combat.PopCounter;
import com.trollclient.module.movement.Goalie;
import com.trollclient.module.movement.Orbit;
import com.trollclient.module.movement.PlayerAvoid;
import com.trollclient.module.movement.PlayerFollow;
import com.trollclient.module.player.ItemFlex;
import com.trollclient.module.player.ItemPickup;
import com.trollclient.module.player.Juggle;
import com.trollclient.module.troll.FoodAnnoy;
import com.trollclient.module.troll.Gifter;
import com.trollclient.module.troll.Mimic;
import com.trollclient.module.troll.Morse;
import com.trollclient.module.troll.NoWayHome;
import com.trollclient.module.chat.Announcer;
import com.trollclient.module.chat.AutoReply;
import com.trollclient.module.chat.ChatStyle;
import com.trollclient.module.chat.Greeter;
import com.trollclient.module.chat.Parrot;
import com.trollclient.module.chat.Spammer;
import com.trollclient.module.chat.Typo;
import com.trollclient.module.combat.Trapper;
import com.trollclient.module.movement.Stalker;
import com.trollclient.module.player.SkinBlink;
import com.trollclient.module.player.SkinChanger;
import com.trollclient.module.troll.Confetti;
import com.trollclient.module.troll.Graffiti;
import com.trollclient.module.troll.Honk;
import com.trollclient.module.troll.Pelter;
import com.trollclient.module.troll.Racket;
import com.trollclient.module.troll.Taunt;
import com.trollclient.module.troll.Twerk;
import com.trollclient.module.troll.WindAnnoy;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ModuleManager {
	private static final List<Module> MODULES = new ArrayList<>();
	private static final Map<Class<? extends Module>, Module> BY_CLASS = new HashMap<>();

	private ModuleManager() {
	}

	public static void init() {
		// combat
		register(new CrystalCancel());
		register(new ArrowDodge());
		register(new PopCounter());
		register(new Trapper());
		register(new Grudge());
		// movement
		register(new PlayerAvoid());
		register(new PlayerFollow());
		register(new Orbit());
		register(new Stalker());
		register(new Goalie());
		// troll
		register(new Twerk());
		register(new WindAnnoy());
		register(new NoWayHome());
		register(new FoodAnnoy());
		register(new Mimic());
		register(new Morse());
		register(new Taunt());
		register(new Racket());
		register(new Gifter());
		register(new Pelter());
		register(new Confetti());
		register(new Graffiti());
		register(new Honk());
		// chat
		register(new Parrot());
		register(new ChatStyle());
		register(new Spammer());
		register(new Announcer());
		register(new Greeter());
		register(new AutoReply());
		register(new Typo());
		// player
		register(new ItemPickup());
		register(new ItemFlex());
		register(new Juggle());
		register(new SkinBlink());
		register(new SkinChanger());
		// client
		register(new ClickGuiModule());
		register(new HudModule());
		register(new ThemeModule());
		register(new ShadersModule());
		register(new TitleScreenModule());
		register(new GuiToolsModule());
		register(new MacrosModule());
		TrollClient.LOGGER.info("Registered {} modules", MODULES.size());
	}

	private static void register(Module module) {
		MODULES.add(module);
		BY_CLASS.put(module.getClass(), module);
	}

	public static List<Module> all() {
		return Collections.unmodifiableList(MODULES);
	}

	public static List<Module> byCategory(Category category) {
		List<Module> list = new ArrayList<>();
		for (Module m : MODULES) {
			if (m.getCategory() == category) {
				list.add(m);
			}
		}
		return list;
	}

	@SuppressWarnings("unchecked")
	public static <T extends Module> T get(Class<T> type) {
		return (T) BY_CLASS.get(type);
	}

	public static Module byName(String name) {
		String needle = name.replace(" ", "");
		for (Module m : MODULES) {
			if (m.getName().equalsIgnoreCase(needle)) {
				return m;
			}
		}
		return null;
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		for (Module m : MODULES) {
			if (m.isEnabled()) {
				try {
					m.onTick();
				} catch (RuntimeException e) {
					TrollClient.LOGGER.error("Module {} crashed while ticking, disabling it", m.getName(), e);
					m.setEnabled(false);
				}
			}
		}
	}

	public static void entityAdded(Entity entity) {
		for (Module m : MODULES) {
			if (m.isEnabled()) {
				m.onEntityAdded(entity);
			}
		}
	}

	public static void entityEvent(Entity entity, byte event) {
		for (Module m : MODULES) {
			if (m.isEnabled()) {
				safely(m, () -> m.onEntityEvent(entity, event));
			}
		}
	}

	public static void damage(Entity victim, DamageSource source) {
		for (Module m : MODULES) {
			if (m.isEnabled()) {
				safely(m, () -> m.onDamage(victim, source));
			}
		}
	}

	public static void chatMessage(String sender, String message) {
		for (Module m : MODULES) {
			if (m.isEnabled()) {
				safely(m, () -> m.onChatMessage(sender, message));
			}
		}
	}

	private static void safely(Module m, Runnable hook) {
		try {
			hook.run();
		} catch (RuntimeException e) {
			TrollClient.LOGGER.error("Module {} crashed in an event hook, disabling it", m.getName(), e);
			m.setEnabled(false);
		}
	}

	public static void worldLeft() {
		for (Module m : MODULES) {
			m.onWorldLeave();
		}
	}

	/** Toggles every module bound to {@code key}. Returns true if anything matched. */
	public static boolean keyPressed(int key) {
		boolean any = false;
		for (Module m : MODULES) {
			if (m.getKey() == key && key > 0) {
				m.toggle();
				any = true;
			}
		}
		return any;
	}
}
