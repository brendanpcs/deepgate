package com.brendan.deepgate.spawn;

import java.util.Optional;

import com.brendan.deepgate.core.Destination;
import com.brendan.deepgate.core.Failure;
import com.brendan.deepgate.core.RuleSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /spawn}: go to wherever Minecraft currently considers your respawn destination
 * (spec sections 10 to 13).
 *
 * <p>There are exactly two kinds of destination - a personal spawn, or world spawn - and Deepgate
 * never silently substitutes one for the other. A bed or anchor that exists but cannot be used is a
 * failure, not a reason to quietly send the player somewhere else.
 */
public final class SpawnService {
	private SpawnService() {
	}

	/** What {@code /spawn} would do right now. */
	public sealed interface Resolution {
		/** A personal spawn: a bed or a charged respawn anchor. */
		record Personal(Destination destination, Kind kind, BlockPos block, DyeColor bedColour, int anchorCharges)
				implements Resolution {
		}

		/** No personal spawn is set, so world spawn it is. */
		record World(Destination destination) implements Resolution {
		}

		/** A personal spawn is set but cannot be used; section 11 says fail rather than fall back. */
		record Blocked(Failure failure, Kind kind) implements Resolution {
		}

		/** Nothing usable at all. */
		record Unavailable(Failure failure) implements Resolution {
		}
	}

	public enum Kind {
		BED,
		ANCHOR,
		WORLD
	}

	/**
	 * Work out where {@code /spawn} goes, without consuming anything.
	 *
	 * <p>The respawn position is resolved by vanilla, with {@code consumeSpawnBlock} false so a
	 * respawn anchor keeps its charge. The real commit repeats the call as a transaction resource.
	 */
	public static Resolution resolve(ServerPlayer player, RuleSnapshot rules) {
		MinecraftServer server = player.level().getServer();
		ServerPlayer.RespawnConfig config = player.getRespawnConfig();

		if (config == null) {
			return resolveWorldSpawn(player, server, rules);
		}

		LevelData.RespawnData data = config.respawnData();
		ServerLevel level = server.getLevel(data.dimension());

		if (level == null) {
			return new Resolution.Blocked(
					Failure.of(Failure.Reason.DESTINATION_MISSING, "Your spawn is in a dimension that no longer exists"),
					Kind.WORLD);
		}

		BlockPos block = data.pos();
		BlockState state = level.isLoaded(block) ? level.getBlockState(block) : null;
		Kind kind = kindOf(state);

		// Ask vanilla where the player would actually appear, taking nothing.
		TeleportTransition transition = player.findRespawnPositionAndUseSpawnBlock(
				false, TeleportTransition.DO_NOTHING);

		if (transition.missingRespawnBlock()) {
			// The bed or anchor is gone, obstructed, or otherwise unusable. Vanilla would fall back to
			// world spawn here; section 11 is explicit that /spawn must not.
			return new Resolution.Blocked(
					Failure.of(Failure.Reason.DESTINATION_INVALID, kind == Kind.ANCHOR
							? "Your respawn anchor is blocked or has no charge"
							: "Your bed is missing or obstructed"),
					kind);
		}

		if (!crossDimensionAllowed(player, transition.newLevel(), rules)) {
			return new Resolution.Blocked(Failure.crossDimensionDisabled(), kind);
		}

		Destination destination = new Destination(
				transition.newLevel(), transition.position(), transition.yRot(), transition.xRot());

		return new Resolution.Personal(
				destination,
				kind,
				block,
				kind == Kind.BED && state != null && state.getBlock() instanceof BedBlock bed ? bed.getColor() : null,
				kind == Kind.ANCHOR && state != null ? state.getValue(RespawnAnchorBlock.CHARGE) : 0);
	}

	private static Resolution resolveWorldSpawn(ServerPlayer player, MinecraftServer server, RuleSnapshot rules) {
		ServerLevel overworld = server.overworld();

		if (!crossDimensionAllowed(player, overworld, rules)) {
			// Outside the world-spawn dimension with dimensional travel off (section 13).
			return new Resolution.Unavailable(Failure.crossDimensionDisabled());
		}

		// Let vanilla resolve world spawn rather than writing a second placement algorithm.
		TeleportTransition transition = player.findRespawnPositionAndUseSpawnBlock(
				false, TeleportTransition.DO_NOTHING);

		return new Resolution.World(new Destination(
				transition.newLevel(), transition.position(), transition.yRot(), transition.xRot()));
	}

	private static boolean crossDimensionAllowed(ServerPlayer player, ServerLevel destination, RuleSnapshot rules) {
		ResourceKey<Level> from = player.level().dimension();
		return from.equals(destination.dimension()) || rules.allowCrossDimension();
	}

	private static Kind kindOf(BlockState state) {
		if (state == null) {
			return Kind.WORLD;
		}

		if (state.getBlock() instanceof BedBlock) {
			return Kind.BED;
		}

		if (state.getBlock() instanceof RespawnAnchorBlock) {
			return Kind.ANCHOR;
		}

		return Kind.WORLD;
	}

	/** The destination a resolution would travel to, if it has one. */
	public static Optional<Destination> destinationOf(Resolution resolution) {
		return switch (resolution) {
			case Resolution.Personal personal -> Optional.of(personal.destination());
			case Resolution.World world -> Optional.of(world.destination());
			case Resolution.Blocked ignored -> Optional.empty();
			case Resolution.Unavailable ignored -> Optional.empty();
		};
	}

	/** A short label for the destination, used in confirmations. */
	public static String describe(Resolution resolution) {
		return switch (resolution) {
			case Resolution.Personal personal -> switch (personal.kind()) {
				case BED -> "your bed";
				case ANCHOR -> "your respawn anchor";
				case WORLD -> "world spawn";
			};
			case Resolution.World ignored -> "world spawn";
			case Resolution.Blocked ignored -> "your spawn";
			case Resolution.Unavailable ignored -> "your spawn";
		};
	}

	/** Distance is measured to the resolved arrival point, wherever that turned out to be. */
	public static Vec3 positionOf(Resolution resolution) {
		return destinationOf(resolution).map(Destination::position).orElse(Vec3.ZERO);
	}
}
