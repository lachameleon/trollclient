package com.trollclient.gui.clickgui.components;

import com.trollclient.gui.Anim;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Icon;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.setting.ModeSetting;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * Short option lists render as a segmented control with a sliding highlight;
 * long ones collapse into a "‹ value ›" cycler whose text slides in and out.
 */
public class ModeRow extends Row {
	private static final float SEG_PAD = 8;

	private final ModeSetting setting;
	private final Anim highlightX = new Anim(18);
	private final Anim highlightW = new Anim(18);
	private final Anim slide = new Anim(1, 14);
	private String previous;
	private int direction = 1;
	private boolean initialised;

	public ModeRow(ModeSetting setting) {
		super(setting.getName(), setting.getDescription(), setting::isVisible);
		this.setting = setting;
		this.previous = setting.get();
	}

	private boolean segmented() {
		float total = 0;
		for (String m : setting.getModes()) {
			total += Theme.width(m) + SEG_PAD;
		}
		return total <= w - 4;
	}

	@Override
	public float height() {
		return w > 0 && segmented() ? 30 : 16;
	}

	@Override
	public void render(GuiGraphicsExtractor g, float mx, float my, float dt) {
		float hv = hover.target(isHovered(mx, my)).update(dt);
		Draw.text(g, label, x + 2 + hv * 2, y + 4, ColorUtil.lerp(Theme.textDim, Theme.text, hv));
		if (segmented()) {
			renderSegmented(g, mx, my, dt);
		} else {
			renderCycler(g, dt, hv);
		}
	}

	private void renderSegmented(GuiGraphicsExtractor g, float mx, float my, float dt) {
		List<String> modes = setting.getModes();
		float total = 0;
		for (String m : modes) {
			total += Theme.width(m) + SEG_PAD;
		}
		float stretch = (w - 4 - total) / modes.size();
		float sx = x + 2;
		float sy = y + 15;
		float sh = 13;
		Draw.panel(g, sx, sy, w - 4, sh, Theme.panel2);
		Draw.panelOutline(g, sx, sy, w - 4, sh, Theme.border);

		float cx = sx;
		for (int i = 0; i < modes.size(); i++) {
			float segW = Theme.width(modes.get(i)) + SEG_PAD + stretch;
			if (modes.get(i).equals(setting.get())) {
				if (!initialised) {
					highlightX.snap(cx);
					highlightW.snap(segW);
					initialised = true;
				}
				highlightX.target(cx);
				highlightW.target(segW);
			}
			cx += segW;
		}
		float hx = highlightX.update(dt);
		float hw = highlightW.update(dt);
		Draw.rect(g, hx + 1, sy + 1, hw - 2, sh - 2, Theme.accent((hx - sx) / (w - 4)));

		cx = sx;
		for (int i = 0; i < modes.size(); i++) {
			String m = modes.get(i);
			float segW = Theme.width(m) + SEG_PAD + stretch;
			boolean over = mx >= cx && mx < cx + segW && my >= sy && my < sy + sh;
			// text flips to the contrast colour exactly where the highlight covers it
			float overlap = Motion.clamp01((Math.min(cx + segW, hx + hw) - Math.max(cx, hx)) / segW);
			int color = ColorUtil.lerp(over ? Theme.text : Theme.textDim, Theme.onAccent(), overlap);
			Draw.textCentered(g, m, cx + segW / 2f, sy + 3, color);
			// dividers only between unselected segments: never on top of, or flush against, the (sliding) highlight
			if (i > 0 && (cx < hx - 1 || cx > hx + hw)) {
				Draw.rect(g, cx, sy + 3, 1, sh - 6, ColorUtil.fade(Theme.border, 0.8f));
			}
			cx += segW;
		}
	}

	private void renderCycler(GuiGraphicsExtractor g, float dt, float hv) {
		if (!setting.get().equals(previous)) {
			slide.snap(0);
			slide.target(1);
		}
		float s = slide.update(dt);
		float right = x + w - 12;
		float valueW = Theme.width(setting.get());
		float boxW = valueW + 12;
		float boxX = right - boxW;

		Draw.icon(g, Icon.CHEVRON, right + 1, y + 4, 8, ColorUtil.fade(Theme.text, 0.4f + hv * 0.6f));
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		// a half-turn keeps the quad's winding intact (a negative scale might get culled)
		pose.translate(boxX - 6, y + 8);
		pose.rotate((float) Math.PI);
		Draw.icon(g, Icon.CHEVRON, -4, -4, 8, ColorUtil.fade(Theme.text, 0.4f + hv * 0.6f));
		pose.popMatrix();

		g.enableScissor(Math.round(boxX), Math.round(y), Math.round(right), Math.round(y + 16));
		float e = Motion.outCubic(s);
		Draw.text(g, setting.get(), boxX + 6 + (1 - e) * 10 * direction, y + 4, ColorUtil.fade(Theme.accent(), e));
		if (s < 1) {
			Draw.text(g, previous, boxX + 6 - e * 10 * direction, y + 4, ColorUtil.fade(Theme.textDim, 1 - e));
		} else {
			previous = setting.get();
		}
		g.disableScissor();
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (!isHovered(mx, my)) {
			return false;
		}
		if (segmented()) {
			float total = 0;
			for (String m : setting.getModes()) {
				total += Theme.width(m) + SEG_PAD;
			}
			float stretch = (w - 4 - total) / setting.getModes().size();
			float cx = x + 2;
			for (String m : setting.getModes()) {
				float segW = Theme.width(m) + SEG_PAD + stretch;
				if (mx >= cx && mx < cx + segW && my >= y + 15) {
					if (!m.equals(setting.get())) {
						setting.set(m);
						Sounds.click();
					}
					return true;
				}
				cx += segW;
			}
			// clicking the label cycles too
			cycle(button == 1 ? -1 : 1);
			return true;
		}
		cycle(button == 1 ? -1 : 1);
		return true;
	}

	private void cycle(int dir) {
		previous = setting.get();
		direction = dir;
		setting.cycle(dir);
		Sounds.click();
	}

	@Override
	public boolean mouseScrolled(float mx, float my, double amount) {
		if (isHovered(mx, my)) {
			cycle(amount > 0 ? -1 : 1);
			return true;
		}
		return false;
	}
}
