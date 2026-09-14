package com.brendan.deepgate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.entity.BeaconBlockEntity;

/**
 * Reads the pyramid size a beacon has computed for itself.
 *
 * <p>{@code levels} is private and has no getter, but recomputing it would mean duplicating vanilla
 * pyramid scanning and then disagreeing with it the moment the rules change. This is an accessor
 * only: no behaviour is altered.
 */
@Mixin(BeaconBlockEntity.class)
public interface BeaconBlockEntityAccessor {
	@Accessor("levels")
	int deepgate$getLevels();
}
