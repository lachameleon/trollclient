package com.trollclient.gui.clickgui.components;

import com.trollclient.gui.Anim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

import java.util.function.BooleanSupplier;

/**
 * One line in the settings panel. Coordinates are in window-local units;
 * the panel lays rows out and hands each its box before rendering.
 */
public abstract class Row {
	protected final String label;
	protected final String description;
	private final BooleanSupplier visible;
	/** 0..1 visibility, animates rows in and out when settings depend on each other. */
	public final Anim shown;
	public final Anim hover = new Anim(14);
	private BooleanSupplier modified = () -> false;
	private String defaultText;

	protected float x;
	protected float y;
	protected float w;

	protected Row(String label, String description, BooleanSupplier visible) {
		this.label = label;
		this.description = description;
		this.visible = visible;
		this.shown = new Anim(visible.getAsBoolean() ? 1 : 0, 12);
	}

	public void layout(float x, float y, float w) {
		this.x = x;
		this.y = y;
		this.w = w;
	}

	public boolean isVisible() {
		return visible.getAsBoolean();
	}

	/** Full height when visible. */
	public abstract float height();

	public abstract void render(GuiGraphicsExtractor g, float mx, float my, float dt);

	public boolean mouseClicked(float mx, float my, int button) {
		return false;
	}

	public void mouseReleased(float mx, float my, int button) {
	}

	public void mouseDragged(float mx, float my) {
	}

	public boolean mouseScrolled(float mx, float my, double amount) {
		return false;
	}

	public boolean keyPressed(KeyEvent event) {
		return false;
	}

	public boolean charTyped(String chars) {
		return false;
	}

	/** True while the row wants all keyboard input (text field, key capture). */
	public boolean isCapturing() {
		return false;
	}

	public void unfocus() {
	}

	/** True while the row tracks the mouse (slider drag), even outside its box. */
	public boolean isDragging() {
		return false;
	}

	/** Point {@code fraction} of the way across the row's interactive part (sliders use their track). */
	public float[] point(float fraction) {
		return new float[]{x + w * fraction, y + height() * 0.7f};
	}

	public boolean isHovered(float mx, float my) {
		return mx >= x && mx < x + w && my >= y && my < y + height();
	}

	public String getDescription() {
		return description;
	}

	/** Links the row to its setting so the panel can mark changed values and show the default. */
	public Row describe(BooleanSupplier modified, String defaultText) {
		this.modified = modified;
		this.defaultText = defaultText;
		return this;
	}

	public boolean isModified() {
		return modified.getAsBoolean();
	}

	public String getDefaultText() {
		return defaultText;
	}

	public String getLabel() {
		return label;
	}

	/** Whether the hand cursor should show while hovering. */
	public boolean clickable() {
		return true;
	}
}
