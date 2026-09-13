package com.brendan.deepgate.core;

/**
 * Whether a payer has to agree again before a quoted teleport actually charges them
 * (spec section 8).
 *
 * <p>A request stores a quote, not a charge. At commit time the fare is recalculated, and this is
 * the rule that decides whether the new number can be taken silently.
 *
 * <p>Deliberately free of Minecraft imports so the rule can be unit tested without the game.
 */
public final class ReapprovalPolicy {
	private ReapprovalPolicy() {
	}

	/**
	 * @param approvedAmount   the fare the payer last agreed to
	 * @param currentAmount    the fare as recalculated immediately before travel
	 * @param dimensionChanged whether the destination is in a different dimension than approved
	 * @return true when the payer must approve again before anything is charged
	 */
	public static boolean needsApproval(int approvedAmount, int currentAmount, boolean dimensionChanged) {
		// A change of destination dimension always requires renewed approval, even when the fare
		// happens to have fallen: the player agreed to go somewhere else entirely.
		if (dimensionChanged) {
			return true;
		}

		// At or below the approved fare, charge the real amount and travel. Only a rise needs consent.
		return currentAmount > approvedAmount;
	}
}
