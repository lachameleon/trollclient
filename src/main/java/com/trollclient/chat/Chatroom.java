package com.trollclient.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.trollclient.TrollClient;
import com.trollclient.dev.DevShots;
import com.trollclient.dev.Showcase;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ChatroomModule;
import com.trollclient.telemetry.Telemetry;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Site;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The chatroom: one room for everyone running the client, whatever server they're on. A WebSocket
 * to the website's Worker stays open while the Chatroom module is on; what people say is printed
 * into the normal chat tagged {@code [room]}, and {@code .chat <message>} talks.
 *
 * <p>Listening sends nothing about the player. The first time they talk, the Minecraft name is
 * sent and proven the way a server login proves it: we tell Mojang we're joining a one-off server
 * id, and the Worker asks Mojang whether this player did. The id is a hash of the Worker's challenge
 * and a nonce made here, so it can never be one a real server is waiting on, and the access token
 * goes to Mojang only. Accounts Mojang doesn't know (offline, dev runs) talk unverified.
 *
 * <p>The same proof is what makes someone a moderator: the site lists Minecraft accounts, not
 * passwords, and {@code .mod ...} asks the room to act for whoever this connection proved to be.
 *
 * <p>Socket callbacks arrive on HttpClient threads; everything they learn is handed to the client
 * thread through {@link #INBOX} and applied in {@link #tick()}.
 */
public final class Chatroom {
	private static final int MAX_LENGTH = 200;
	private static final int BACKLOG = 20;
	private static final long PING_MS = 30_000;
	private static final long RETRY_MIN_MS = 5_000;
	private static final long RETRY_MAX_MS = 300_000;

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final Gson GSON = new Gson();
	private static final Queue<Runnable> INBOX = new ConcurrentLinkedQueue<>();
	/** What was said lately, for {@code .chat} with nothing after it. */
	private static final Deque<Component> RECENT = new ArrayDeque<>();
	/** Frames from before the name was proven; sent as soon as it is. */
	private static final Deque<String> OUTBOX = new ArrayDeque<>();

	private static WebSocket socket;
	/** Bumped for every connection, so a late callback from an old one is ignored. */
	private static int generation;
	private static boolean connecting;
	private static boolean welcomed;
	private static String challenge = "";
	private static String name = "";
	private static boolean authed;
	private static boolean authing;
	private static boolean introduced;
	private static int online;
	private static long nextAttempt;
	private static long retryMs = RETRY_MIN_MS;
	private static long nextPing;
	private static CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
	private static boolean wasInWorld;

	private Chatroom() {
	}

	private static boolean wanted() {
		if (Showcase.enabled() || DevShots.enabled() || "false".equals(System.getProperty("trollclient.chatroom"))) {
			return false;
		}
		ChatroomModule module = ModuleManager.get(ChatroomModule.class);
		return module != null && module.isEnabled() && !Site.origin().isEmpty();
	}

	public static boolean connected() {
		return socket != null && welcomed;
	}

	public static int online() {
		return online;
	}

	/** End of every client tick: apply what the socket heard, keep it open, bring it back. */
	public static void tick(Minecraft mc) {
		for (Runnable r; (r = INBOX.poll()) != null; ) {
			r.run();
		}
		boolean inWorld = mc.player != null;
		if (inWorld && !wasInWorld) {
			joined();
		}
		wasInWorld = inWorld;
		if (!wanted()) {
			if (socket != null || connecting) {
				disconnect();
			}
			return;
		}
		long now = System.currentTimeMillis();
		if (socket == null && !connecting && now >= nextAttempt) {
			connect();
		} else if (socket != null && now >= nextPing) {
			nextPing = now + PING_MS;
			transmit("ping");
		}
	}

	/** World joined: one line saying the room exists and how full it is. */
	private static void joined() {
		ChatroomModule module = ModuleManager.get(ChatroomModule.class);
		if (connected() && module.joinNote.get()) {
			print(Component.literal(online + (online == 1 ? " player" : " players") + " in the room. ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(com.trollclient.command.Commands.prefix() + "chat <message>").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" talks to all of them.").withStyle(ChatFormatting.GRAY)));
		}
	}

	// ------------------------------------------------------------ talking

	/** {@code .chat <message>}. */
	public static void say(String text) {
		if (!wanted()) {
			note(Site.origin().isEmpty() ? "This build has no chatroom." : "The chatroom is off. Client > Chatroom turns it on.");
			return;
		}
		String clean = ChatUtil.sanitize(text);
		if (clean.length() > MAX_LENGTH) {
			clean = clean.substring(0, MAX_LENGTH);
		}
		if (clean.isEmpty()) {
			return;
		}
		if (!connected()) {
			nextAttempt = 0;
			note("Not connected to the chatroom yet. Try again in a moment.");
			return;
		}
		if (!introduced) {
			introduced = true;
			note("You're talking as " + Minecraft.getInstance().getUser().getName() + ". Everyone running Troll Client can read the room.");
		}
		Telemetry.count("chatroom_sent");
		JsonObject frame = new JsonObject();
		frame.addProperty("type", "say");
		frame.addProperty("text", clean);
		whenProven(GSON.toJson(frame));
	}

	/** {@code .mod ...}: the room decides whether this account may, and answers either way. */
	public static void moderate(String[] args) {
		if (!wanted() || !connected()) {
			note(wanted() ? "Not connected to the chatroom yet. Try again in a moment." : "Moderating goes through the chatroom, which is off.");
			return;
		}
		JsonArray list = new JsonArray();
		for (String arg : args) {
			list.add(arg);
		}
		JsonObject frame = new JsonObject();
		frame.addProperty("type", "mod");
		frame.add("args", list);
		whenProven(GSON.toJson(frame));
	}

	/** Sends a frame that needs a name behind it: now if the name is proven, otherwise right after it is. */
	private static void whenProven(String frame) {
		if (authed) {
			transmit(frame);
			return;
		}
		if (OUTBOX.size() < 5) {
			OUTBOX.add(frame);
		}
		authenticate();
	}

	/** {@code .chat} on its own: who's there and what was said lately. */
	public static void status() {
		if (!wanted()) {
			note(Site.origin().isEmpty() ? "This build has no chatroom." : "The chatroom is off. Client > Chatroom turns it on.");
			return;
		}
		if (!connected()) {
			note("Not connected to the chatroom yet.");
			return;
		}
		RECENT.forEach(Chatroom::print);
		note(online + (online == 1 ? " player" : " players") + " in the room. " + com.trollclient.command.Commands.prefix() + "chat <message> talks.");
	}

	/**
	 * Proves the Minecraft name to the room. The session service call is a web request, so it runs
	 * off the client thread; if Mojang says no (offline account, dev run) we join unverified.
	 */
	private static void authenticate() {
		if (authing || socket == null) {
			return;
		}
		authing = true;
		Minecraft mc = Minecraft.getInstance();
		User user = mc.getUser();
		String playerName = user.getName();
		String asked = challenge;
		int gen = generation;
		Thread thread = new Thread(() -> {
			byte[] random = new byte[16];
			new SecureRandom().nextBytes(random);
			String nonce = HexFormat.of().formatHex(random);
			boolean joined = false;
			try {
				mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId(asked, nonce));
				joined = true;
			} catch (AuthenticationException | RuntimeException e) {
				TrollClient.LOGGER.debug("Chatroom: Mojang didn't vouch for this account, joining unverified: {}", e.toString());
			}
			boolean verified = joined;
			INBOX.add(() -> {
				if (gen != generation || socket == null) {
					authing = false;
					return;
				}
				JsonObject auth = new JsonObject();
				auth.addProperty("type", "auth");
				auth.addProperty("name", playerName);
				if (verified) {
					auth.addProperty("nonce", nonce);
				}
				transmit(GSON.toJson(auth));
			});
		}, "Troll Client chatroom");
		thread.setDaemon(true);
		thread.start();
	}

	/** Must match website/src/chat.js: SHA-1 over the challenge, our nonce and a fixed tag, as hex. */
	private static String serverId(String challenge, String nonce) {
		try {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			return HexFormat.of().formatHex(sha1.digest((challenge + ":" + nonce + ":trollclient-chat").getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------ the connection

	private static void connect() {
		String url = Site.socket("/api/chat");
		if (url.isEmpty()) {
			return;
		}
		connecting = true;
		int gen = ++generation;
		HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8))
				.buildAsync(URI.create(url + "?role=game"), new Listener(gen))
				.whenComplete((ws, err) -> INBOX.add(() -> {
					if (gen != generation) {
						if (ws != null) {
							ws.abort();
						}
						return;
					}
					connecting = false;
					if (err != null) {
						TrollClient.LOGGER.debug("Chatroom: couldn't connect: {}", err.toString());
						retryLater();
						return;
					}
					socket = ws;
					nextPing = System.currentTimeMillis() + PING_MS;
				}));
	}

	/** Module switched off, or the game is closing. */
	public static void disconnect() {
		generation++;
		WebSocket ws = socket;
		reset();
		nextAttempt = 0;
		retryMs = RETRY_MIN_MS;
		if (ws != null) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").exceptionally(e -> null);
		}
	}

	private static void reset() {
		socket = null;
		connecting = false;
		welcomed = false;
		authed = false;
		authing = false;
		challenge = "";
		OUTBOX.clear();
		sending = CompletableFuture.completedFuture(null);
	}

	private static void dropped(int gen) {
		if (gen != generation) {
			return;
		}
		reset();
		retryLater();
	}

	/** Waits longer each time, so a site that's down isn't hammered by every client at once. */
	private static void retryLater() {
		nextAttempt = System.currentTimeMillis() + retryMs + (long) (Math.random() * retryMs / 2);
		retryMs = Math.min(RETRY_MAX_MS, retryMs * 2);
	}

	/** One frame at a time: the socket refuses a send while the last one is still going out. */
	private static void transmit(String text) {
		WebSocket ws = socket;
		if (ws == null) {
			return;
		}
		sending = sending.handle((ok, err) -> null).thenCompose(x -> ws.sendText(text, true)).exceptionally(e -> null);
	}

	private static final class Listener implements WebSocket.Listener {
		private final int gen;
		private final StringBuilder buffer = new StringBuilder();

		Listener(int gen) {
			this.gen = gen;
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				String text = buffer.toString();
				buffer.setLength(0);
				if (!text.equals("pong") && text.length() < 100_000) {
					INBOX.add(() -> {
						if (gen == generation) {
							receive(text);
						}
					});
				}
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
			INBOX.add(() -> dropped(gen));
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			INBOX.add(() -> dropped(gen));
		}
	}

	// ------------------------------------------------------------ what the room says (client thread)

	private static void receive(String text) {
		JsonObject msg;
		try {
			JsonElement parsed = JsonParser.parseString(text);
			if (!parsed.isJsonObject()) {
				return;
			}
			msg = parsed.getAsJsonObject();
			switch (string(msg, "type")) {
				case "welcome" -> {
					welcomed = true;
					retryMs = RETRY_MIN_MS;
					challenge = string(msg, "challenge");
					online = msg.has("online") ? msg.get("online").getAsInt() : 0;
					RECENT.clear();
					if (msg.has("history") && msg.get("history").isJsonArray()) {
						for (JsonElement e : msg.getAsJsonArray("history")) {
							remember(line(e.getAsJsonObject()));
						}
					}
				}
				case "ready" -> {
					authing = false;
					authed = true;
					name = string(msg, "name");
					for (String queued; (queued = OUTBOX.poll()) != null; ) {
						transmit(queued);
					}
				}
				case "msg" -> {
					JsonObject m = msg.getAsJsonObject("message");
					Component line = line(m);
					remember(line);
					boolean verified = m.has("verified") && m.get("verified").getAsBoolean();
					if (verified || !ModuleManager.get(ChatroomModule.class).verifiedOnly.get() || string(m, "name").equalsIgnoreCase(name)) {
						print(line);
					}
				}
				case "online" -> online = msg.get("online").getAsInt();
				case "mod" -> {
					note(string(msg, "text"));
					String key = string(msg, "key");
					if (!key.isEmpty()) {
						// never printed in full: it's a click away, and only in this player's own chat
						print(Component.literal("[click to copy the key]").withStyle(style -> style.withColor(ChatFormatting.WHITE).withUnderlined(true)
								.withClickEvent(new ClickEvent.CopyToClipboard(key))
								.withHoverEvent(new HoverEvent.ShowText(Component.literal("copies it to the clipboard")))));
					}
				}
				case "error" -> {
					authing = false;
					note(string(msg, "error"));
				}
				default -> {
				}
			}
		} catch (RuntimeException e) {
			// a frame that isn't what we expect is just skipped
			TrollClient.LOGGER.debug("Chatroom: odd message", e);
		}
	}

	private static String string(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}

	/** "Name: text", with a ~ on names Mojang didn't vouch for. Sanitized like anything else headed for chat. */
	private static Component line(JsonObject m) {
		boolean verified = m.has("verified") && m.get("verified").getAsBoolean();
		String who = ChatUtil.sanitize(string(m, "name"));
		String text = ChatUtil.sanitize(string(m, "text"));
		return Component.literal((verified ? "" : "~") + who).withStyle(verified ? ChatFormatting.WHITE : ChatFormatting.GRAY)
				.append(Component.literal(": " + text).withStyle(ChatFormatting.GRAY));
	}

	private static void remember(Component line) {
		RECENT.add(line);
		while (RECENT.size() > BACKLOG) {
			RECENT.poll();
		}
	}

	private static MutableComponent tag() {
		return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal("room").withStyle(ChatFormatting.WHITE))
				.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void print(Component line) {
		ChatUtil.print(tag().append(line));
	}

	private static void note(String text) {
		print(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}
}
