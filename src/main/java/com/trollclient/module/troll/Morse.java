package com.trollclient.module.troll;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.MovementControl;
import com.trollclient.util.Targets;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Spells a message in Morse code with your crouch key. Somebody out there can read it. */
public class Morse extends Module {
	private static final Map<Character, String> CODE = Map.ofEntries(
			Map.entry('a', ".-"), Map.entry('b', "-..."), Map.entry('c', "-.-."), Map.entry('d', "-.."),
			Map.entry('e', "."), Map.entry('f', "..-."), Map.entry('g', "--."), Map.entry('h', "...."),
			Map.entry('i', ".."), Map.entry('j', ".---"), Map.entry('k', "-.-"), Map.entry('l', ".-.."),
			Map.entry('m', "--"), Map.entry('n', "-."), Map.entry('o', "---"), Map.entry('p', ".--."),
			Map.entry('q', "--.-"), Map.entry('r', ".-."), Map.entry('s', "..."), Map.entry('t', "-"),
			Map.entry('u', "..-"), Map.entry('v', "...-"), Map.entry('w', ".--"), Map.entry('x', "-..-"),
			Map.entry('y', "-.--"), Map.entry('z', "--.."), Map.entry('0', "-----"), Map.entry('1', ".----"),
			Map.entry('2', "..---"), Map.entry('3', "...--"), Map.entry('4', "....-"), Map.entry('5', "....."),
			Map.entry('6', "-...."), Map.entry('7', "--..."), Map.entry('8', "---.."), Map.entry('9', "----."),
			Map.entry('.', ".-.-.-"), Map.entry(',', "--..--"), Map.entry('?', "..--.."), Map.entry('!', "-.-.--"),
			Map.entry('/', "-..-."), Map.entry('@', ".--.-."), Map.entry('-', "-....-"), Map.entry('\'', ".----."));

	/** One step of the signal: on or off for a number of time units, tagged with the letter it belongs to. */
	private record Pulse(boolean on, int units, int letter) {
	}

	private final TextSetting message = add(new TextSetting("Message", "What to spell out", "sos troll client", 64))
			.onChange(this::rebuild);
	private final ModeSetting signal = add(new ModeSetting("Signal", "How each dot and dash is sent", "Crouch",
			"Crouch", "Swing", "Both"));
	private final NumberSetting unit = add(new NumberSetting("Unit", "Length of a dot; a dash is three", 3, 1, 10, 1).unit("t"));
	private final BoolSetting loop = add(new BoolSetting("Loop", "Start again after the last letter", true));
	private final NumberSetting gap = add(new NumberSetting("Loop Gap", "Pause before repeating", 2, 0, 10, 0.5).unit("s"))
			.visibleWhen(loop::get);
	private final BoolSetting onlyWatched = add(new BoolSetting("Only When Watched", "Pause unless a player is nearby to read it", false));
	private final NumberSetting watchRange = add(new NumberSetting("Watch Range", "How close a reader has to be", 16, 4, 64, 1)
			.unit("m")).visibleWhen(onlyWatched::get);

	private final List<Pulse> pulses = new ArrayList<>();
	private String clean = "";
	private int index;
	private int ticksLeft;
	private boolean finished;

	public Morse() {
		super("Morse", "Spells a message in Morse code by crouching (or swinging) on the spot.", Category.TROLL);
		rebuild();
	}

	public void setMessage(String text) {
		message.set(text);
	}

	private void rebuild() {
		pulses.clear();
		clean = message.get().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
		for (int i = 0; i < clean.length(); i++) {
			char c = clean.charAt(i);
			if (c == ' ') {
				// word gap is 7 units; the letter gap before it already gave 3
				pulses.add(new Pulse(false, 4, i));
				continue;
			}
			String code = CODE.get(c);
			if (code == null) {
				continue;
			}
			for (int k = 0; k < code.length(); k++) {
				pulses.add(new Pulse(true, code.charAt(k) == '-' ? 3 : 1, i));
				pulses.add(new Pulse(false, k == code.length() - 1 ? 3 : 1, i));
			}
		}
		index = 0;
		ticksLeft = 0;
		finished = false;
	}

	@Override
	protected void onEnable() {
		rebuild();
	}

	@Override
	public void onTick() {
		if (pulses.isEmpty() || finished) {
			return;
		}
		if (onlyWatched.get() && Targets.playersWithin(watchRange.get(), false).isEmpty()) {
			return;
		}
		if (ticksLeft <= 0) {
			if (index >= pulses.size()) {
				if (!loop.get()) {
					finished = true;
					return;
				}
				index = 0;
			}
			Pulse p = pulses.get(index++);
			ticksLeft = p.units() * unit.getInt();
			if (index >= pulses.size() && loop.get()) {
				ticksLeft += (int) (gap.get() * 20);
			}
			if (p.on() && !signal.is("Crouch")) {
				mc.player.swing(InteractionHand.MAIN_HAND);
			}
		}
		ticksLeft--;
		Pulse current = pulses.get(Math.max(0, index - 1));
		if (!signal.is("Swing")) {
			MovementControl.sneak(MovementControl.PRIORITY_SIGNAL, current.on());
		} else if (current.on() && ticksLeft % 4 == 0) {
			// keep the arm moving for the length of a dash
			mc.player.swing(InteractionHand.MAIN_HAND);
		}
	}

	/** Index of the character being sent right now, for the HUD. */
	public int currentLetter() {
		return pulses.isEmpty() ? -1 : pulses.get(Math.max(0, index - 1)).letter();
	}

	@Override
	public String getInfo() {
		if (finished || clean.isEmpty()) {
			return "done";
		}
		int at = currentLetter();
		if (at < 0 || at >= clean.length()) {
			return null;
		}
		char c = clean.charAt(at);
		return c == ' ' ? "_" : String.valueOf(c);
	}
}
