package com.trollclient.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trollclient.TrollClient;
import com.trollclient.command.Commands;
import com.trollclient.dev.DevShots;
import com.trollclient.dev.Showcase;
import com.trollclient.gui.Icon;
import com.trollclient.gui.clickgui.TerminalLog;
import com.trollclient.gui.hud.Notifications;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.UpdaterModule;
import com.trollclient.telemetry.Telemetry;
import com.trollclient.util.Site;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps the client up to date. On launch it asks the website for the newest release built for the
 * Minecraft version that's actually running, downloads it next to the jar we were loaded from, and
 * leaves it there: Fabric Loader picks the newest of two jars with the same mod id, so the new one
 * loads on the next launch, and that launch deletes the old one. Nothing is replaced while it's in use.
 *
 * <p>A jar is only installed if all of this holds: it comes from our own site, its SHA-256 and size
 * match what the site said, its own fabric.mod.json says it's this mod at the announced version and
 * newer than this one, and every dependency it declares (Minecraft, Java, Fabric Loader, Fabric API)
 * is satisfied by what's running right now. So a build for another Minecraft version never lands in
 * the mods folder, even if the site offered one by mistake.
 *
 * <p>The check itself sends the client version and the Minecraft version and nothing else, and the
 * download goes through {@code /api/update/}, which the site doesn't count as a download.
 */
public final class Updater {
	private static final long MAX_BYTES = 64L * 1024 * 1024;
	private static final Pattern FILE_NAME = Pattern.compile("troll-client-[A-Za-z0-9.+-]{1,40}\\.jar");
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	/** Something that makes this release not for us; the message says what. */
	private static final class Unsuitable extends Exception {
		Unsuitable(String message) {
			super(message);
		}
	}

	private static volatile String status = "";
	private static volatile boolean busy;
	/** Shown in chat once there is a world to show it in, since launch-time toasts are long gone by then. */
	private static volatile String notice;

	private Updater() {
	}

	/** After the config is loaded: tidy up after a finished update, then look for the next one. */
	public static void init() {
		if (!supported()) {
			return;
		}
		Path jar = ownJar();
		if (jar == null) {
			status = "not installed as a jar";
			return;
		}
		Thread thread = new Thread(() -> {
			removeOlder(jar);
			if (enabled()) {
				check(false);
			}
		}, "Troll Client updater");
		thread.setDaemon(true);
		thread.start();
	}

	private static boolean supported() {
		return !Showcase.enabled() && !DevShots.enabled() && !"false".equals(System.getProperty("trollclient.update"))
				&& !Site.origin().isEmpty();
	}

	private static boolean enabled() {
		UpdaterModule module = ModuleManager.get(UpdaterModule.class);
		return module != null && module.isEnabled();
	}

	/** What the module shows next to its name: "up to date", "1.1.0 on next launch"... */
	public static String status() {
		return status;
	}

	/** The Check Now button and {@code .update}: looks again and says what it found. */
	public static void checkNow() {
		if (!supported()) {
			say("Updates are off in this build.");
			return;
		}
		if (ownJar() == null) {
			say("Not running from a jar in the mods folder, so there's nothing to update.");
			return;
		}
		if (busy) {
			say("Already checking.");
			return;
		}
		Thread thread = new Thread(() -> check(true), "Troll Client updater");
		thread.setDaemon(true);
		thread.start();
	}

	/** End of every client tick: pass on anything that happened while there was no chat to say it in. */
	public static void tick(Minecraft mc) {
		String text = notice;
		if (text != null && mc.player != null) {
			notice = null;
			Commands.info(text);
		}
	}

	// ------------------------------------------------------------ the check

	private static void check(boolean manual) {
		if (busy) {
			return;
		}
		busy = true;
		try {
			status = "checking";
			run(manual);
		} catch (Unsuitable e) {
			status = "skipped: " + e.getMessage();
			TrollClient.LOGGER.warn("Update not installed: {}", e.getMessage());
			if (manual) {
				say("Update not installed: " + e.getMessage());
			}
		} catch (IOException | RuntimeException e) {
			status = "couldn't check";
			TrollClient.LOGGER.debug("Update check failed", e);
			if (manual) {
				say("Couldn't reach the update server.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			busy = false;
		}
	}

	private static void run(boolean manual) throws IOException, InterruptedException, Unsuitable {
		Path jar = ownJar();
		ModContainer self = FabricLoader.getInstance().getModContainer(TrollClient.MOD_ID).orElseThrow();
		Version current = self.getMetadata().getVersion();
		String minecraft = Site.version("minecraft");

		// a newer jar from an earlier check this session is already waiting
		Path waiting = newerSibling(jar, current);
		if (waiting != null) {
			ready(versionOf(waiting), manual);
			return;
		}

		String query = Site.url("/api/update") + "?version=" + encode(current.getFriendlyString()) + "&minecraft=" + encode(minecraft);
		HttpResponse<String> res = HTTP.send(request(query, 10).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (res.statusCode() != 200) {
			throw new IOException("update check answered " + res.statusCode());
		}
		JsonObject info = JsonParser.parseString(res.body()).getAsJsonObject();
		if (!info.has("update") || !info.get("update").getAsBoolean()) {
			status = "up to date";
			if (manual) {
				say("Troll Client " + current.getFriendlyString() + " is the newest version for Minecraft " + minecraft + ".");
			}
			return;
		}

		String version = info.get("version").getAsString();
		String file = info.get("file").getAsString();
		String sha256 = info.get("sha256").getAsString();
		long size = info.get("size").getAsLong();
		// everything below is checked again against the jar itself once it's here
		if (parse(version).compareTo(current) <= 0) {
			status = "up to date";
			return;
		}
		if (!builtFor(info.get("minecraft"), minecraft)) {
			throw new Unsuitable(version + " isn't built for Minecraft " + minecraft);
		}
		if (!FILE_NAME.matcher(file).matches() || !SHA_256.matcher(sha256).matches() || size <= 0 || size > MAX_BYTES) {
			throw new Unsuitable("the update server's answer didn't make sense");
		}
		URI from = URI.create(Site.url("/api/update/" + file));

		UpdaterModule module = ModuleManager.get(UpdaterModule.class);
		if (module.mode.is("Notify")) {
			status = version + " is out";
			announce("Troll Client " + version + " is out. Set Updater to Install, or get it from " + Site.origin() + "/download", true);
			return;
		}

		Path target = jar.resolveSibling(file);
		if (Files.exists(target)) {
			throw new Unsuitable(file + " is already in the mods folder but isn't the newest release");
		}
		status = "downloading " + version;
		Path part = jar.resolveSibling(file + ".part");
		try {
			download(from, part, size, sha256);
			inspect(part, version, current);
			Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(part);
		}
		TrollClient.LOGGER.info("Troll Client {} downloaded to {}; it loads on the next launch", version, target);
		Minecraft.getInstance().execute(() -> Telemetry.count("updates_installed"));
		ready(version, true);
	}

	private static void ready(String version, boolean tell) {
		status = version + " on next launch";
		if (tell) {
			announce("Troll Client " + version + " is downloaded and loads the next time you start the game.", true);
		}
	}

	private static void download(URI from, Path part, long size, String sha256) throws IOException, InterruptedException, Unsuitable {
		HttpResponse<InputStream> res = HTTP.send(request(from.toString(), 120).build(), HttpResponse.BodyHandlers.ofInputStream());
		MessageDigest digest = sha256();
		long total = 0;
		try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
			if (res.statusCode() != 200) {
				throw new IOException("download answered " + res.statusCode());
			}
			byte[] buffer = new byte[16384];
			for (int n; (n = in.read(buffer)) > 0; ) {
				total += n;
				if (total > size) {
					throw new Unsuitable("the download is bigger than the release it's supposed to be");
				}
				digest.update(buffer, 0, n);
				out.write(buffer, 0, n);
			}
		}
		if (total != size || !HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
			throw new Unsuitable("the download doesn't match the release's checksum");
		}
	}

	/** Reads the downloaded jar's own fabric.mod.json: right mod, right version, and it would load here. */
	private static void inspect(Path file, String announced, Version current) throws IOException, Unsuitable {
		JsonObject meta = metadata(file);
		if (meta == null || !meta.has("id") || !TrollClient.MOD_ID.equals(meta.get("id").getAsString()) || !meta.has("version")) {
			throw new Unsuitable("the download isn't Troll Client");
		}
		String version = meta.get("version").getAsString();
		if (!version.equals(announced) || parse(version).compareTo(current) <= 0) {
			throw new Unsuitable("the download is version " + version + ", not the " + announced + " that was announced");
		}
		if (!meta.has("depends") || !meta.get("depends").isJsonObject()) {
			throw new Unsuitable("the download doesn't say what it needs");
		}
		for (var need : meta.getAsJsonObject("depends").entrySet()) {
			Optional<ModContainer> have = FabricLoader.getInstance().getModContainer(need.getKey());
			if (have.isEmpty()) {
				throw new Unsuitable(version + " needs " + need.getKey() + ", which isn't installed");
			}
			Version running = have.get().getMetadata().getVersion();
			if (!satisfies(need.getValue(), running)) {
				throw new Unsuitable(version + " needs " + need.getKey() + " " + need.getValue() + ", this is " + running.getFriendlyString());
			}
		}
	}

	/** A dependency is a version range, or a list of them where any one will do. */
	private static boolean satisfies(JsonElement ranges, Version running) throws Unsuitable {
		List<String> list = new ArrayList<>();
		if (ranges.isJsonArray()) {
			ranges.getAsJsonArray().forEach(e -> list.add(e.getAsString()));
		} else {
			list.add(ranges.getAsString());
		}
		try {
			for (String range : list) {
				if (VersionPredicate.parse(range).test(running)) {
					return true;
				}
			}
		} catch (VersionParsingException e) {
			throw new Unsuitable("the download has a dependency this loader can't read");
		}
		return false;
	}

	/** The site's own idea of which Minecraft versions a release is for: "26.3" covers 26.3 and 26.3.x. */
	private static boolean builtFor(JsonElement versions, String minecraft) {
		if (versions == null || !versions.isJsonArray()) {
			return false;
		}
		JsonArray list = versions.getAsJsonArray();
		for (JsonElement e : list) {
			String built = e.getAsString();
			if (minecraft.equals(built) || minecraft.startsWith(built + ".")) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ the mods folder

	/** The jar this mod was loaded from, or null in a dev run or when another mod bundles us. */
	private static Path ownJar() {
		Optional<ModContainer> self = FabricLoader.getInstance().getModContainer(TrollClient.MOD_ID);
		if (self.isEmpty()) {
			return null;
		}
		ModOrigin origin = self.get().getOrigin();
		if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1) {
			return null;
		}
		Path path = origin.getPaths().get(0);
		return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar") ? path.toAbsolutePath() : null;
	}

	/** Other copies of this mod next to our jar, with their versions. Jars of anything else are left alone. */
	private static List<Path> siblings(Path jar) {
		List<Path> out = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(jar.getParent(), "*.jar")) {
			for (Path p : files) {
				if (!p.toAbsolutePath().equals(jar) && Files.isRegularFile(p) && versionOf(p) != null) {
					out.add(p);
				}
			}
		} catch (IOException | RuntimeException e) {
			TrollClient.LOGGER.debug("Couldn't look through the mods folder", e);
		}
		return out;
	}

	/** Version of {@code file} if it is a Troll Client jar, otherwise null. */
	private static String versionOf(Path file) {
		try {
			JsonObject meta = metadata(file);
			if (meta != null && meta.has("id") && meta.has("version") && TrollClient.MOD_ID.equals(meta.get("id").getAsString())) {
				return meta.get("version").getAsString();
			}
		} catch (IOException | RuntimeException e) {
			// not a jar we can read: not ours to touch
		}
		return null;
	}

	private static Path newerSibling(Path jar, Version current) {
		for (Path p : siblings(jar)) {
			try {
				if (parse(versionOf(p)).compareTo(current) > 0) {
					return p;
				}
			} catch (Unsuitable e) {
				// a version we can't parse isn't an update
			}
		}
		return null;
	}

	/** We are the update: the copy we replaced can go. Runs once at launch, off the main thread. */
	private static void removeOlder(Path jar) {
		Version current = FabricLoader.getInstance().getModContainer(TrollClient.MOD_ID).orElseThrow().getMetadata().getVersion();
		for (Path p : siblings(jar)) {
			try {
				if (parse(versionOf(p)).compareTo(current) < 0) {
					Files.delete(p);
					TrollClient.LOGGER.info("Removed {} (replaced by {})", p.getFileName(), jar.getFileName());
				}
			} catch (IOException | Unsuitable e) {
				// still locked or unreadable: the next launch tries again
				TrollClient.LOGGER.debug("Couldn't remove {}", p.getFileName(), e);
			}
		}
		// a download that was cut short
		try (DirectoryStream<Path> parts = Files.newDirectoryStream(jar.getParent(), "troll-client-*.jar.part")) {
			for (Path p : parts) {
				Files.deleteIfExists(p);
			}
		} catch (IOException | RuntimeException e) {
			TrollClient.LOGGER.debug("Couldn't clear unfinished downloads", e);
		}
	}

	private static JsonObject metadata(Path file) throws IOException {
		try (ZipFile zip = new ZipFile(file.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null || entry.getSize() > 1_000_000) {
				return null;
			}
			try (InputStream in = zip.getInputStream(entry)) {
				JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
			}
		}
	}

	// ------------------------------------------------------------ helpers

	private static HttpRequest.Builder request(String url, int seconds) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(seconds)).header("user-agent", Site.userAgent()).GET();
	}

	private static Version parse(String version) throws Unsuitable {
		try {
			return Version.parse(version);
		} catch (VersionParsingException | RuntimeException e) {
			throw new Unsuitable("\"" + version + "\" isn't a version");
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/** A toast and a terminal line now, and a chat line as soon as there's a chat. */
	private static void announce(String text, boolean highlight) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			TerminalLog.push("update: " + status);
			Notifications.post("Updater", status, Icon.CUBE, highlight);
			if (mc.player != null) {
				Commands.info(text);
			} else {
				notice = text;
			}
		});
	}

	private static void say(String text) {
		Minecraft.getInstance().execute(() -> Commands.info(text));
	}
}
