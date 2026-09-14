package com.brendan.deepgate.home;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.core.Destination;
import com.brendan.deepgate.core.Failure;
import com.brendan.deepgate.core.RuleSnapshot;
import com.brendan.deepgate.state.DeepgateState;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Beacon homes (spec sections 14 to 19).
 *
 * <p>Every home is bound to a real beacon, and the beacon is the single source of truth: the arrival
 * point is derived from it, and a home is available exactly when its beacon is.
 */
public final class HomeService {
	/** Facings a saved home can snap to (section 16). */
	public enum Facing {
		N(180.0F), NE(-135.0F), E(-90.0F), SE(-45.0F),
		S(0.0F), SW(45.0F), W(90.0F), NW(135.0F),
		UP(0.0F), DOWN(0.0F);

		private final float yaw;

		Facing(float yaw) {
			this.yaw = yaw;
		}

		public float yaw() {
			return yaw;
		}

		public float pitch() {
			return this == UP ? -90.0F : this == DOWN ? 90.0F : 0.0F;
		}

		/**
		 * Snap a look direction to one of the ten stored facings.
		 *
		 * <p>A steep pitch becomes UP or DOWN; everything else rounds to the nearest of eight compass
		 * points, so a home always faces somewhere deliberate rather than at an arbitrary angle.
		 *
		 * <p>Pure arithmetic, so it is unit tested without the game.
		 */
		public static Facing snap(float yaw, float pitch) {
			if (pitch <= -60.0F) {
				return UP;
			}

			if (pitch >= 60.0F) {
				return DOWN;
			}

			// Minecraft yaw: 0 is south and it increases clockwise, hence this ordering.
			return switch (Math.floorMod(Math.round(yaw / 45.0F), 8)) {
				case 0 -> S;
				case 1 -> SW;
				case 2 -> W;
				case 3 -> NW;
				case 4 -> N;
				case 5 -> NE;
				case 6 -> E;
				default -> SE;
			};
		}
	}

	/** Why a home cannot be used right now, or {@link #AVAILABLE} when it can. */
	public enum Availability {
		AVAILABLE("Ready"),
		HOMES_DISABLED("Homes are disabled"),
		PYRAMID_TOO_SMALL("Beacon pyramid is too small"),
		BEAM_BLOCKED("Beacon beam is blocked"),
		CROSS_DIMENSION_DISABLED("In another dimension"),
		OBSTRUCTED("Arrival is obstructed"),
		UNLOADED("Too far away to check");

		private final String description;

		Availability(String description) {
			this.description = description;
		}

		public String description() {
			return description;
		}

		public boolean usable() {
			return this == AVAILABLE;
		}
	}

	/**
	 * Players currently standing in a qualifying beam, so the prompt is edge triggered (section 15).
	 *
	 * <p>Keyed by player, holding the beacon they are standing over. Standing still never reopens the
	 * screen; leaving the beam is what rearms it.
	 */
	private final Map<UUID, BlockPos> inBeam = new HashMap<>();

	public boolean isInBeam(UUID playerId) {
		return inBeam.containsKey(playerId);
	}

	/**
	 * Note that a player has entered a beam.
	 *
	 * @return true the first time, false while they remain over the same beacon
	 */
	public boolean enterBeam(UUID playerId, BlockPos beacon) {
		BlockPos previous = inBeam.put(playerId, beacon);
		return !beacon.equals(previous);
	}

	public void leaveBeam(UUID playerId) {
		inBeam.remove(playerId);
	}

	public void clearAll() {
		inBeam.clear();
	}

	// ------------------------------------------------------------ queries

	public List<HomeRecord> homesOf(ServerPlayer player) {
		return DeepgateState.get(player.level().getServer()).homesOf(player.getUUID());
	}

	public Optional<HomeRecord> homeNamed(ServerPlayer player, String name) {
		return DeepgateState.get(player.level().getServer()).homeNamed(player.getUUID(), name);
	}

	/**
	 * Where a home puts you: centred on top of its beacon (section 17).
	 *
	 * <p>Fixed, never searched for. If a block is in the way the travel fails rather than sliding the
	 * player somewhere approximate.
	 */
	public static Optional<Destination> destinationOf(MinecraftServer server, HomeRecord home) {
		ServerLevel level = server.getLevel(home.dimension());

		if (level == null) {
			return Optional.empty();
		}

		BlockPos beacon = home.beacon();
		Vec3 position = new Vec3(beacon.getX() + 0.5D, beacon.getY() + 1, beacon.getZ() + 0.5D);

		return Optional.of(new Destination(level, position, home.yaw(), home.pitch()));
	}

	/**
	 * Whether a home can be travelled to right now (section 18).
	 *
	 * <p>Everything here is a temporary condition: the record survives all of them, and restoring the
	 * beacon restores the home. Only the beacon block disappearing deletes anything, and that is
	 * handled where the break is observed rather than here.
	 */
	public Availability availability(ServerPlayer player, HomeRecord home, RuleSnapshot rules) {
		if (!rules.allowHomes()) {
			return Availability.HOMES_DISABLED;
		}

		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.getLevel(home.dimension());

		if (level == null) {
			return Availability.UNLOADED;
		}

		if (!home.dimension().equals(player.level().dimension()) && !rules.allowCrossDimension()) {
			return Availability.CROSS_DIMENSION_DISABLED;
		}

		Optional<BeaconScan.Found> found = BeaconScan.beaconAt(level, home.beacon());

		if (found.isEmpty()) {
			// Not proof the beacon is gone - the chunk may simply be unloaded. Deletion only happens
			// when a break is actually observed, never from an absence of evidence.
			return Availability.UNLOADED;
		}

		if (!found.get().qualifies(rules.homeBeaconLayers())) {
			return Availability.PYRAMID_TOO_SMALL;
		}

		if (!BeaconScan.hasBeam(found.get().beacon())) {
			return Availability.BEAM_BLOCKED;
		}

		Optional<Destination> destination = destinationOf(server, home);

		if (destination.isEmpty()) {
			return Availability.UNLOADED;
		}

		if (!com.brendan.deepgate.core.TeleportService.isArrivalClear(player, destination.get())) {
			return Availability.OBSTRUCTED;
		}

		return Availability.AVAILABLE;
	}

	/** Turn an unusable availability into the failure the teleport pipeline should report. */
	public static Optional<Failure> toFailure(Availability availability) {
		return switch (availability) {
			case AVAILABLE -> Optional.empty();
			case HOMES_DISABLED -> Optional.of(
					Failure.of(Failure.Reason.FEATURE_UNAVAILABLE, availability.description()));
			case CROSS_DIMENSION_DISABLED -> Optional.of(Failure.crossDimensionDisabled());
			case UNLOADED -> Optional.of(
					Failure.of(Failure.Reason.DESTINATION_MISSING, "That beacon is not loaded right now"));
			case PYRAMID_TOO_SMALL, BEAM_BLOCKED, OBSTRUCTED -> Optional.of(
					Failure.of(Failure.Reason.DESTINATION_INVALID, availability.description()));
		};
	}

	// ------------------------------------------------------------ creation

	/** Why a beam interaction cannot create a home, or empty when it can. */
	public Optional<String> cannotCreate(ServerPlayer player, RuleSnapshot rules) {
		if (!rules.allowHomes()) {
			return Optional.of("Homes are disabled");
		}

		int owned = homesOf(player).size();

		if (owned >= rules.maxHomes()) {
			return Optional.of("Home limit reached - " + owned + "/" + rules.maxHomes());
		}

		return Optional.empty();
	}

	/**
	 * Create a home on the beacon a player is standing over.
	 *
	 * @return the new record, or the reason it was refused
	 */
	public HomeName.Result create(ServerPlayer player, BlockPos beacon, String rawName, RuleSnapshot rules) {
		Optional<String> blocked = cannotCreate(player, rules);

		if (blocked.isPresent()) {
			return new HomeName.Result.Invalid(blocked.get());
		}

		DeepgateState state = DeepgateState.get(player.level().getServer());
		List<String> existing = state.homesOf(player.getUUID()).stream().map(HomeRecord::name).toList();

		HomeName.Result validation = HomeName.validate(rawName, existing);

		if (!(validation instanceof HomeName.Result.Valid valid)) {
			return validation;
		}

		Facing facing = facingOf(player);

		state.add(new HomeRecord(
				UUID.randomUUID(),
				player.getUUID(),
				valid.name(),
				player.level().dimension(),
				beacon,
				facing.yaw(),
				facing.pitch()));

		return validation;
	}

	/** Rename, using exactly the same validation as creation (section 19). */
	public HomeName.Result rename(ServerPlayer player, HomeRecord home, String rawName) {
		DeepgateState state = DeepgateState.get(player.level().getServer());

		List<String> existing = state.homesOf(player.getUUID()).stream()
				.filter(other -> !other.id().equals(home.id()))
				.map(HomeRecord::name)
				.toList();

		HomeName.Result validation = HomeName.validate(rawName, existing);

		if (validation instanceof HomeName.Result.Valid valid) {
			state.replace(home.renamedTo(valid.name()));
		}

		return validation;
	}

	public boolean delete(ServerPlayer player, HomeRecord home) {
		if (!home.owner().equals(player.getUUID())) {
			return false;
		}

		return DeepgateState.get(player.level().getServer()).remove(home.id());
	}

	/** The facing a player is looking, snapped to one of the ten stored directions (section 16). */
	public static Facing facingOf(ServerPlayer player) {
		return Facing.snap(player.getYRot(), player.getXRot());
	}
}
