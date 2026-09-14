package com.brendan.deepgate.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A resolved arrival: exact level, exact position, exact facing. Deepgate never searches nearby.
 *
 * <p>{@code loadChunks} says whether the destination area has to be pulled in before it can be
 * judged. Every destination Deepgate resolves from stored data needs it - a home, a spawn point, an
 * undo record - because the place may be far from anyone and therefore not loaded.
 *
 * <p>Travelling to another player is the exception: they are standing there, so their surroundings
 * are loaded by definition and there is nothing to fetch.
 */
public record Destination(ServerLevel level, Vec3 position, float yaw, float pitch, boolean loadChunks) {
	/** A destination whose area must be loaded before it can be judged. */
	public Destination(ServerLevel level, Vec3 position, float yaw, float pitch) {
		this(level, position, yaw, pitch, true);
	}

	/** A destination that is already loaded, because a player is standing in it. */
	public static Destination atPlayer(ServerLevel level, Vec3 position, float yaw, float pitch) {
		return new Destination(level, position, yaw, pitch, false);
	}
}
