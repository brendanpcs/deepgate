package com.brendan.deepgate.core;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where a player was standing before their last Deepgate teleport, and what it cost them
 * (spec section 20).
 *
 * <p>{@code xpPointsRemoved} is the exact point count taken by the original transaction, so the
 * refund is exact rather than re-derived from the player's current level. Records are memory-only
 * and do not survive a restart (section 48).
 */
public record BackRecord(
		ResourceKey<Level> dimension,
		Vec3 position,
		float yaw,
		float pitch,
		int xpPointsRemoved,
		long expiresAtTick) {

	public boolean isExpired(long currentTick) {
		return currentTick >= expiresAtTick;
	}
}
