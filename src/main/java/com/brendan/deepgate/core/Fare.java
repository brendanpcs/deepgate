package com.brendan.deepgate.core;

/**
 * A priced teleport, held as separate point and level components (spec section 6).
 *
 * <p>Each cost rule carries its own unit, so a fare can be part points and part levels. They are
 * kept apart until the moment of charging because levels are not a fixed number of points: how much
 * three levels costs depends on where the payer stands.
 */
public record Fare(int points, int levels, double distance, boolean crossDimension) {
	public static final Fare FREE = new Fare(0, 0, 0.0D, false);

	/**
	 * Exact experience points to remove from a payer currently holding {@code payerTotalPoints}.
	 *
	 * <p>The level component is converted against the payer, then added to the flat point component,
	 * which is what makes "10 points and 2 levels" mean the obvious thing.
	 */
	public int pointsFor(int payerTotalPoints) {
		if (levels <= 0) {
			return Math.max(0, points);
		}

		// Take the flat points off first, so the levels are counted from what is actually left.
		int afterPoints = Math.max(0, payerTotalPoints - Math.max(0, points));
		return Math.max(0, points) + XpCurve.pointsForLevels(afterPoints, levels);
	}

	public boolean isFree() {
        return points <= 0 && levels <= 0;
	}

	/** Add another component, keeping the two units apart. */
	public Fare plus(Cost cost) {
		if (cost.isFree()) {
			return this;
		}

		return cost.inLevels()
				? new Fare(points, levels + cost.amount(), distance, crossDimension)
				: new Fare(points + cost.amount(), levels, distance, crossDimension);
	}

	/** How the fare reads to a player: "XP 15", "3 Levels", or "XP 10 + 2 Levels". */
	public String describe() {
		if (isFree()) {
			return "free";
		}

		if (levels <= 0) {
			return "XP " + points;
		}

		String levelPart = levels + (levels == 1 ? " Level" : " Levels");
		return points <= 0 ? levelPart : "XP " + points + " + " + levelPart;
	}
}
