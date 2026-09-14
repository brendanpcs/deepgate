package com.brendan.deepgate.home;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The order in which spots around a beacon are tried when placing an arriving player.
 *
 * <p>A home lands beside its beacon rather than on top of it, the way a bed puts you next to itself
 * rather than inside it. The search is deliberately bounded: a fixed radius, ordered nearest first,
 * so it is cheap, predictable, and always picks the same spot for the same world.
 *
 * <p>The beam column itself is excluded - standing in the beam is what opens the home screens, so
 * arriving there would reopen a dialog the moment you land.
 *
 * <p>Deliberately free of Minecraft imports so the ordering can be unit tested without the game.
 */
public final class ArrivalSearch {
	/**
	 * How far from the beam a player may be placed, per layer of the pyramid.
	 *
	 * <p>A bigger beacon reaches further: one layer gives one block of room, a full four-layer
	 * pyramid gives four. A stronger beacon is more work to build, so it earns a wider landing area.
	 */
	public static final int RADIUS_PER_LAYER = 1;

	/** The radius a beacon of this many layers searches within. */
	public static int radiusFor(int pyramidLayers) {
		return Math.max(1, pyramidLayers) * RADIUS_PER_LAYER;
	}

	/** How far above and below the top of the beacon to look. */
	public static final int VERTICAL_REACH = 2;

	private ArrivalSearch() {
	}

	/** A candidate position relative to the block directly above the beacon. */
	public record Offset(int dx, int dy, int dz) {
		/** Squared horizontal distance, used for ordering and for the radius test. */
		public int horizontalDistanceSquared() {
			return dx * dx + dz * dz;
		}
	}

	/**
	 * Every candidate spot for a beacon of this size, nearest first.
	 *
	 * <p>Ordered by horizontal distance, then by how far it is vertically from the top of the beacon,
	 * then by a fixed tiebreak so the result never depends on iteration order.
	 */
	public static List<Offset> candidatesFor(int pyramidLayers) {
		return candidates(radiusFor(pyramidLayers), VERTICAL_REACH);
	}

	public static List<Offset> candidates(int radius, int verticalReach) {
		List<Offset> offsets = new ArrayList<>();
		int limit = radius * radius;

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				// The beam column is where the home screens trigger, so never land in it.
				if (dx == 0 && dz == 0) {
					continue;
				}

				if (dx * dx + dz * dz > limit) {
					continue;
				}

				for (int dy = -verticalReach; dy <= verticalReach; dy++) {
					offsets.add(new Offset(dx, dy, dz));
				}
			}
		}

		offsets.sort(Comparator
				.comparingInt(Offset::horizontalDistanceSquared)
				.thenComparingInt(offset -> Math.abs(offset.dy()))
				// Prefer stepping up over dropping down: less chance of landing in a hole.
				.thenComparingInt(offset -> offset.dy() < 0 ? 1 : 0)
				.thenComparingInt(Offset::dx)
				.thenComparingInt(Offset::dz));

		return List.copyOf(offsets);
	}
}
