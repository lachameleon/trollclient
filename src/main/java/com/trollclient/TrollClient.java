package com.trollclient;

import com.trollclient.command.Commands;
import com.trollclient.config.ConfigManager;
import com.trollclient.dev.DevShots;
import com.trollclient.dev.Showcase;
import com.trollclient.gui.hud.HudRenderer;
import com.trollclient.gui.title.TitleScreenGuard;
import com.trollclient.gui.tools.GuiToolsPanel;
import com.trollclient.macro.MacroManager;
import com.trollclient.module.ModuleManager;
import com.trollclient.module.chat.Announcer;
import com.trollclient.module.chat.ChatStyle;
import com.trollclient.module.chat.Typo;
import com.trollclient.module.player.SkinBlink;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Rotations;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrollClient implements ClientModInitializer {
	public static final String MOD_ID = "trollclient";
	public static final Logger LOGGER = LoggerFactory.getLogger("Troll Client");

	@Override
	public void onInitializeClient() {
		ModuleManager.init();
		ConfigManager.load();
		MacroManager.load();

		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			// requests are rebuilt from scratch every tick, before the player moves
			MovementControl.reset();
			Rotations.reset();
			ModuleManager.tick(mc);
			// after the modules, so a macro step asking to move or sneak outranks them
			if (mc.player != null && mc.level != null) {
				MacroManager.tick();
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ConfigManager.tick();
			MacroManager.saveTick();
			TitleScreenGuard.tick(mc);
		});
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			TitleScreenGuard.afterInit(mc, screen, width);
			GuiToolsPanel.attach(screen);
		});
		if (Showcase.enabled()) {
			Showcase.init();
			ClientTickEvents.END_CLIENT_TICK.register(mc -> Showcase.tick());
		}
		if (DevShots.enabled()) {
			DevShots.init();
			ClientTickEvents.END_CLIENT_TICK.register(mc -> DevShots.tick());
		}

		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> ModuleManager.entityAdded(entity));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			ModuleManager.worldLeft();
			MacroManager.worldLeft();
		});
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> !Commands.handle(message));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> ChatUtil.onPlayerChat(message, signed, sender));
		ClientReceiveMessageEvents.GAME.register(ChatUtil::onSystemChat);
		// every line, ours included, for macros' Wait Chat steps
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> MacroManager.onChat(message.getString()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				MacroManager.onChat(message.getString());
			}
		});
		// listeners run in order: the typo goes in first, then ChatStyle styles the result
		ClientSendMessageEvents.MODIFY_CHAT.register(message -> ModuleManager.get(Typo.class).modify(message));
		ClientSendMessageEvents.MODIFY_CHAT.register(message -> ModuleManager.get(ChatStyle.class).modify(message));
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			Announcer announcer = ModuleManager.get(Announcer.class);
			if (announcer.isEnabled()) {
				announcer.blockBroken(state);
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			ModuleManager.get(SkinBlink.class).restore();
			ConfigManager.save();
			MacroManager.save();
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), HudRenderer::render);
		LOGGER.info("Troll Client loaded. Right Shift opens the menu.");
	}
}
