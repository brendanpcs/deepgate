package com.brendan.deepgate.home;

import java.util.List;
import java.util.Optional;

import com.brendan.deepgate.core.Chunks;
import com.brendan.deepgate.mixin.BeaconBlockEntityAccessor;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

/**
 * Finds the beacon whose beam a player is standing in (spec section 15).
 *
 * <p>The search runs <em>downward from the player</em> rather than outward from every beacon. A beam
 * only passes through blocks that do not block light, so the scan stops at the first block that
 * does - which means its cost is bounded by the height of the actual beam column, not by the world.
 * No beacon registry, no per-tick sweep, nothing to keep in sync (section 53).
 */
public final class BeaconScan {
	/** Vanilla allows at most four pyramid layers. */
	public static final int MAX_PYRAMID_LAYERS = 4;

	private BeaconScan() {
	}

	/** A beacon found beneath a player, with the pyramid size it currently has. */
	public record Found(BlockPos pos, BeaconBlockEntity beacon, int levels) {
		/** Whether this beacon is big enough to anchor a home right now. */
		public boolean qualifies(int requiredLayers) {
			return levels >= requiredLayers;
		}
	}

	/**
	 * The beacon directly below this player, if its beam reaches them.
	 *
	 * <p>Being in the beam means sharing the beacon's block column with an unobstructed path down to
	 * it. A beacon whose beam is blocked below the player is not reachable and is not returned, which
	 * is the same condition that makes an existing home temporarily unavailable (section 18).
	 */
	public static Optional<Found> beaconBelow(ServerPlayer player) {
		ServerLevel level = player.level();
		BlockPos feet = player.blockPosition();
		int bottom = level.getMinY();

		BlockPos.MutableBlockPos cursor = feet.mutable();

		for (int y = feet.getY(); y >= bottom; y--) {
			cursor.setY(y);

			if (!level.isLoaded(cursor)) {
				return Optional.empty();
			}

			if (level.getBlockEntity(cursor) instanceof BeaconBlockEntity beacon) {
				int levels = ((BeaconBlockEntityAccessor) beacon).deepgate$getLevels();
				return Optional.of(new Found(cursor.immutable(), beacon, levels));
			}

			// Anything that blocks light also blocks the beam, so the column ends here.
			if (y != feet.getY() && blocksBeam(level, cursor)) {
				return Optional.empty();
			}
		}

		return Optional.empty();
	}

	/**
	 * Whether a block would stop a beacon beam.
	 *
	 * <p>Vanilla lets a beam through anything that does not reduce light, which is why stained glass
	 * tints a beam instead of cutting it.
	 */
	private static boolean blocksBeam(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getLightDampening() > 0;
	}

	/** What was found at a recorded beacon position. */
	public enum Presence {
		/** A beacon is there. */
		PRESENT,
		/** The chunk is loaded and there is no beacon, so it is genuinely gone. */
		ABSENT,
		/** The chunk is not loaded, so nothing can be said either way. */
		UNKNOWN
	}

	/** A lookup result that distinguishes "no beacon" from "could not look". */
	public record Lookup(Presence presence, Optional<Found> found) {
	}

	/**
	 * Look for the beacon at a recorded position.
	 *
	 * @param forceLoad pull the chunk in so the answer is definitive. Travel does this, because a
	 *                  home should work at any distance; casual listing does not, because a player
	 *                  with homes scattered across the world would load a chunk for each one.
	 */
	public static Lookup lookup(ServerLevel level, BlockPos pos, boolean forceLoad) {
		if (forceLoad) {
			Chunks.loadAround(level, pos);
		} else if (!level.isLoaded(pos)) {
			return new Lookup(Presence.UNKNOWN, Optional.empty());
		}

		if (level.getBlockEntity(pos) instanceof BeaconBlockEntity beacon) {
			return new Lookup(Presence.PRESENT, Optional.of(new Found(pos, beacon, levelsOf(level, pos, beacon))));
		}

		return new Lookup(Presence.ABSENT, Optional.empty());
	}

	/**
	 * How many pyramid layers a beacon has, answered immediately.
	 *
	 * <p>A beacon works its own pyramid size out while it ticks, so the field it stores reads zero
	 * for a chunk that has only just been loaded. Travelling to a distant home does exactly that -
	 * loads the chunk and asks straight away - and would be told the beacon is too small when it is
	 * nothing of the sort.
	 *
	 * <p>So the stored value is used when it has been worked out, and the pyramid is measured
	 * directly when it has not. Measuring is a few hundred block reads at most and is only reached on
	 * a freshly loaded beacon.
	 */
	private static int levelsOf(ServerLevel level, BlockPos pos, BeaconBlockEntity beacon) {
		int known = ((BeaconBlockEntityAccessor) beacon).deepgate$getLevels();
		return known > 0 ? known : measurePyramid(level, pos);
	}

	/**
	 * Measure the pyramid under a beacon the way vanilla does: complete squares of beacon base
	 * blocks, each one wider than the last, counted upwards from the layer directly beneath.
	 */
	public static int measurePyramid(ServerLevel level, BlockPos beacon) {
		int layers = 0;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int layer = 1; layer <= MAX_PYRAMID_LAYERS; layer++) {
			int y = beacon.getY() - layer;

			if (y < level.getMinY()) {
				break;
			}

			if (!isCompleteLayer(level, beacon, cursor, layer, y)) {
				break;
			}

			layers = layer;
		}

		return layers;
	}

	private static boolean isCompleteLayer(ServerLevel level, BlockPos beacon,
			BlockPos.MutableBlockPos cursor, int layer, int y) {
		for (int x = beacon.getX() - layer; x <= beacon.getX() + layer; x++) {
			for (int z = beacon.getZ() - layer; z <= beacon.getZ() + layer; z++) {
				cursor.set(x, y, z);

				if (!level.getBlockState(cursor).is(BlockTags.BEACON_BASE_BLOCKS)) {
					return false;
				}
			}
		}

		return true;
	}

	/** The beacon still standing at a recorded position, without forcing the chunk to load. */
	public static Optional<Found> beaconAt(ServerLevel level, BlockPos pos) {
		return lookup(level, pos, false).found();
	}

	/**
	 * Whether the beam of a beacon is currently unobstructed.
	 *
	 * <p>A beacon with no beam sections is either blocked or has no pyramid; either way its home is
	 * temporarily unavailable rather than deleted (section 18).
	 */
	public static boolean hasBeam(BeaconBlockEntity beacon) {
		return !beacon.getBeamSections().isEmpty();
	}

	/**
	 * The colour the beam ends up, after every pane of stained glass it passes through.
	 *
	 * <p>Vanilla starts a new beam section each time the colour changes and blends the new glass into
	 * the running colour, so stacking two different glasses gives a mixed result. Reading the topmost
	 * section therefore picks up custom colours and stacking for free, with no colour maths here.
	 *
	 * @return packed RGB, white when the beacon has no beam
	 */
	public static int beamColour(BeaconBlockEntity beacon) {
		List<BeaconBeamOwner.Section> sections = beacon.getBeamSections();

		if (sections.isEmpty()) {
			return HomeRecord.WHITE;
		}

		return sections.get(sections.size() - 1).getColor() & 0xFFFFFF;
	}
}
