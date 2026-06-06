package com.aionemu.gameserver.utils;

public final class IntRange {

	private final int minimum;
	private final int maximum;

	public IntRange(int minimum, int maximum) {
		this.minimum = minimum;
		this.maximum = maximum;
	}

	public int getMinimumInteger() {
		return minimum;
	}

	public int getMaximumInteger() {
		return maximum;
	}

	public boolean containsInteger(int value) {
		return value >= minimum && value <= maximum;
	}

	@Override
	public String toString() {
		return minimum + "-" + maximum;
	}
}