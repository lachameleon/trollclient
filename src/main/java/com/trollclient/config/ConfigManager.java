package com.trollclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trollclient.TrollClient;
import com.trollclient.gui.clickgui.GuiState;
import com.trollclient.module.Module;
import com.trollclient.module.ModuleManager;
import com.trollclient.setting.Setting;
import com.trollclient.util.Friends;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Single JSON file in the config directory. Saves are debounced. */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long SAVE_DELAY_MS = 1500;

	private static boolean loading;
	private static long dirtySince = -1;

	private ConfigManager() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("trollclient.json");
	}

	/** True while the file is being read back in, so restored values don't look like changes. */
	public static boolean isLoading() {
		return loading;
	}

	public static void markDirty() {
		if (!loading && dirtySince < 0) {
			dirtySince = System.currentTimeMillis();
		}
	}

	/** Called every client tick; writes the file once things have settled. */
	public static void tick() {
		if (dirtySince >= 0 && System.currentTimeMillis() - dirtySince > SAVE_DELAY_MS) {
			save();
		}
	}

	public static void save() {
		dirtySince = -1;
		JsonObject root = new JsonObject();
		JsonObject modules = new JsonObject();
		for (Module m : ModuleManager.all()) {
			JsonObject obj = new JsonObject();
			obj.addProperty("enabled", m.isEnabled());
			obj.addProperty("key", m.getKey());
			obj.addProperty("hidden", m.isHidden());
			JsonObject settings = new JsonObject();
			for (Setting<?> s : m.getSettings()) {
				settings.add(s.getName(), s.toJson());
			}
			obj.add("settings", settings);
			modules.add(m.getName(), obj);
		}
		root.add("modules", modules);

		JsonArray friends = new JsonArray();
		Friends.all().forEach(friends::add);
		root.add("friends", friends);
		root.add("gui", GuiState.toJson());

		Path path = file();
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			TrollClient.LOGGER.error("Failed to save config", e);
		}
	}

	public static void load() {
		Path path = file();
		if (!Files.exists(path)) {
			return;
		}
		loading = true;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("modules")) {
				JsonObject modules = root.getAsJsonObject("modules");
				for (Module m : ModuleManager.all()) {
					if (!modules.has(m.getName())) {
						continue;
					}
					JsonObject obj = modules.getAsJsonObject(m.getName());
					if (obj.has("settings")) {
						JsonObject settings = obj.getAsJsonObject("settings");
						for (Setting<?> s : m.getSettings()) {
							JsonElement e = settings.get(s.getName());
							if (e != null) {
								try {
									s.fromJson(e);
								} catch (RuntimeException ex) {
									TrollClient.LOGGER.warn("Bad value for {}.{}", m.getName(), s.getName());
								}
							}
						}
					}
					if (obj.has("key")) {
						m.setKey(obj.get("key").getAsInt());
					}
					if (obj.has("hidden")) {
						m.setHidden(obj.get("hidden").getAsBoolean());
					}
					if (obj.has("enabled")) {
						m.restoreEnabled(obj.get("enabled").getAsBoolean());
					}
				}
			}
			if (root.has("friends")) {
				for (JsonElement e : root.getAsJsonArray("friends")) {
					Friends.add(e.getAsString());
				}
			}
			if (root.has("gui")) {
				GuiState.fromJson(root.getAsJsonObject("gui"));
			}
		} catch (IOException | RuntimeException e) {
			TrollClient.LOGGER.error("Failed to load config, using defaults", e);
		} finally {
			loading = false;
			dirtySince = -1;
		}
	}
}
