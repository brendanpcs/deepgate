package com.brendan.deepgate.core;

/**
 * The single fare engine shared by TPA, {@code /spawn} and {@code /home} (spec section 4).
 *
 * <p>Deepgate portal travel never reaches this class: portals are free in V1 Lite, and {@code /back}
 * is free and refunds the original charge rather than pricing a new one.
 *
 * <p>Deliberately free of Minecraft imports so it can be unit tested without the game.
 */
public final class Pricing {
	private Pricing() {
	}

	/**
	 * Price a move of {@code distance} blocks, measured in the coordinate frame of the destination.
	 *
	 * <p>The cross-dimension charge is <em>additive</em>, never a multiplier, and applies even when
	 * the distance component came out free. Each component keeps the unit its own rule was set in.
	 */
	public static Fare quote(double distance, boolean crossDimension, RuleSnapshot rules) {
		Fare fare = new Fare(0, 0, distance, crossDimension);

		fare = fare.plus(distanceCost(distance, rules));

		if (crossDimension) {
			fare = fare.plus(rules.xpCrossDimension());
		}

		return fare;
	}

	/** The distance component alone, before any dimensional surcharge. */
	static Cost distanceCost(double distance, RuleSnapshot rules) {
		if (distance < rules.xpFreeDistance()) {
			return Cost.FREE;
		}

		Cost rate = rules.xpCostPer1k();

		if (rate.isFree()) {
			return Cost.FREE;
		}

		return rate.times(distance / 1000.0D);
	}
}
