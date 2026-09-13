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
	 * Price a move of {@code distance} blocks, measured in the destination's coordinate frame.
	 *
	 * <p>The cross-dimension charge is <em>additive</em>, never a multiplier, and applies even when
	 * the distance component came out free.
	 */
	public static Fare quote(double distance, boolean crossDimension, RuleSnapshot rules) {
		int distanceFare = distanceFare(distance, rules);
		int total = distanceFare + (crossDimension ? Math.max(0, rules.xpCrossDimension()) : 0);

		return new Fare(total, rules.xpCostInLevels(), distance, crossDimension);
	}

	/** The distance component alone, before any dimensional surcharge. */
	static int distanceFare(double distance, RuleSnapshot rules) {
		if (distance < rules.xpFreeDistance()) {
			return 0;
		}

		if (rules.xpCostPer1k() <= 0) {
			return 0;
		}

		double raw = distance / 1000.0D * rules.xpCostPer1k();
		return (int) Math.ceil(raw);
	}
}
