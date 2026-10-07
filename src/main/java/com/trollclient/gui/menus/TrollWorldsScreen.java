package com.trollclient.gui.menus;

import com.mojang.blaze3d.platform.NativeImage;
import com.trollclient.TrollClient;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Motion;
import com.trollclient.gui.Sounds;
import com.trollclient.gui.Theme;
import com.trollclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The singleplayer world list, Troll Client style. Opening, creating and editing still go through vanilla. */
public class TrollWorldsScreen extends TrollListScreen<LevelSummary> {
	private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);

	private List<LevelSummary> worlds = new ArrayList<>();
	private final Map<String, FaviconTexture> icons = new HashMap<>();
	private boolean loading = true;
	private String error;

	public TrollWorldsScreen(Screen parent) {
		super(Component.literal("Singleplayer"), parent);
		reload();
	}

	private void reload() {
		loading = true;
		LevelStorageSource source = minecraft.getLevelSource();
		try {
			LevelStorageSource.LevelCandidates candidates = source.findLevelCandidates();
			if (candidates.isEmpty()) {
				worlds = new ArrayList<>();
				loading = false;
				status("ls ~/saves  (empty)");
				return;
			}
			source.loadLevelSummaries(candidates).thenAcceptAsync(list -> {
				List<LevelSummary> sorted = new ArrayList<>(list);
				sorted.sort(null); // most recently played first
				worlds = sorted;
				loading = false;
				status("ls ~/saves  (" + sorted.size() + " world" + (sorted.size() == 1 ? "" : "s") + ")");
				if (!sorted.isEmpty() && selectedEntry() == null) {
					select(sorted.get(0));
				}
			}, minecraft).exceptionally(t -> {
				error = "couldn't read your worlds";
				loading = false;
				TrollClient.LOGGER.error("Couldn't load world list", t);
				return null;
			});
		} catch (Exception e) {
			error = "couldn't read your worlds";
			loading = false;
			TrollClient.LOGGER.error("Couldn't load world list", e);
		}
	}

	@Override
	protected List<LevelSummary> entries() {
		return worlds;
	}

	@Override
	protected String searchText(LevelSummary w) {
		return w.getLevelName() + " " + w.getLevelId();
	}

	@Override
	protected String heading() {
		return "singleplayer";
	}

	@Override
	protected String tagline() {
		return "pick a world. or make a new one.";
	}

	@Override
	protected String headerInfo() {
		return loading ? "loading..." : worlds.size() + " world" + (worlds.size() == 1 ? "" : "s");
	}

	@Override
	protected String emptyText() {
		if (error != null) {
			return error;
		}
		return loading ? "loading worlds..." : "no worlds yet. press \"new world\"";
	}

	// ------------------------------------------------------------------ rows

	private FaviconTexture icon(LevelSummary w) {
		return icons.computeIfAbsent(w.getLevelId(), id -> {
			FaviconTexture tex = FaviconTexture.forWorld(minecraft.getTextureManager(), id);
			Path file = w.getIcon();
			try {
				if (file != null && Files.isRegularFile(file)) {
					try (InputStream in = Files.newInputStream(file)) {
						tex.upload(NativeImage.read(in));
					}
				} else {
					tex.clear();
				}
			} catch (Exception e) {
				tex.clear();
			}
			return tex;
		});
	}

	@Override
	protected void drawRow(GuiGraphicsExtractor g, LevelSummary w, float x, float y, float width, float h, float hover, boolean selected) {
		// world icon, framed; it stays dimmed until you hover or pick it
		float is = h - 6;
		Draw.rect(g, x - 1, y + 2, is + 2, is + 2, Theme.border);
		FaviconTexture tex = icon(w);
		if (tex != null && !tex.isClosed()) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex.textureLocation(), Math.round(x), Math.round(y + 3), 0, 0,
					Math.round(is), Math.round(is), Math.round(is), Math.round(is));
		}
		float lit = Math.max(hover, selected ? 1 : 0);
		Draw.rect(g, x, y + 3, is, is, ColorUtil.withAlpha(Theme.bg, Math.round(150 * (1 - lit))));

		float tx = x + is + 8;
		float right = x + width - 6;
		List<String> badges = new ArrayList<>();
		if (w.isHardcore()) {
			badges.add("hardcore");
		}
		if (w.isLocked()) {
			badges.add("in use");
		}
		if (w.isExperimental()) {
			badges.add("experimental");
		}
		if (w.isDowngrade() || !w.isCompatible()) {
			badges.add("old version");
		}
		for (String b : badges) {
			float bw = Theme.width(b) + 6;
			Draw.panelOutline(g, right - bw, y + 4, bw, 11, Theme.borderHi);
			Draw.text(g, b, right - bw + 3, y + 6, Theme.textDim);
			right -= bw + 4;
		}
		if (selected) {
			String hint = w.primaryActionActive() ? "play >" : "can't open";
			float pulse = 0.6f + 0.4f * (float) Math.sin(Motion.time() * 5);
			Draw.textRight(g, hint, x + width - 6, y + h - 12, ColorUtil.fade(Theme.text, pulse));
		}
		Draw.text(g, Draw.ellipsize(w.getLevelName(), (int) (right - tx - 4)), tx, y + 5, ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(lit, 0.6f)));
		String mode = w.isHardcore() ? "hardcore" : w.getGameMode().getName();
		String line2 = w.getLevelId() + "  /  " + (w.getLastPlayed() > 0 ? DATE.format(new Date(w.getLastPlayed())) : "never played");
		String line3 = mode + (w.hasCommands() ? "  /  cheats" : "") + "  /  " + w.getWorldVersionName().getString();
		Draw.text(g, Draw.ellipsize(line2, (int) (x + width - tx - 60)), tx, y + 15, Theme.textDim);
		Draw.text(g, Draw.ellipsize(line3.toLowerCase(Locale.ROOT), (int) (x + width - tx - 60)), tx, y + 24, ColorUtil.fade(Theme.textDim, 0.75f));
	}

	// ------------------------------------------------------------------ actions

	@Override
	protected void activate(LevelSummary w) {
		if (!w.primaryActionActive()) {
			status("can't open " + w.getLevelId() + ": " + w.getInfo().getString().toLowerCase(Locale.ROOT));
			Sounds.toggle(false);
			return;
		}
		status("./play " + w.getLevelId());
		Sounds.click();
		minecraft.createWorldOpenFlows().openWorld(w.getLevelId(), () -> {
			reload();
			minecraft.gui.setScreen(this);
		});
	}

	@Override
	protected List<Action> actions() {
		return List.of(
				new Action("play", "enter", () -> selectedEntry() != null && selectedEntry().primaryActionActive(), () -> activate(selectedEntry())),
				new Action("new world", null, () -> true, this::createWorld),
				new Action("edit", null, () -> selectedEntry() != null && selectedEntry().canEdit(), () -> edit(selectedEntry())),
				new Action("delete", "del", () -> selectedEntry() != null && selectedEntry().canDelete(), () -> deleteRequested(selectedEntry())),
				new Action("back", "esc", () -> true, this::onClose));
	}

	private void createWorld() {
		status("./new-world");
		CreateWorldScreen.openFresh(minecraft, () -> minecraft.gui.setScreen(this));
	}

	private void edit(LevelSummary w) {
		if (w == null) {
			return;
		}
		try {
			LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().validateAndCreateAccess(w.getLevelId());
			minecraft.gui.setScreen(EditWorldScreen.create(minecraft, access, changed -> {
				access.safeClose();
				reload();
				minecraft.gui.setScreen(this);
			}));
		} catch (Exception e) {
			status("couldn't open " + w.getLevelId() + " for editing");
			TrollClient.LOGGER.error("Couldn't edit world {}", w.getLevelId(), e);
		}
	}

	@Override
	protected void deleteRequested(LevelSummary w) {
		if (w == null || !w.canDelete()) {
			return;
		}
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				try (LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().createAccess(w.getLevelId())) {
					access.deleteLevel();
					status("rm -rf ~/saves/" + w.getLevelId());
				} catch (Exception e) {
					status("couldn't delete " + w.getLevelId());
					TrollClient.LOGGER.error("Couldn't delete world {}", w.getLevelId(), e);
				}
				FaviconTexture tex = icons.remove(w.getLevelId());
				if (tex != null) {
					tex.close();
				}
				reload();
			}
			minecraft.gui.setScreen(this);
		}, Component.literal("Delete \"" + w.getLevelName() + "\"?"),
				Component.literal("It'll be gone forever. (A long time!)"),
				Component.literal("Delete"), Component.literal("Keep it")));
	}

	@Override
	protected boolean extraKey(KeyEvent event) {
		switch (event.key()) {
			case GLFW.GLFW_KEY_F5 -> reload();
			default -> {
			}
		}
		return true;
	}

	@Override
	public void removed() {
		// textures are rebuilt lazily if the screen comes back
		for (FaviconTexture tex : icons.values()) {
			tex.close();
		}
		icons.clear();
	}
}
