package com.trollclient.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

/**
 * A single configurable value on a module. Every setting knows how to
 * serialise itself so the config system never needs to special-case types.
 */
public abstract class Setting<T> {
	private final String name;
	private final String description;
	private final T defaultValue;
	protected T value;
	private BooleanSupplier visibility = () -> true;
	private Runnable onChange = () -> {};

	protected Setting(String name, String description, T defaultValue) {
		this.name = name;
		this.description = description;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		T sanitized = sanitize(value);
		if (sanitized == null || sanitized.equals(this.value)) {
			return;
		}
		this.value = sanitized;
		onChange.run();
		SettingEvents.changed(this);
	}

	public T getDefault() {
		return defaultValue;
	}

	public boolean isModified() {
		return !java.util.Objects.equals(value, defaultValue);
	}

	/** The default, formatted the same way as {@link #display()}. */
	public String displayDefault() {
		T current = value;
		value = defaultValue;
		String s = display();
		value = current;
		return s;
	}

	public void reset() {
		set(defaultValue);
	}

	protected T sanitize(T value) {
		return value;
	}

	public boolean isVisible() {
		return visibility.getAsBoolean();
	}

	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S visibleWhen(BooleanSupplier supplier) {
		this.visibility = supplier;
		return (S) this;
	}

	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S onChange(Runnable runnable) {
		this.onChange = runnable;
		return (S) this;
	}

	public abstract JsonElement toJson();

	public abstract void fromJson(JsonElement element);

	/** Human readable value, used by tooltips, the HUD and commands. */
	public abstract String display();

	/** Parses a value typed into chat via {@code .set}. */
	public abstract boolean parse(String input);
}
