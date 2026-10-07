package com.trollclient.gui.menus;

import com.trollclient.TrollClient;
import com.trollclient.gui.Draw;
import com.trollclient.gui.Theme;
import com.trollclient.util.ColorUtil;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The multiplayer server list, Troll Client style. Adding, editing and connecting still go through vanilla. */
public class TrollServersScreen extends TrollListScreen<ServerData> {
	private static final ExecutorService PING_POOL = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "troll-server-pinger");
		t.setDaemon(true);
		return t;
	});

	private final ServerList servers;
	private final ServerStatusPinger pinger = new ServerStatusPinger();
	private final List<ServerData> lan = new ArrayList<>();
	private LanServerDetection.LanServerList lanList;
	private LanServerDetection.LanServerDetector lanDetector;
	private final java.util.Map<ServerData, net.minecraft.client.gui.screens.FaviconTexture> icons = new java.util.HashMap<>();
	private final java.util.Map<ServerData, byte[]> iconBytes = new java.util.HashMap<>();

	/** Server favicon texture, re-uploaded whenever the ping brings new icon bytes. */
	private net.minecraft.client.gui.screens.FaviconTexture icon(ServerData d) {
		byte[] bytes = d.getIconBytes();
		if (bytes == null) {
			return null;
		}
		net.minecraft.client.gui.screens.FaviconTexture tex = icons.computeIfAbsent(d,
				k -> net.minecraft.client.gui.screens.FaviconTexture.forServer(minecraft.getTextureManager(), d.ip));
		if (iconBytes.get(d) != bytes) {
			iconBytes.put(d, bytes);
			try {
				tex.upload(com.mojang.blaze3d.platform.NativeImage.read(bytes));
			} catch (Exception e) {
				tex.clear();
				return null;
			}
		}
		return tex;
	}

	public TrollServersScreen(Screen parent) {
		super(Component.literal("Multiplayer"), parent);
		servers = new ServerList(minecraft);
		servers.load();
		status("cat servers.dat  (" + servers.size() + " saved)");
	}

	@Override
	protected void init() {
		if (lanDetector == null) {
			try {
				lanList = new LanServerDetection.LanServerList();
				lanDetector = new LanServerDetection.LanServerDetector(lanList);
				lanDetector.start();
			} catch (Exception e) {
				TrollClient.LOGGER.warn("LAN detection unavailable: {}", e.getMessage());
			}
		}
	}

	@Override
	public void removed() {
		if (lanDetector != null) {
			lanDetector.interrupt();
			lanDetector = null;
		}
		pinger.removeAll();
		icons.values().forEach(net.minecraft.client.gui.screens.FaviconTexture::close);
		icons.clear();
		iconBytes.clear();
	}

	@Override
	protected void tickScreen() {
		pinger.tick();
		if (lanList != null) {
			List<LanServer> found = lanList.takeDirtyServers();
			if (found != null) {
				lan.clear();
				for (LanServer s : found) {
					lan.add(new ServerData(s.getMotd(), s.getAddress(), ServerData.Type.LAN));
				}
			}
		}
		for (ServerData d : entries()) {
			if (d.state() == ServerData.State.INITIAL) {
				ping(d);
			}
		}
	}

	private void ping(ServerData d) {
		d.setState(ServerData.State.PINGING);
		d.motd = Component.empty();
		d.status = Component.empty();
		PING_POOL.submit(() -> {
			try {
				pinger.pingServer(d, () -> minecraft.execute(servers::save), () -> d.setState(
						d.protocol == SharedConstants.getCurrentVersion().protocolVersion()
								? ServerData.State.SUCCESSFUL : ServerData.State.INCOMPATIBLE),
						EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()));
			} catch (Exception e) {
				d.setState(ServerData.State.UNREACHABLE);
				d.motd = Component.literal("can't resolve that address");
			}
		});
	}

	@Override
	protected List<ServerData> entries() {
		List<ServerData> all = new ArrayList<>();
		for (int i = 0; i < servers.size(); i++) {
			all.add(servers.get(i));
		}
		all.addAll(lan);
		return all;
	}

	@Override
	protected String searchText(ServerData d) {
		return d.name + " " + d.ip;
	}

	@Override
	protected String heading() {
		return "multiplayer";
	}

	@Override
	protected String tagline() {
		return "where the trolling happens.";
	}

	@Override
	protected String headerInfo() {
		return servers.size() + " saved" + (lan.isEmpty() ? "" : " / " + lan.size() + " lan");
	}

	@Override
	protected String emptyText() {
		return "no servers yet. press \"add\"";
	}

	@Override
	protected void drawRow(GuiGraphicsExtractor g, ServerData d, float x, float y, float width, float h, float hover, boolean selected) {
		float lit = Math.max(hover, selected ? 1 : 0);
		float is = h - 6;
		Draw.outline(g, x - 1, y + 2, is + 2, is + 2, Theme.border);
		net.minecraft.client.gui.screens.FaviconTexture tex = icon(d);
		if (tex != null && !tex.isClosed()) {
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex.textureLocation(), Math.round(x), Math.round(y + 3),
					0, 0, Math.round(is), Math.round(is), Math.round(is), Math.round(is));
		} else Draw.textCentered(g, d.isLan() ? "LAN" : d.name.isEmpty() ? "?" : d.name.substring(0, 1).toUpperCase(Locale.ROOT),
				x + is / 2f, y + 3 + is / 2f - 4, Theme.textDim);

		// signal bars on the right, or the state while there's no ping yet
		float right = x + width - 6;
		ServerData.State st = d.state();
		if (st == ServerData.State.SUCCESSFUL || st == ServerData.State.INCOMPATIBLE) {
			int bars = d.ping < 0 ? 0 : d.ping < 150 ? 5 : d.ping < 300 ? 4 : d.ping < 600 ? 3 : d.ping < 1000 ? 2 : 1;
			for (int i = 0; i < 5; i++) {
				float bh = 2 + i * 2;
				Draw.rect(g, right - 24 + i * 5, y + 15 - bh, 3, bh, i < bars ? Theme.text : Theme.border);
			}
			String ms = d.ping + "ms";
			Draw.textRight(g, ms, right - 28, y + 6, Theme.textDim);
			if (d.players != null) {
				Draw.textRight(g, d.players.online() + "/" + d.players.max(), right, y + 18, Theme.textDim);
			}
		} else {
			String label = d.isLan() ? "lan" : st == ServerData.State.UNREACHABLE ? "offline" : "pinging...";
			Draw.textRight(g, label, right, y + 6, Theme.textDim);
		}
		float tx = x + is + 8;
		Draw.text(g, Draw.ellipsize(d.name, (int) (right - tx - 70)), tx, y + 5, ColorUtil.lerp(Theme.textDim, Theme.text, Math.max(lit, 0.6f)));
		String motd = d.motd == null ? "" : d.motd.getString().replace('\n', ' ');
		if (st == ServerData.State.INCOMPATIBLE && d.version != null) {
			motd = "needs " + d.version.getString();
		}
		if (st == ServerData.State.SUCCESSFUL && d.motd != null && !motd.isEmpty()) {
			// the server's own colours and formatting, first line only, clipped to the row
			g.enableScissor(Math.round(tx), Math.round(y + 13), Math.round(right - 40), Math.round(y + 24));
			net.minecraft.network.chat.MutableComponent line = net.minecraft.network.chat.Component.empty();
			for (net.minecraft.network.chat.Component part : d.motd.toFlatList()) {
				String t = part.getString();
				int nl = t.indexOf('\n');
				line.append(net.minecraft.network.chat.Component.literal(nl >= 0 ? t.substring(0, nl) : t).withStyle(part.getStyle()));
				if (nl >= 0) {
					break;
				}
			}
			g.text(Draw.font(), line, Math.round(tx), Math.round(y + 15), ColorUtil.fade(0xFFAAAAAA, Draw.alpha), false);
			g.disableScissor();
		} else {
			Draw.text(g, Draw.ellipsize(motd.isEmpty() ? d.ip : motd, (int) (right - tx - 40)), tx, y + 15, Theme.textDim);
		}
		Draw.text(g, Draw.ellipsize(d.ip, (int) (right - tx - 40)), tx, y + 24, ColorUtil.fade(Theme.textDim, 0.6f));
	}

	@Override
	protected void activate(ServerData d) {
		status("ssh " + d.ip);
		ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(d.ip), d, false, null);
	}

	@Override
	protected List<Action> actions() {
		return List.of(
				new Action("join", "enter", () -> selectedEntry() != null, () -> activate(selectedEntry())),
				new Action("direct", null, () -> true, this::direct),
				new Action("add", null, () -> true, this::add),
				new Action("edit", null, () -> selectedEntry() != null && !selectedEntry().isLan(), () -> edit(selectedEntry())),
				new Action("delete", "del", () -> selectedEntry() != null && !selectedEntry().isLan(), () -> deleteRequested(selectedEntry())),
				new Action("refresh", "f5", () -> true, this::refresh),
				new Action("back", "esc", () -> true, this::onClose));
	}

	private void refresh() {
		minecraft.gui.setScreen(new TrollServersScreen(parent));
	}

	private void direct() {
		ServerData d = new ServerData("Minecraft Server", "", ServerData.Type.OTHER);
		minecraft.gui.setScreen(new DirectJoinServerScreen(this, ok -> {
			if (ok) {
				activate(d);
			} else {
				minecraft.gui.setScreen(this);
			}
		}, d));
	}

	private void add() {
		ServerData d = new ServerData("Minecraft Server", "", ServerData.Type.OTHER);
		minecraft.gui.setScreen(new ManageServerScreen(this, Component.translatable("manageServer.add.title"), ok -> {
			if (ok) {
				ServerData hidden = servers.unhide(d.ip);
				if (hidden != null) {
					hidden.copyNameIconFrom(d);
				} else {
					servers.add(d, false);
				}
				servers.save();
				status("echo " + d.ip + " >> servers.dat");
			}
			minecraft.gui.setScreen(this);
		}, d));
	}

	private void edit(ServerData d) {
		if (d == null || d.isLan()) {
			return;
		}
		ServerData copy = new ServerData(d.name, d.ip, ServerData.Type.OTHER);
		copy.copyFrom(d);
		minecraft.gui.setScreen(new ManageServerScreen(this, Component.translatable("manageServer.edit.title"), ok -> {
			if (ok) {
				d.copyFrom(copy);
				d.setState(ServerData.State.INITIAL);
				servers.save();
			}
			minecraft.gui.setScreen(this);
		}, copy));
	}

	@Override
	protected void deleteRequested(ServerData d) {
		if (d == null || d.isLan()) {
			return;
		}
		minecraft.gui.setScreen(new ConfirmScreen(ok -> {
			if (ok) {
				servers.remove(d);
				servers.save();
				status("rm " + d.ip);
			}
			minecraft.gui.setScreen(this);
		}, Component.literal("Remove \"" + d.name + "\"?"), Component.literal("It'll be gone from your list."),
				Component.literal("Remove"), Component.literal("Keep it")));
	}

	@Override
	protected boolean extraKey(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_F5) {
			refresh();
		}
		return true;
	}
}
