package com.brendan.deepgate.home;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One beacon home (spec section 47).
 *
 * <p>The destination is not stored: a home arrives directly above its beacon, computed from
 * {@link #beacon()} at travel time (section 17). Storing a position would let the two drift apart if
 * the beacon were ever moved, and there would be no way to tell which was right.
 *
 * <p>Facing is stored, because where a player looks on arrival is a preference the world cannot
 * recreate.
 */
public record HomeRecord(
		UUID id,
		UUID owner,
		String name,
		ResourceKey<Level> dimension,
		BlockPos beacon,
		float yaw,
		float pitch,
		int beamColour) {

	/** Used when a beacon has no beam, or its colour has not been observed yet. */
	public static final int WHITE = 0xFFFFFF;

	public static final Codec<HomeRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(HomeRecord::id),
			UUIDUtil.CODEC.fieldOf("owner").forGetter(HomeRecord::owner),
			Codec.STRING.fieldOf("name").forGetter(HomeRecord::name),
			ResourceKey.codec(net.minecraft.core.registries.Registries.DIMENSION).fieldOf("dimension")
					.forGetter(HomeRecord::dimension),
			BlockPos.CODEC.fieldOf("beacon").forGetter(HomeRecord::beacon),
			Codec.FLOAT.fieldOf("yaw").forGetter(HomeRecord::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(HomeRecord::pitch),
			// Optional so records written before colours were stored still load.
			Codec.INT.optionalFieldOf("beam_colour", WHITE).forGetter(HomeRecord::beamColour)
	).apply(instance, HomeRecord::new));

	/** A copy under a new name, keeping the same identity so a rename is not a delete and recreate. */
	public HomeRecord renamedTo(String newName) {
		return new HomeRecord(id, owner, newName, dimension, beacon, yaw, pitch, beamColour);
	}

	/** A copy carrying a freshly observed beam colour. */
	public HomeRecord withBeamColour(int colour) {
		return new HomeRecord(id, owner, name, dimension, beacon, yaw, pitch, colour);
	}

	/** Whether this home is bound to the given beacon block. */
	public boolean isAt(ResourceKey<Level> level, BlockPos pos) {
		return dimension.equals(level) && beacon.equals(pos);
	}
}
