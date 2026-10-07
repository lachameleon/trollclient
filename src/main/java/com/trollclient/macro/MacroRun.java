package com.trollclient.macro;

import com.trollclient.command.Commands;
import com.trollclient.gui.clickgui.TerminalLog;
import com.trollclient.util.MovementControl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One macro running. Steps run back to back within a tick until one waits;
 * a pass through the whole macro, or a jump backwards, always ends the tick,
 * so a loop can never freeze the game or fire a thousand packets at once.
 */
public final class MacroRun {
	/** Steps run per tick at most, even without waits. */
	private static final int BUDGET = 64;

	/** A Repeat in progress: steps [start, end) run again {@code remaining} more times. */
	private static final class Frame {
		final int start;
		final int end;
		int remaining;

		Frame(int start, int end, int remaining) {
			this.start = start;
			this.end = end;
			this.remaining = remaining;
		}
	}

	private final Macro macro;
	private final Deque<Frame> repeats = new ArrayDeque<>();
	private final Map<MacroStep, Integer> jumps = new IdentityHashMap<>();
	private int index;
	private int loop;
	private int jumpTo = -1;
	private boolean started;
	private int stepTicks;
	private long stepStart;
	private boolean finished;
	private String error;
	/** Sneak held by a Sneak step until released or the macro ends; null = not ours to decide. */
	Boolean sneak;

	MacroRun(Macro macro) {
		this.macro = macro;
	}

	public Macro macro() {
		return macro;
	}

	/** Step running (or next to run). */
	public int index() {
		return index;
	}

	/** Pass through the macro, counting from 0. */
	public int loop() {
		return loop;
	}

	public boolean isFinished() {
		return finished;
	}

	public String error() {
		return error;
	}

	/** Ticks the current step has already run for (0 on its first tick). */
	public int stepTicks() {
		return stepTicks;
	}

	public long stepMillis() {
		return System.currentTimeMillis() - stepStart;
	}

	public void setSneak(Boolean sneak) {
		this.sneak = sneak;
	}

	/** Makes the next step the one at {@code target}. */
	public void jump(int target) {
		jumpTo = target;
		// leaving a repeated block early: its Repeat is over
		repeats.removeIf(f -> target < f.start || target >= f.end);
	}

	/** Times this Goto has jumped during this pass. */
	public int jumpsOf(MacroStep step) {
		return jumps.getOrDefault(step, 0);
	}

	public void countJump(MacroStep step) {
		jumps.merge(step, 1, Integer::sum);
	}

	/** Repeats the {@code count} steps after the current one {@code more} more times. */
	public void repeatNext(int count, int more) {
		int start = index + 1;
		int end = Math.min(macro.steps.size(), start + count);
		if (end > start && more > 0) {
			repeats.push(new Frame(start, end, more));
		}
	}

	/** Ends the run with an error message (shown in chat and the editor). */
	public void fail(String message) {
		error = message;
		Commands.info("macro \"" + macro.getName() + "\" stopped at step " + (index + 1) + ": " + message);
		stop();
	}

	void stop() {
		if (finished) {
			return;
		}
		finished = true;
		List<MacroStep> steps = macro.steps;
		if (started && index < steps.size()) {
			steps.get(index).abort();
		}
		sneak = null;
		TerminalLog.push("macro --exit \"" + macro.getName().toLowerCase(Locale.ROOT) + "\"" + (error != null ? " (" + error + ")" : ""));
	}

	void tick() {
		if (finished) {
			return;
		}
		if (sneak != null) {
			MovementControl.sneak(MovementControl.PRIORITY_MACRO, sneak);
		}
		List<MacroStep> steps = macro.steps;
		for (int budget = BUDGET; budget > 0 && !finished; budget--) {
			if (index >= steps.size()) {
				loop++;
				int loops = macro.loops.getInt();
				if (steps.isEmpty() || (loops > 0 && loop >= loops)) {
					stop();
					return;
				}
				index = 0;
				repeats.clear();
				jumps.clear();
				return;
			}
			MacroStep step = steps.get(index);
			if (!step.isEnabled()) {
				advance();
				continue;
			}
			if (!started) {
				started = true;
				stepTicks = 0;
				stepStart = System.currentTimeMillis();
				step.start(this);
				if (finished) {
					return;
				}
			}
			MacroStep.Result result;
			try {
				result = step.tick(this);
			} catch (RuntimeException e) {
				fail(step.type().name() + " broke: " + e.getMessage());
				return;
			}
			if (finished) {
				return;
			}
			stepTicks++;
			switch (result) {
				case WAIT -> {
					return;
				}
				case STOP -> {
					stop();
					return;
				}
				case NEXT -> {
					if (advance()) {
						return;
					}
				}
			}
		}
	}

	/** Moves past the current step. Returns true if that jumped backwards (which ends the tick). */
	private boolean advance() {
		started = false;
		int from = index;
		if (jumpTo >= 0) {
			index = jumpTo;
			jumpTo = -1;
			return index <= from;
		}
		index++;
		while (!repeats.isEmpty() && index == repeats.peek().end) {
			Frame f = repeats.peek();
			if (f.remaining > 0) {
				f.remaining--;
				index = f.start;
				return false;
			}
			repeats.pop();
		}
		return false;
	}
}
