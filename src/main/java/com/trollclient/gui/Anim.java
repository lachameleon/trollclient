package com.trollclient.gui;

/**
 * Frame-rate independent smoothed value. {@link #update(float)} moves the value
 * towards the target with exponential easing; {@code speed} is roughly
 * "how many times per second it closes most of the gap".
 */
public final class Anim {
	private float value;
	private float target;
	private final float speed;

	public Anim(float initial, float speed) {
		this.value = initial;
		this.target = initial;
		this.speed = speed;
	}

	public Anim(float speed) {
		this(0, speed);
	}

	public float update(float dt) {
		float k = 1f - (float) Math.exp(-speed * dt * Motion.speed());
		value += (target - value) * k;
		if (Math.abs(target - value) < 0.0005f) {
			value = target;
		}
		return value;
	}

	public Anim target(float target) {
		this.target = target;
		return this;
	}

	public Anim target(boolean on) {
		return target(on ? 1f : 0f);
	}

	public void snap(float v) {
		this.value = v;
		this.target = v;
	}

	public float get() {
		return value;
	}

	public float getTarget() {
		return target;
	}

	public boolean isSettled() {
		return value == target;
	}
}
