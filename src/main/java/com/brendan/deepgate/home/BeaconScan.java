package com.brendan.deepgate.home;

import java.util.List;
import java.util.Optional;

import com.brendan.deepgate.mixin.BeaconBlockEntityAccessor;

import net.minecraft.core.BlockPos;
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

	/** The beacon still standing at a recorded position, if the chunk is loaded and it is there. */
	public static Optional<Found> beaconAt(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return Optional.empty();
		}

		if (level.getBlockEntity(pos) instanceof BeaconBlockEntity beacon) {
			return Optional.of(new Found(pos, beacon, ((BeaconBlockEntityAccessor) beacon).deepgate$getLevels()));
		}

		return Optional.empty();
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
