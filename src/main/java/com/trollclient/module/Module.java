package com.trollclient.module;

import com.trollclient.config.ConfigManager;
import com.trollclient.gui.hud.Notifications;
import com.trollclient.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module {
	protected static final Minecraft mc = Minecraft.getInstance();

	private final String name;
	private final String description;
	private final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
	private final List<Action> actions = new ArrayList<>();
	private boolean enabled;
	private int key = GLFW.GLFW_KEY_UNKNOWN;
	private boolean hidden;

	protected Module(String name, String description, Category category) {
		this.name = name;
		this.description = description;
		this.category = category;
	}

	/** A button in the module's settings, for things that aren't a value: open an editor, stop everything... */
	public record Action(String label, String description, Runnable run) {
	}

	protected <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	protected void action(String label, String description, Runnable run) {
		actions.add(new Action(label, description, run));
	}

	public List<Action> getActions() {
		return Collections.unmodifiableList(actions);
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled || !isToggleable()) {
			return;
		}
		this.enabled = enabled;
		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
		Notifications.moduleToggled(this);
		ConfigManager.markDirty();
	}

	/** Used by the config loader: flips state without notifications. */
	public void restoreEnabled(boolean enabled) {
		if (this.enabled == enabled || !isToggleable()) {
			return;
		}
		this.enabled = enabled;
		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	/** Called at the start of every client tick while enabled and in a world. */
	public void onTick() {
	}

	/** Called whenever an entity is added to the client level while enabled. */
	public void onEntityAdded(Entity entity) {
	}

	/**
	 * Called for every entity event packet (totem pops are 35, deaths 3) while
	 * enabled. Runs on the client thread after vanilla handled the packet.
	 */
	public void onEntityEvent(Entity entity, byte event) {
	}

	/**
	 * Called for every damage event packet (anyone in view getting hurt) while
	 * enabled, after vanilla handled it. {@code source.getEntity()} is whoever
	 * is responsible: the shooter for an arrow, the attacker for a punch.
	 */
	public void onDamage(Entity victim, DamageSource source) {
	}

	/** Called for chat lines from other players while enabled; {@code sender} is never you. */
	public void onChatMessage(String sender, String message) {
	}

	/** Called when the player leaves a world, so modules can drop stale state. */
	public void onWorldLeave() {
	}

	/** Short suffix shown next to the module in the array list. */
	public String getInfo() {
		return null;
	}

	/** The player this module is currently picking on, for the target HUD. */
	public Player getTarget() {
		return null;
	}

	/** {@link #getInfo()} minus anything that just repeats the name ("Twerk Twerk"). */
	public final String displayInfo() {
		String info = getInfo();
		return info == null || info.isBlank() || info.equalsIgnoreCase(name) ? null : info;
	}

	/** Settings-only modules (theme, HUD layout...) show no checkbox in the GUI. */
	public boolean isToggleable() {
		return true;
	}

	protected boolean inGame() {
		return mc.player != null && mc.level != null && mc.gameMode != null;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Category getCategory() {
		return category;
	}

	public List<Setting<?>> getSettings() {
		return Collections.unmodifiableList(settings);
	}

	public Setting<?> getSetting(String name) {
		for (Setting<?> s : settings) {
			if (s.getName().replace(" ", "").equalsIgnoreCase(name.replace(" ", ""))) {
				return s;
			}
		}
		return null;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int getKey() {
		return key;
	}

	public void setKey(int key) {
		this.key = key;
		ConfigManager.markDirty();
	}

	public boolean isHidden() {
		return hidden;
	}

	public void setHidden(boolean hidden) {
		this.hidden = hidden;
		ConfigManager.markDirty();
	}
}
