package com.brendan.deepgate.core;

import java.util.Locale;
import java.util.Optional;

/**
 * An amount of experience together with the unit it is counted in.
 *
 * <p>Each price carries its own unit, so a rule reads {@code 5 points} or {@code 3 levels} and there
 * is no separate switch to keep in step with it. That also lets the distance charge and the
 * dimensional surcharge use different units without either of them being ambiguous.
 *
 * <p>Deliberately free of Minecraft imports so parsing and formatting can be unit tested without the
 * game; the codec and command argument that wrap it live in the gamerule layer.
 */
public record Cost(int amount, Unit unit) {
	public static final Cost FREE = new Cost(0, Unit.POINTS);

	public enum Unit {
		POINTS("points"),
		LEVELS("levels");

		private final String label;

		Unit(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		/** Accepts the plural the rule is written with, and the singular for good measure. */
		public static Optional<Unit> parse(String raw) {
			String folded = raw.trim().toLowerCase(Locale.ROOT);

			return switch (folded) {
				case "points", "point", "xp" -> Optional.of(POINTS);
				case "levels", "level" -> Optional.of(LEVELS);
				default -> Optional.empty();
			};
		}
	}

	public boolean isFree() {
		return amount <= 0;
	}

	public boolean inLevels() {
		return unit == Unit.LEVELS;
	}

	/** Scale the amount, keeping the unit. Used to turn a per-1000-blocks rate into a real charge. */
	public Cost times(double factor) {
		return new Cost((int) Math.ceil(amount * factor), unit);
	}

	/** How the value is written in a command and stored in the world: "5 points". */
	@Override
	public String toString() {
		return amount + " " + unit.label();
	}

	/**
	 * Parse "5 points", "3 levels", or a bare "5" which is taken as points.
	 *
	 * @return empty when the text is not a valid cost
	 */
	public static Optional<Cost> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}

		String[] parts = raw.trim().split("\\s+");

		if (parts.length == 0 || parts[0].isEmpty() || parts.length > 2) {
			return Optional.empty();
		}

		int amount;

		try {
			amount = Integer.parseInt(parts[0]);
		} catch (NumberFormatException e) {
			return Optional.empty();
		}

		if (amount < 0) {
			return Optional.empty();
		}

		// A bare number means points, so the common case stays short to type.
		if (parts.length == 1) {
			return Optional.of(new Cost(amount, Unit.POINTS));
		}

		return Unit.parse(parts[1]).map(unit -> new Cost(amount, unit));
	}
}
