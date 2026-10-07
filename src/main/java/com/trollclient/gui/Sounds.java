package com.trollclient.gui;

import com.trollclient.module.ModuleManager;
import com.trollclient.module.client.ClickGuiModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.concurrent.ThreadLocalRandom;

/** Quiet UI feedback; every sound respects the ClickGUI "Sounds" toggle. */
public final class Sounds {
	private Sounds() {
	}

	private static boolean enabled() {
		ClickGuiModule gui = ModuleManager.get(ClickGuiModule.class);
		return gui == null || gui.sounds.get();
	}

	private static void play(SoundEvent sound, float pitch, float volume) {
		if (enabled()) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
		}
	}

	public static void click() {
		play(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f + ThreadLocalRandom.current().nextFloat() * 0.2f, 0.25f);
	}

	public static void toggle(boolean on) {
		play(SoundEvents.NOTE_BLOCK_HAT.value(), on ? 1.8f : 1.2f, 0.5f);
	}

	public static void tick() {
		play(SoundEvents.NOTE_BLOCK_HAT.value(), 2.0f, 0.15f);
	}

	public static void open() {
		play(SoundEvents.NOTE_BLOCK_PLING.value(), 2.0f, 0.2f);
	}
}
