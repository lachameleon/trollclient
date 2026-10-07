package com.trollclient.macro;

import java.util.function.Supplier;

/** A kind of step as the editor's palette lists it: id (saved), name, category and a one-line description. */
public record StepType(String id, String name, String category, String description, Supplier<MacroStep> factory) {
	public MacroStep create() {
		MacroStep step = factory.get();
		step.setType(this);
		return step;
	}
}
