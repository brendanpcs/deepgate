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
		OBSTRUCTED("No clear space beside the beacon, or it was obstructed"),
		/** The beacon is genuinely gone; the record is deleted when this is discovered. */
		GONE("Beacon is gone"),
		/**
		 * The beacon is too far away to inspect, which says nothing about whether it works.
		 *
		 * <p>Treated as usable so travel is still offered: distance must never decide whether a home
		 * can be reached, and committing the travel loads the chunk and finds out for real.
		 */
		UNKNOWN("Not loaded");

		private final String description;

		Availability(String description) {
			this.description = description;
		}

		public String description() {
			return description;
		}

		/** Whether travel should be offered. Unknown counts: the commit will settle it properly. */
		public boolean usable() {
			return this == AVAILABLE || this == UNKNOWN;
		}

		/** Whether this is worth showing beside the name in a list. */
		public boolean worthReporting() {
			return this != AVAILABLE && this != UNKNOWN;
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
	 * Where a home puts you: the nearest spot you can stand beside the beacon.
	 *
	 * <p>Placement works like a bed rather than a coordinate: the beam column itself is skipped, and
	 * the search walks outwards to {@link ArrivalSearch#RADIUS} blocks, nearest first, taking the
	 * first position where the player actually fits on solid ground. Ordering is fixed, so the same
	 * beacon always puts you in the same place.
	 *
	 * <p>Landing in the beam would reopen the home screen the instant you arrived, which is why the
	 * column is excluded rather than merely deprioritised.
	 *
	 * @return the arrival, or empty when nothing within range can hold a player
	 */
	public static Optional<Destination> findArrival(ServerPlayer player, HomeRecord home) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.getLevel(home.dimension());

		if (level == null) {
			return Optional.empty();
		}

		BlockPos top = home.beacon().above();

		if (!level.isLoaded(top)) {
			return Optional.empty();
		}

		// A bigger pyramid earns a wider landing area. One layer is assumed when the beacon cannot be
		// read, which is the smallest a home is ever allowed to be anchored to.
		// Measured from the beacon when it can be read, otherwise the last size seen.
		int layers = BeaconScan.beaconAt(level, home.beacon())
				.map(BeaconScan.Found::levels)
				.orElse(home.pyramidLayers());

		for (ArrivalSearch.Offset offset : ArrivalSearch.candidatesFor(layers)) {
			BlockPos candidate = top.offset(offset.dx(), offset.dy(), offset.dz());

			if (!level.isLoaded(candidate)) {
				continue;
			}

			Vec3 position = new Vec3(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D);
			Destination destination = new Destination(level, position, home.yaw(), home.pitch());

			if (canStandAt(player, level, candidate, destination)) {
				return Optional.of(destination);
			}
		}

		return Optional.empty();
	}

	/** Whether a player fits here with something solid under their feet. */
	private static boolean canStandAt(ServerPlayer player, ServerLevel level, BlockPos pos,
			Destination destination) {
		// Something to stand on, so arriving never drops the player into a hole or onto water.
		if (!level.getBlockState(pos.below()).blocksMotion()) {
			return false;
		}

		return com.brendan.deepgate.core.TeleportService.isArrivalClear(player, destination);
	}

	/**
	 * Whether a home can be travelled to right now (section 18).
	 *
	 * <p>Everything here is a temporary condition: the record survives all of them, and restoring the
	 * beacon restores the home. Only the beacon block disappearing deletes anything, and that is
	 * handled where the break is observed rather than here.
	 */
	public Availability availability(ServerPlayer player, HomeRecord home, RuleSnapshot rules) {
		return availability(player, home, rules, false);
	}

	/**
	 * @param forceLoad pull the beacon chunk in so the answer is definitive. Travel passes true, so
	 *                  distance never decides whether a home can be reached; listing passes false, so
	 *                  opening the menu does not load a chunk for every home a player owns.
	 */
	public Availability availability(ServerPlayer player, HomeRecord home, RuleSnapshot rules,
			boolean forceLoad) {
		if (!rules.allowHomes()) {
			return Availability.HOMES_DISABLED;
		}

		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.getLevel(home.dimension());

		if (level == null) {
			return Availability.UNKNOWN;
		}

		if (!home.dimension().equals(player.level().dimension()) && !rules.allowCrossDimension()) {
			return Availability.CROSS_DIMENSION_DISABLED;
		}

		BeaconScan.Lookup lookup = BeaconScan.lookup(level, home.beacon(), forceLoad);

		if (lookup.presence() == BeaconScan.Presence.UNKNOWN) {
			// Could not look, which is not the same as looking and finding nothing.
			return Availability.UNKNOWN;
		}

		if (lookup.presence() == BeaconScan.Presence.ABSENT) {
			// Looked at a loaded chunk and the beacon is not there, so it really is gone. Section 49
			// asks for exactly this: invalid records are cleaned up when discovered.
			DeepgateState.get(server).remove(home.id());
			return Availability.GONE;
		}

		Optional<BeaconScan.Found> found = lookup.found();

		// Remember what was seen, so a home can still describe itself once the beacon is unloaded.
		remember(server, home, found.get());

		if (!found.get().qualifies(rules.homeBeaconLayers())) {
			return Availability.PYRAMID_TOO_SMALL;
		}

		if (!BeaconScan.hasClearBeam(level, home.beacon())) {
			return Availability.BEAM_BLOCKED;
		}

		if (findArrival(player, home).isEmpty()) {
			return Availability.OBSTRUCTED;
		}

		return Availability.AVAILABLE;
	}

	/** Store what was just observed about a beacon, for use when it cannot be read later. */
	private static void remember(MinecraftServer server, HomeRecord home, BeaconScan.Found found) {
		int colour = BeaconScan.beamColour(found.beacon());
		int layers = found.levels();

		if (colour != home.beamColour() || layers != home.pyramidLayers()) {
			DeepgateState.get(server).replace(home.withBeamColour(colour).withPyramidLayers(layers));
		}
	}

	/**
	 * The colour to draw this home in, preferring what the beam looks like right now.
	 *
	 * <p>Read live whenever the beacon is loaded, so changing the glass over a beacon recolours the
	 * name immediately rather than at some later refresh. The observed colour is written back, which
	 * is what keeps the list correct later on when the beacon is too far away to read.
	 *
	 * <p>Falls back to the last colour seen when the beacon is unloaded - the best answer available,
	 * and never a reason to claim the home has changed colour.
	 */
	public static int beamColourOf(MinecraftServer server, HomeRecord home) {
		ServerLevel level = server.getLevel(home.dimension());

		if (level == null) {
			return home.beamColour();
		}

		Optional<BeaconScan.Found> found = BeaconScan.beaconAt(level, home.beacon());

		if (found.isEmpty()) {
			return home.beamColour();
		}

		int colour = BeaconScan.beamColour(found.get().beacon());

		if (colour != home.beamColour()) {
			DeepgateState.get(server).replace(home.withBeamColour(colour));
		}

		return colour;
	}

	/** Turn an unusable availability into the failure the teleport pipeline should report. */
	public static Optional<Failure> toFailure(Availability availability) {
		return switch (availability) {
			case AVAILABLE -> Optional.empty();
			case HOMES_DISABLED -> Optional.of(
					Failure.of(Failure.Reason.FEATURE_UNAVAILABLE, availability.description()));
			case CROSS_DIMENSION_DISABLED -> Optional.of(Failure.crossDimensionDisabled());
			case UNKNOWN -> Optional.empty();
			case GONE -> Optional.of(
					Failure.of(Failure.Reason.DESTINATION_MISSING, "That beacon no longer exists"));
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
			return Optional.of("Home limit reached — " + owned + "/" + rules.maxHomes());
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

		// A beacon already claimed by someone carries their name; everyone else joins it rather than
		// giving the same place a second name.
		String name = state.firstHomeNameAt(player.level().dimension(), beacon).orElse(rawName);
		List<String> existing = state.homesOf(player.getUUID()).stream().map(HomeRecord::name).toList();

		HomeName.Result validation = HomeName.validate(name, existing);

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
				facing.pitch(),
				BeaconScan.beaconAt(player.level(), beacon)
						.map(found -> BeaconScan.beamColour(found.beacon()))
						.orElse(HomeRecord.WHITE),
				BeaconScan.beaconAt(player.level(), beacon)
						.map(BeaconScan.Found::levels)
						.orElse(1)));

		return validation;
	}

	/**
	 * Rename a beacon, for everyone who has a home on it (section 19).
	 *
	 * <p>A beacon carries one name. Renaming it renames every home bound to it, whoever owns them,
	 * because the name describes the place rather than one player's bookmark of it - a market that
	 * becomes a workshop has become one for everybody who goes there.
	 *
	 * <p>Names still have to stay unique per player, so the new name is checked against the other
	 * homes of every affected owner. A clash refuses the whole rename rather than renaming some
	 * players and not others, which would silently split the beacon in two.
	 */
	public HomeName.Result rename(ServerPlayer player, HomeRecord home, String rawName) {
		DeepgateState state = DeepgateState.get(player.level().getServer());
		List<HomeRecord> onBeacon = state.homesAt(home.dimension(), home.beacon());

		// Validated against the renaming player first, so the usual messages come back unchanged.
		HomeName.Result validation = HomeName.validate(rawName, otherNamesOf(state, player.getUUID(), onBeacon));

		if (!(validation instanceof HomeName.Result.Valid valid)) {
			return validation;
		}

		for (HomeRecord affected : onBeacon) {
			if (affected.owner().equals(player.getUUID())) {
				continue;
			}

			if (HomeName.isTaken(valid.name(), otherNamesOf(state, affected.owner(), onBeacon))) {
				return new HomeName.Result.Invalid(
						"Someone else with a home here already has a home called " + valid.name());
			}
		}

		for (HomeRecord affected : onBeacon) {
			state.replace(affected.renamedTo(valid.name()));
		}

		return validation;
	}

	/** The names of this owner's homes that are not on the beacon being renamed. */
	private static List<String> otherNamesOf(DeepgateState state, UUID owner, List<HomeRecord> onBeacon) {
		return state.homesOf(owner).stream()
				.filter(home -> onBeacon.stream().noneMatch(shared -> shared.id().equals(home.id())))
				.map(HomeRecord::name)
				.toList();
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
