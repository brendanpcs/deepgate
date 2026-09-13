package com.brendan.deepgate.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** A resolved arrival: exact level, exact position, exact facing. Deepgate never searches nearby. */
public record Destination(ServerLevel level, Vec3 position, float yaw, float pitch) {
}
