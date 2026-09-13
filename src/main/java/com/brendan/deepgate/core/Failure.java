package com.brendan.deepgate.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One reason a teleport was refused, carrying the exact text the player sees.
 *
 * <p>Spec section 52 requires a single primary failure, chosen by a fixed precedence, and messages
 * that state the corrective action ("In combat: 6s remaining") rather than a bare "Teleport failed".
 *
 * <p>Deliberately free of Minecraft imports so precedence can be unit tested without the game; the
 * command and dialog layers wrap {@link #message()} in a text component.
 */
public record Failure(Reason reason, String message) {
	public enum Reason {
		FEATURE_UNAVAILABLE,
		DESTINATION_MISSING,
		CROSS_DIMENSION_DISABLED,
		DESTINATION_INVALID,
		COMBAT,
		INSUFFICIENT_XP,
		PORTAL_INVALID,
		ACCESS_DENIED,
		DESTINATION_UNAVAILABLE
	}

	/** Precedence for player command teleports: TPA, spawn, home, back (section 52, first list). */
	public static final List<Reason> COMMAND_ORDER = List.of(
			Reason.FEATURE_UNAVAILABLE,
			Reason.DESTINATION_MISSING,
			Reason.CROSS_DIMENSION_DISABLED,
			Reason.DESTINATION_INVALID,
			Reason.COMBAT,
			Reason.INSUFFICIENT_XP);

	/** Precedence for Deepgate portal travel (section 52, second list). */
	public static final List<Reason> PORTAL_ORDER = List.of(
			Reason.PORTAL_INVALID,
			Reason.ACCESS_DENIED,
			Reason.CROSS_DIMENSION_DISABLED,
			Reason.COMBAT,
			Reason.DESTINATION_UNAVAILABLE);

	public static Failure of(Reason reason, String message) {
		return new Failure(reason, message);
	}

	/**
	 * The single failure to show, given everything that went wrong.
	 *
	 * <p>A reason absent from {@code order} sorts last rather than throwing, so a portal-only reason
	 * leaking into a command path degrades to "shown last" instead of crashing a teleport.
	 */
	public static Optional<Failure> primary(Collection<Failure> failures, List<Reason> order) {
		return failures.stream().min(Comparator.comparingInt(f -> {
			int index = order.indexOf(f.reason());
			return index < 0 ? Integer.MAX_VALUE : index;
		}));
	}

	/** Combat refusal phrased with the remaining wait, per section 52. */
	public static Failure combat(int secondsRemaining) {
		return new Failure(Reason.COMBAT, "In combat: " + secondsRemaining + "s remaining");
	}

	public static Failure crossDimensionDisabled() {
		return new Failure(Reason.CROSS_DIMENSION_DISABLED, "Cross-dimension travel is disabled");
	}

	/** Insufficient experience, phrased with the shortfall so the player knows how much to earn. */
	public static Failure insufficientXp(Fare fare, int held) {
		String unit = fare.inLevels() ? " levels" : " XP";
		int shortfall = Math.max(0, fare.amount() - held);
		return new Failure(Reason.INSUFFICIENT_XP,
				"Not enough experience: need " + fare.amount() + unit + ", short " + shortfall + unit);
	}
}
