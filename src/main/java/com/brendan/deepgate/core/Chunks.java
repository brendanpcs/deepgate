package com.brendan.deepgate.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;

/** Chunk loading for arrivals. */
public final class Chunks {
	private Chunks() {
	}

	/**
	 * Pull in the chunk containing a position and its eight neighbours.
	 *
	 * <p>One chunk is not enough to judge an arrival. An unloaded chunk reads as air, so a player
	 * landing near a chunk boundary can pass a collision test against a wall that simply was not
	 * loaded yet, and a search for somewhere to stand can skip perfectly good ground for the same
	 * reason.
	 *
	 * <p>A three by three is the shape vanilla loads around a portal or gateway destination.
	 */
	public static void loadAround(ServerLevel level, BlockPos pos) {
		int chunkX = SectionPos.blockToSectionCoord(pos.getX());
		int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());

		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.getChunk(chunkX + dx, chunkZ + dz);
			}
		}
	}
}
