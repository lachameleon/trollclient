package com.trollclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public class NumberSetting extends Setting<Double> {
	private final double min;
	private final double max;
	private final double step;
	private String unit = "";

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
		super(name, description, defaultValue);
		this.min = min;
		this.max = max;
		this.step = step;
	}

	public NumberSetting unit(String unit) {
		this.unit = unit;
		return this;
	}

	public double getMin() {
		return min;
	}

	public double getMax() {
		return max;
	}

	public double getStep() {
		return step;
	}

	public int getInt() {
		return (int) Math.round(value);
	}

	public float getFloat() {
		return value.floatValue();
	}

	/** Position of the value between min and max, 0..1. */
	public double getPercent() {
		return (value - min) / (max - min);
	}

	public void setPercent(double percent) {
		set(min + (max - min) * Math.max(0, Math.min(1, percent)));
	}

	public void increment(int direction) {
		set(value + step * direction);
	}

	@Override
	protected Double sanitize(Double v) {
		if (v == null || v.isNaN()) {
			return value;
		}
		double snapped = Math.round((v - min) / step) * step + min;
		snapped = Math.max(min, Math.min(max, snapped));
		// trim floating point noise so the config stays readable
		return Math.round(snapped * 10000.0) / 10000.0;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement element) {
		if (element.isJsonPrimitive()) {
			set(element.getAsDouble());
		}
	}

	@Override
	public String display() {
		boolean whole = step >= 1 && Math.abs(step - Math.rint(step)) < 1e-9;
		String number = whole ? Integer.toString(getInt()) : String.format(Locale.ROOT, step >= 0.1 ? "%.1f" : "%.2f", value);
		return number + unit;
	}

	@Override
	public boolean parse(String input) {
		try {
			set(Double.parseDouble(input.replace(unit, "").trim()));
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
