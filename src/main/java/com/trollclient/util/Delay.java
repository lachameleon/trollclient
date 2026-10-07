package com.trollclient.util;

import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;

import java.util.concurrent.ThreadLocalRandom;

/** Millisecond timer that understands the shared "Fixed / Random" delay settings. */
public final class Delay {
	private long last;

	public boolean passed(long ms) {
		return System.currentTimeMillis() - last >= ms;
	}

	public void reset() {
		last = System.currentTimeMillis();
	}

	public long elapsed() {
		return System.currentTimeMillis() - last;
	}

	/** Picks the next delay in ms from a Fixed/Random setting trio. */
	public static long roll(ModeSetting mode, NumberSetting fixed, NumberSetting min, NumberSetting max) {
		if (mode.is("Random")) {
			int lo = Math.min(min.getInt(), max.getInt());
			int hi = Math.max(min.getInt(), max.getInt());
			return lo == hi ? lo : ThreadLocalRandom.current().nextLong(lo, hi + 1L);
		}
		return fixed.getInt();
	}
}
