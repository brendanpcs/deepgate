package com.brendan.deepgate.spawn;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.core.TxnResource;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One charge from a respawn anchor, taken as part of a {@code /spawn} transaction (spec section 12).
 *
 * <p>Section 12 asks for the experience charge, the anchor charge and the teleport to commit
 * atomically. Modelling the charge as a transaction resource is what delivers the half that is easy
 * to get wrong: if the move fails after the charge was spent, {@link #rollback()} puts it back,
 * rather than leaving the player poorer and still standing where they were.
 */
public final class AnchorChargeResource implements TxnResource {
	private final ServerLevel level;
	private final BlockPos pos;

	public AnchorChargeResource(ServerLevel level, BlockPos pos) {
		this.level = level;
		this.pos = pos;
	}

	/** The charge a respawn anchor is currently holding, or zero if it is not an anchor. */
	public static int chargesAt(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return 0;
		}

		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof RespawnAnchorBlock ? state.getValue(RespawnAnchorBlock.CHARGE) : 0;
	}

	@Override
	public boolean consume() {
		if (!level.isLoaded(pos)) {
			return false;
		}

		BlockState state = level.getBlockState(pos);

		if (!(state.getBlock() instanceof RespawnAnchorBlock)) {
			// The anchor was broken between resolving and committing.
			return false;
		}

		int charges = state.getValue(RespawnAnchorBlock.CHARGE);

		if (charges <= 0) {
			// Still the configured personal spawn, just not usable right now (section 12).
			return false;
		}

		level.setBlockAndUpdate(pos, state.setValue(RespawnAnchorBlock.CHARGE, charges - 1));
		return true;
	}

	@Override
	public void rollback() {
		if (!level.isLoaded(pos)) {
			Deepgate.LOGGER.warn("Could not restore an anchor charge at {}: chunk no longer loaded", pos);
			return;
		}

		BlockState state = level.getBlockState(pos);

		if (!(state.getBlock() instanceof RespawnAnchorBlock)) {
			Deepgate.LOGGER.warn("Could not restore an anchor charge at {}: the anchor is gone", pos);
			return;
		}

		int charges = state.getValue(RespawnAnchorBlock.CHARGE);

		if (charges >= RespawnAnchorBlock.MAX_CHARGES) {
			// Someone refilled it in between; giving another charge would create one from nothing.
			return;
		}

		level.setBlockAndUpdate(pos, state.setValue(RespawnAnchorBlock.CHARGE, charges + 1));
	}

	@Override
	public String describe() {
		return "respawn anchor charge at " + pos;
	}
}
