package com.trollclient.module.player;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Flickers your skin's outer layers (hat, jacket, sleeves, cape) on and off for everyone to see. */
public class SkinBlink extends Module {
	private static final PlayerModelPart[] ORDER = {
			PlayerModelPart.HAT, PlayerModelPart.JACKET, PlayerModelPart.LEFT_SLEEVE, PlayerModelPart.LEFT_PANTS_LEG,
			PlayerModelPart.RIGHT_PANTS_LEG, PlayerModelPart.RIGHT_SLEEVE, PlayerModelPart.CAPE
	};

	private final ModeSetting pattern = add(new ModeSetting("Pattern", "How the layers flicker", "Wave",
			"Blink", "Wave", "Random", "Strobe"));
	private final NumberSetting interval = add(new NumberSetting("Interval", "Ticks between changes", 3, 1, 20, 1).unit("t"));
	private final BoolSetting cape = add(new BoolSetting("Cape", "Include your cape", true));
	private final ModeSetting trigger = add(new ModeSetting("Trigger", "Only blink while this is happening; otherwise your skin looks normal",
			"Always", "Always", "In Air", "On Ground", "Moving", "Still", "Sneaking", "Sprinting", "Hurt", "Using Item"));

	/** True while the trigger held last tick, so layers are put back once when it stops. */
	private boolean blinking;

	private final Map<PlayerModelPart, Boolean> original = new EnumMap<>(PlayerModelPart.class);
	private int ticks;
	private int step;

	public SkinBlink() {
		super("SkinBlink", "Flickers your hat, jacket, sleeves, trousers and cape so your skin strobes for everyone.", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		blinking = false;
		// captured lazily on the first tick: the config can enable us before options exist
		original.clear();
		ticks = 0;
		step = 0;
	}

	@Override
	protected void onDisable() {
		restore();
	}

	@Override
	public void onWorldLeave() {
		restore();
	}

	/** Puts your skin layers back exactly how they were, so options.txt never saves a blinked state. */
	public void restore() {
		if (original.isEmpty() || mc.options == null) {
			return;
		}
		for (Map.Entry<PlayerModelPart, Boolean> e : original.entrySet()) {
			mc.options.setModelPart(e.getKey(), e.getValue());
		}
		original.clear();
		if (mc.player != null) {
			mc.options.broadcastOptions();
		}
	}

	@Override
	public void onTick() {
		if (original.isEmpty()) {
			for (PlayerModelPart part : PlayerModelPart.values()) {
				original.put(part, mc.options.isModelPartEnabled(part));
			}
		}
		if (!triggered()) {
			if (blinking) {
				blinking = false;
				for (Map.Entry<PlayerModelPart, Boolean> e : original.entrySet()) {
					mc.options.setModelPart(e.getKey(), e.getValue());
				}
				mc.options.broadcastOptions();
			}
			return;
		}
		blinking = true;
		if (++ticks < interval.getInt()) {
			return;
		}
		ticks = 0;
		step++;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int i = 0; i < ORDER.length; i++) {
			PlayerModelPart part = ORDER[i];
			if (part == PlayerModelPart.CAPE && !cape.get()) {
				continue;
			}
			boolean on = switch (pattern.get()) {
				case "Blink" -> step % 2 == 0;
				case "Random" -> rnd.nextBoolean();
				case "Strobe" -> step % 4 == 0;
				// a single gap travels up the body: hat, jacket, arms, legs...
				default -> i != step % ORDER.length;
			};
			mc.options.setModelPart(part, on);
		}
		// one settings packet for the whole change
		mc.options.broadcastOptions();
	}

	private boolean triggered() {
		var p = mc.player;
		boolean moving = p.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4;
		return switch (trigger.get()) {
			case "In Air" -> !p.onGround();
			case "On Ground" -> p.onGround();
			case "Moving" -> moving;
			case "Still" -> !moving;
			case "Sneaking" -> p.isShiftKeyDown();
			case "Sprinting" -> p.isSprinting();
			case "Hurt" -> p.hurtTime > 0;
			case "Using Item" -> p.isUsingItem();
			default -> true;
		};
	}

	@Override
	public String getInfo() {
		return trigger.is("Always") ? pattern.get() : pattern.get() + " " + trigger.get().toLowerCase(java.util.Locale.ROOT);
	}
}
