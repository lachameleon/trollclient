package com.trollclient.util;

import com.trollclient.TrollClient;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;

/**
 * Where the website's Worker lives. Updates and the chatroom talk to the same place telemetry
 * reports to, so a build with an empty {@code telemetry_url} talks to nothing at all.
 */
public final class Site {
	private Site() {
	}

	/**
	 * Scheme and host, no trailing slash, or "" if this build has no site.
	 * {@code -Dtrollclient.site} wins; dev runs talk to {@code npm run dev}; releases use the
	 * origin of the URL baked into fabric.mod.json.
	 */
	public static String origin() {
		String override = System.getProperty("trollclient.site");
		if (override != null) {
			return originOf(override.trim());
		}
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return "http://localhost:8642";
		}
		return FabricLoader.getInstance().getModContainer(TrollClient.MOD_ID)
				.map(c -> c.getMetadata().getCustomValue("trollclient:telemetry"))
				.map(v -> originOf(v.getAsString().trim()))
				.orElse("");
	}

	/** {@code path} ("/api/update") on the site, or "" if there is no site. */
	public static String url(String path) {
		String origin = origin();
		return origin.isEmpty() ? "" : origin + path;
	}

	/** The same, as a WebSocket address. */
	public static String socket(String path) {
		String url = url(path);
		return url.isEmpty() ? "" : url.replaceFirst("^http", "ws");
	}

	/** "https://example.com/api/telemetry" -> "https://example.com"; anything that isn't http(s) -> "". */
	public static String originOf(String url) {
		try {
			URI uri = URI.create(url);
			String scheme = uri.getScheme();
			if (uri.getHost() == null || scheme == null || !(scheme.equals("https") || scheme.equals("http"))) {
				return "";
			}
			return scheme + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
		} catch (IllegalArgumentException e) {
			return "";
		}
	}

	public static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("");
	}

	public static String userAgent() {
		return "TrollClient/" + version(TrollClient.MOD_ID);
	}
}
