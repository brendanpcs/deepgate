package com.brendan.deepgate.core;

/**
 * A priced teleport, in whichever unit {@code deepgate:xp_cost_in_levels} selects (spec section 6).
 *
 * <p>{@code amount} is what the player is shown: raw experience points when {@code inLevels} is
 * false, whole levels when it is true. {@link #pointsFor(int)} resolves that into the exact point
 * count to remove, which is what gets stored for the {@code /back} refund.
 */
public record Fare(int amount, boolean inLevels, double distance, boolean crossDimension) {
	public static final Fare FREE = new Fare(0, false, 0.0D, false);

	/**
	 * Exact experience points to remove from a payer currently holding {@code payerTotalPoints}.
	 *
	 * <p>In levels mode this depends on where the payer stands, because levels are not a fixed
	 * number of points - see {@link XpCurve#pointsForLevels(int, int)}.
	 */
	public int pointsFor(int payerTotalPoints) {
		if (amount <= 0) {
			return 0;
		}

		return inLevels ? XpCurve.pointsForLevels(payerTotalPoints, amount) : amount;
	}

	public boolean isFree() {
		return amount <= 0;
	}
}
