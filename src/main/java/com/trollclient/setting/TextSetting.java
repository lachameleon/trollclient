package com.trollclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class TextSetting extends Setting<String> {
	private final int maxLength;
	private Supplier<List<String>> suggestions = Collections::emptyList;
	private String placeholder = "";

	public TextSetting(String name, String description, String defaultValue, int maxLength) {
		super(name, description, defaultValue);
		this.maxLength = maxLength;
	}

	public TextSetting suggestions(Supplier<List<String>> suggestions) {
		this.suggestions = suggestions;
		return this;
	}

	public TextSetting placeholder(String placeholder) {
		this.placeholder = placeholder;
		return this;
	}

	public List<String> getSuggestions() {
		return suggestions.get();
	}

	public String getPlaceholder() {
		return placeholder;
	}

	public int getMaxLength() {
		return maxLength;
	}

	@Override
	protected String sanitize(String v) {
		if (v == null) {
			return "";
		}
		return v.length() > maxLength ? v.substring(0, maxLength) : v;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement element) {
		if (element.isJsonPrimitive()) {
			set(element.getAsString());
		}
	}

	@Override
	public String display() {
		return value.isEmpty() ? "<none>" : value;
	}

	@Override
	public boolean parse(String input) {
		set(input);
		return true;
	}
}
