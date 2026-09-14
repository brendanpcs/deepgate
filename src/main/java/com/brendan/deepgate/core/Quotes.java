package com.brendan.deepgate.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Turns two points in the world into a fare (spec sections 4 and 5).
 *
 * <p>Distance is always measured in the coordinate frame of the destination, using the real
 * {@code coordinateScale} of each dimension rather than assuming the Nether ratio, so a custom
 * dimension is priced by its own numbers.
 */
public final class Quotes {
	private Quotes() {
	}

	/** Distance from a player to a point, measured in the frame of the destination level. */
	public static double distance(ServerPlayer mover, ServerLevel destinationLevel, Vec3 destination) {
		ServerLevel origin = mover.level();

		return GeoMath.distance(
				mover.getX(), mover.getY(), mover.getZ(), origin.dimensionType().coordinateScale(),
				destination.x(), destination.y(), destination.z(),
				destinationLevel.dimensionType().coordinateScale());
	}

	public static boolean isCrossDimension(ServerPlayer mover, ServerLevel destinationLevel) {
		return !mover.level().dimension().equals(destinationLevel.dimension());
	}

	/**
	 * The fare for moving {@code mover} to a point in {@code destinationLevel}.
	 *
	 * <p>Creative and spectator players travel free. Experience is a survival resource, and charging
	 * it to someone who cannot meaningfully hold it means a creative player with an empty bar is
	 * refused the teleport outright - the opposite of what those modes are for.
	 *
	 * <p>The distance and dimension are still measured and reported, so the screen reads
	 * "Distance: 1240 blocks" alongside a free fare rather than pretending the trip was nothing.
	 */
	public static Fare quote(ServerPlayer mover, ServerLevel destinationLevel, Vec3 destination,
			RuleSnapshot rules) {
		double distance = distance(mover, destinationLevel, destination);
		boolean crossDimension = isCrossDimension(mover, destinationLevel);

		if (travelsFree(mover)) {
			return new Fare(0, 0, distance, crossDimension);
		}

		return Pricing.quote(distance, crossDimension, rules);
	}

	/** Whether this player bypasses fares entirely. */
	public static boolean travelsFree(ServerPlayer player) {
		return player.isCreative() || player.isSpectator();
	}

	/** The fare for moving {@code mover} to wherever another player is standing right now. */
	public static Fare quote(ServerPlayer mover, ServerPlayer destinationPlayer, RuleSnapshot rules) {
		return quote(mover, destinationPlayer.level(), destinationPlayer.position(), rules);
	}
}
