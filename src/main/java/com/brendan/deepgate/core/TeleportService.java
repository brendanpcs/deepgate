package com.brendan.deepgate.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.Deepgate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The one paid-teleport pipeline shared by TPA, {@code /spawn} and {@code /home}
 * (spec sections 50 to 52), plus the {@code /back} undo (section 51).
 *
 * <p>Every command teleport moves the player alone and dismounts them first. Vehicles, passengers,
 * pets and leashed entities are out of scope for command teleportation in V1 Lite.
 */
public final class TeleportService {
	private static final int TICKS_PER_SECOND = 20;

	private final CombatTracker combat;
	private final Map<UUID, BackRecord> backRecords = new HashMap<>();

	public TeleportService(CombatTracker combat) {
		this.combat = combat;
	}

	public CombatTracker combat() {
		return combat;
	}

	/** Outcome of a teleport attempt. Failure always means nothing was charged. */
	public sealed interface Result {
		record Success(int pointsCharged) implements Result {
		}

		record Failed(Failure failure) implements Result {
		}
	}

	/**
	 * Run the full transaction for a paid teleport.
	 *
	 * @param preFailures failures the caller already knows about, such as a disabled feature or a
	 *                    destination that no longer exists; they take part in precedence selection
	 *                    rather than short-circuiting it
	 * @param extra       resources beyond experience that this teleport consumes, such as a respawn
	 *                    anchor charge; they roll back with the experience if the move fails
	 */
	public Result execute(
			ServerPlayer player,
			Destination destination,
			Fare fare,
			List<Failure> preFailures,
			List<TxnResource> extra,
			RuleSnapshot rules) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return new Result.Failed(Failure.of(Failure.Reason.DESTINATION_UNAVAILABLE, "Server unavailable"));
		}

		List<Failure> failures = new ArrayList<>(preFailures);

		boolean crossDimension = !player.level().dimension().equals(destination.level().dimension());

		if (crossDimension && !rules.allowCrossDimension()) {
			// Blocked outright, never quietly redirected to a same-dimension destination.
			failures.add(Failure.crossDimensionDisabled());
		}

		if (!isArrivalClear(player, destination)) {
			failures.add(Failure.of(Failure.Reason.DESTINATION_INVALID, "Destination is obstructed"));
		}

		combat.check(server, player.getUUID(), rules).ifPresent(failures::add);

		int held = XpAccount.totalPoints(player);
		int points = fare.pointsFor(held);

		if (held < points) {
			failures.add(Failure.insufficientXp(fare, points, held));
		}

		Optional<Failure> primary = Failure.primary(failures, Failure.COMMAND_ORDER);

		if (primary.isPresent()) {
			return new Result.Failed(primary.get());
		}

		// Capture the origin before anything moves, so the /back record describes where they were.
		BackRecord origin = new BackRecord(
				player.level().dimension(),
				player.position(),
				player.getYRot(),
				player.getXRot(),
				points,
				server.getTickCount() + (long) rules.backWindowSeconds() * TICKS_PER_SECOND);

		TxnLedger ledger = new TxnLedger();

		if (!ledger.consume(new XpResource(player, points))) {
			return new Result.Failed(Failure.insufficientXp(fare, points, held));
		}

		for (TxnResource resource : extra) {
			if (!ledger.consume(resource)) {
				rollback(ledger);
				return new Result.Failed(
						Failure.of(Failure.Reason.DESTINATION_UNAVAILABLE, "Destination is no longer usable"));
			}
		}

		boolean moved;

		try {
			moved = move(player, destination);
		} catch (RuntimeException e) {
			Deepgate.LOGGER.error("Teleport threw after resources were consumed; rolling back", e);
			rollback(ledger);
			return new Result.Failed(Failure.of(Failure.Reason.DESTINATION_INVALID, "Teleport failed"));
		}

		if (!moved) {
			rollback(ledger);
			return new Result.Failed(Failure.of(Failure.Reason.DESTINATION_INVALID, "Destination is obstructed"));
		}

		ledger.commit();

		if (rules.backWindowSeconds() > 0) {
			backRecords.put(player.getUUID(), origin);
		} else {
			backRecords.remove(player.getUUID());
		}

		return new Result.Success(points);
	}

	private void rollback(TxnLedger ledger) {
		for (String failure : ledger.rollbackAll()) {
			Deepgate.LOGGER.error("Failed to roll back {} after a failed teleport", failure);
		}
	}

	/**
	 * Move the player alone, dismounting first.
	 *
	 * @return true if the player actually arrived
	 */
	private boolean move(ServerPlayer player, Destination destination) {
		player.stopRiding();

		TeleportTransition transition = new TeleportTransition(
				destination.level(),
				destination.position(),
				Vec3.ZERO,
				destination.yaw(),
				destination.pitch(),
				TeleportTransition.DO_NOTHING);

		return player.teleport(transition) != null;
	}

	/**
	 * Whether the bounding box of the player fits at the destination exactly.
	 *
	 * <p>Deepgate never searches for a nearby alternative (sections 9, 17, 20), so this is a single
	 * yes-or-no test. The destination chunk is pulled in first, because an unloaded chunk reports no
	 * collision and would let a player land inside a wall.
	 */
	public static boolean isArrivalClear(ServerPlayer player, Destination destination) {
		ServerLevel level = destination.level();

		if (destination.loadChunks()) {
			Chunks.loadAround(level, BlockPos.containing(destination.position()));
		}

		AABB box = player.getDimensions(player.getPose()).makeBoundingBox(destination.position());
		return level.noCollision(player, box);
	}

	// ---------------------------------------------------------------- /back

	public Optional<BackRecord> backRecord(MinecraftServer server, UUID playerId) {
		BackRecord record = backRecords.get(playerId);

		if (record == null) {
			return Optional.empty();
		}

		if (record.isExpired(server.getTickCount())) {
			backRecords.remove(playerId);
			return Optional.empty();
		}

		return Optional.of(record);
	}

	/**
	 * The transient undo (spec sections 20 and 51): free, single use, and it never creates another
	 * {@code /back}. A failed move refunds nothing and leaves the record alive until it expires.
	 */
	public Result back(ServerPlayer player, RuleSnapshot rules) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return new Result.Failed(Failure.of(Failure.Reason.DESTINATION_UNAVAILABLE, "Server unavailable"));
		}

		if (rules.backWindowSeconds() <= 0) {
			return new Result.Failed(Failure.of(Failure.Reason.FEATURE_UNAVAILABLE, "/back is disabled"));
		}

		Optional<BackRecord> maybeRecord = backRecord(server, player.getUUID());

		if (maybeRecord.isEmpty()) {
			return new Result.Failed(Failure.of(Failure.Reason.DESTINATION_MISSING, "Nothing to go back to"));
		}

		BackRecord record = maybeRecord.get();
		ServerLevel level = server.getLevel(record.dimension());

		if (level == null) {
			backRecords.remove(player.getUUID());
			return new Result.Failed(
					Failure.of(Failure.Reason.DESTINATION_MISSING, "That dimension is no longer loaded"));
		}

		List<Failure> failures = new ArrayList<>();

		if (!player.level().dimension().equals(record.dimension()) && !rules.allowCrossDimension()) {
			failures.add(Failure.crossDimensionDisabled());
		}

		Destination destination = new Destination(level, record.position(), record.yaw(), record.pitch());

		if (!isArrivalClear(player, destination)) {
			failures.add(Failure.of(Failure.Reason.DESTINATION_INVALID, "Where you came from is blocked"));
		}

		combat.check(server, player.getUUID(), rules).ifPresent(failures::add);

		Optional<Failure> primary = Failure.primary(failures, Failure.COMMAND_ORDER);

		if (primary.isPresent()) {
			// The record survives a failure and stays usable until it expires naturally.
			return new Result.Failed(primary.get());
		}

		if (!move(player, destination)) {
			return new Result.Failed(Failure.of(Failure.Reason.DESTINATION_INVALID, "Where you came from is blocked"));
		}

		// Refund only after the move landed, so a failed /back refunds nothing.
		XpAccount.addPoints(player, record.xpPointsRemoved());

		// Single use: consumed here, and a successful /back never leaves another one behind.
		backRecords.remove(player.getUUID());

		return new Result.Success(-record.xpPointsRemoved());
	}

	/** Drop the undo record for a player. Death, disconnect and a fresh teleport all clear it. */
	public void clearBack(UUID playerId) {
		backRecords.remove(playerId);
	}

	public void clearAll() {
		backRecords.clear();
	}

	/** Expire stale records so a long-lived server does not accumulate them. */
	public void tick(MinecraftServer server) {
		long now = server.getTickCount();
		backRecords.values().removeIf(record -> record.isExpired(now));
	}
}
