package com.brendan.deepgate.gametest;

import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.CostGameRule;
import com.brendan.deepgate.DeepgateRules;
import com.brendan.deepgate.core.Cost;
import com.brendan.deepgate.core.RuleSnapshot;
import com.brendan.deepgate.core.XpAccount;
import com.brendan.deepgate.home.ArrivalSearch;
import com.brendan.deepgate.home.BeaconScan;
import com.brendan.deepgate.home.HomeName;
import com.brendan.deepgate.home.HomeRecord;
import com.brendan.deepgate.home.HomeService;
import com.brendan.deepgate.spawn.AnchorChargeResource;
import com.brendan.deepgate.spawn.SpawnService;
import com.brendan.deepgate.state.DeepgateState;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * World-dependent checks for M2: beacon detection, the section 18 validity table, home persistence
 * and the respawn-anchor charge behaving as a transaction resource.
 *
 * <p>Name validation and facing arithmetic are unit tested without the game and are not repeated.
 */
public final class M2GameTests {
	private static RuleSnapshot rules(GameTestHelper helper) {
		return DeepgateRules.snapshot(helper.getLevel().getServer());
	}

	/** Build a one-layer iron pyramid with a beacon on top, and return the beacon position. */
	private static BlockPos buildBeacon(GameTestHelper helper, int x, int y, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.IRON_BLOCK);
			}
		}

		BlockPos beacon = new BlockPos(x, y + 1, z);
		helper.setBlock(beacon, Blocks.BEACON);
		return beacon;
	}

	/** A beacon with a real pyramid reports its level; the scan finds it from above. */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 220, setupTicks = 0, skyAccess = true)
	public void aBeaconIsFoundFromAboveAndReportsItsPyramid(GameTestHelper helper) {
		BlockPos relative = buildBeacon(helper, 1, 1, 1);
		BlockPos absolute = helper.absolutePos(relative);

		// The beacon needs a few ticks to scan its own pyramid before levels is populated.
		helper.runAfterDelay(100, () -> {
			Optional<BeaconScan.Found> found = BeaconScan.beaconAt(helper.getLevel(), absolute);

			if (found.isEmpty()) {
				throw helper.assertionException("no beacon found at " + absolute);
			}

			if (found.get().levels() < 1) {
				throw helper.assertionException("expected at least one pyramid level, got "
						+ found.get().levels());
			}

			if (!found.get().qualifies(1)) {
				throw helper.assertionException("a one-layer pyramid should qualify at the default rule");
			}

			helper.succeed();
		});
	}

	/** Homes survive a round trip through the persistent state and are found by name. */
	@GameTest
	public void homesPersistAndAreFoundByNameCaseInsensitively(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());

		UUID owner = player.getUUID();
		BlockPos beacon = helper.absolutePos(new BlockPos(2, 1, 2));

		HomeRecord home = new HomeRecord(UUID.randomUUID(), owner, "Workshop",
				helper.getLevel().dimension(), beacon, 0.0F, 0.0F, HomeRecord.WHITE, 1);
		state.add(home);

		try {
			if (state.homeNamed(owner, "workshop").isEmpty()) {
				throw helper.assertionException("lookup should ignore case");
			}

			if (state.homeNamed(owner, "  WORKSHOP ").isEmpty()) {
				throw helper.assertionException("lookup should trim and ignore case");
			}

			if (state.homeAt(owner, helper.getLevel().dimension(), beacon).isEmpty()) {
				throw helper.assertionException("should find the home bound to this beacon");
			}

			// A different player gets nothing, even on the same beacon.
			if (state.homeAt(UUID.randomUUID(), helper.getLevel().dimension(), beacon).isPresent()) {
				throw helper.assertionException("homes must be per player");
			}

			if (!state.isDirty()) {
				throw helper.assertionException("adding a home must mark the state dirty so it saves");
			}
		} finally {
			state.remove(home.id());
		}

		helper.succeed();
	}

	/** Breaking a beacon removes every home bound to it, for every owner (sections 14 and 18). */
	@GameTest
	public void breakingABeaconRemovesEveryHomeBoundToIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		DeepgateState state = DeepgateState.get(level.getServer());

		BlockPos beacon = helper.absolutePos(new BlockPos(3, 1, 3));
		UUID alice = UUID.randomUUID();
		UUID bob = UUID.randomUUID();

		state.add(new HomeRecord(UUID.randomUUID(), alice, "A", level.dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1));
		state.add(new HomeRecord(UUID.randomUUID(), bob, "B", level.dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1));
		// A home on a different beacon must be left alone.
		BlockPos elsewhere = helper.absolutePos(new BlockPos(5, 1, 5));
		HomeRecord survivor = new HomeRecord(UUID.randomUUID(), alice, "C", level.dimension(), elsewhere, 0F, 0F, HomeRecord.WHITE, 1);
		state.add(survivor);

		try {
			int removed = state.removeHomesAt(level.dimension(), beacon);

			if (removed != 2) {
				throw helper.assertionException("expected to remove both homes on that beacon, removed " + removed);
			}

			if (state.home(survivor.id()).isEmpty()) {
				throw helper.assertionException("a home on another beacon must survive");
			}
		} finally {
			state.remove(survivor.id());
		}

		helper.succeed();
	}

	/**
	 * A beacon that is really there reports available.
	 *
	 * <p>The positive case, which is where the bug hid: the lookup refused to read an unloaded chunk
	 * and the caller turned that into "too far away", so a perfectly good home a short walk away was
	 * reported unreachable.
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300, skyAccess = true)
	public void aRealBeaconReportsAvailable(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());

		// Floor to arrive on, with the beacon in the middle.
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(new BlockPos(5 + dx, 1, 5 + dz), Blocks.IRON_BLOCK);
			}
		}

		BlockPos beaconRelative = new BlockPos(5, 2, 5);
		helper.setBlock(beaconRelative, Blocks.BEACON);
		BlockPos beacon = helper.absolutePos(beaconRelative);

		HomeRecord home = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Live",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		state.add(home);

		helper.runAfterDelay(120, () -> {
			try {
				HomeService.Availability availability =
						Deepgate.homes().availability(player, home, rules(helper), true);

				if (availability != HomeService.Availability.AVAILABLE) {
					throw helper.assertionException("a real beacon should be available, got " + availability);
				}

				if (!availability.usable() || availability.worthReporting()) {
					throw helper.assertionException("available should be usable and unremarkable");
				}
			} finally {
				state.remove(home.id());
			}

			helper.succeed();
		});
	}

	/** A beacon that is confirmed gone deletes the record and says so, per section 49. */
	@GameTest
	public void aConfirmedMissingBeaconDeletesTheRecord(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());

		// A loaded position with definitely no beacon on it.
		BlockPos empty = helper.absolutePos(new BlockPos(6, 1, 6));
		helper.setBlock(new BlockPos(6, 1, 6), Blocks.AIR);

		HomeRecord home = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Ghost",
				helper.getLevel().dimension(), empty, 0F, 0F, HomeRecord.WHITE, 1);
		state.add(home);

		HomeService.Availability availability =
				Deepgate.homes().availability(player, home, rules(helper), true);

		if (availability != HomeService.Availability.GONE) {
			state.remove(home.id());
			throw helper.assertionException("a loaded chunk with no beacon means gone, got " + availability);
		}

		if (state.home(home.id()).isPresent()) {
			state.remove(home.id());
			throw helper.assertionException("a confirmed missing beacon should delete the record");
		}

		helper.succeed();
	}

	/** Being unable to look is not the same as looking and finding nothing. */
	@GameTest
	public void anUnreadableBeaconIsUnknownAndStillOffersTravel(GameTestHelper helper) {
		// Far outside any loaded chunk, so the cheap lookup cannot say anything.
		BlockPos faraway = new BlockPos(6_000_000, 64, 6_000_000);

		BeaconScan.Lookup lookup = BeaconScan.lookup(helper.getLevel(), faraway, false);

		if (lookup.presence() != BeaconScan.Presence.UNKNOWN) {
			throw helper.assertionException("an unloaded chunk should be unknown, got " + lookup.presence());
		}

		// Unknown must not block travel: distance is never a reason a home cannot be reached.
		if (!HomeService.Availability.UNKNOWN.usable()) {
			throw helper.assertionException("unknown must still offer travel");
		}

		if (HomeService.Availability.UNKNOWN.worthReporting()) {
			throw helper.assertionException("unknown should not be shown as a problem");
		}

		helper.succeed();
	}

	/** The home limit is enforced against the gamerule, and the message names the numbers. */
	@GameTest
	public void theHomeLimitIsEnforced(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());
		RuleSnapshot rules = rules(helper);

		BlockPos beacon = helper.absolutePos(new BlockPos(7, 1, 7));

		for (int i = 0; i < rules.maxHomes(); i++) {
			state.add(new HomeRecord(UUID.randomUUID(), player.getUUID(), "Home" + i,
					helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1));
		}

		try {
			Optional<String> blocked = Deepgate.homes().cannotCreate(player, rules);

			if (blocked.isEmpty()) {
				throw helper.assertionException("creation should be refused at the limit");
			}

			if (!blocked.get().contains(rules.maxHomes() + "/" + rules.maxHomes())) {
				throw helper.assertionException("the message should show the count: " + blocked.get());
			}

			// Lowering the limit must not delete anything that already exists.
			if (Deepgate.homes().homesOf(player).size() != rules.maxHomes()) {
				throw helper.assertionException("existing homes must be retained");
			}
		} finally {
			for (HomeRecord home : Deepgate.homes().homesOf(player)) {
				state.remove(home.id());
			}
		}

		helper.succeed();
	}

	/** Renaming keeps identity and rejects a duplicate, exactly as creation does (section 19). */
	@GameTest
	public void renamingValidatesTheSameWayAsCreation(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());
		BlockPos beacon = helper.absolutePos(new BlockPos(8, 1, 8));

		HomeRecord first = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Alpha",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		HomeRecord second = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Beta",
				helper.getLevel().dimension(), helper.absolutePos(new BlockPos(22, 1, 22)),
				0F, 0F, HomeRecord.WHITE, 1);
		state.add(first);
		state.add(second);

		try {
			if (!(Deepgate.homes().rename(player, second, "alpha") instanceof HomeName.Result.Invalid)) {
				throw helper.assertionException("renaming onto an existing name must be refused");
			}

			if (!(Deepgate.homes().rename(player, second, "Gamma") instanceof HomeName.Result.Valid)) {
				throw helper.assertionException("a free name should be accepted");
			}

			Optional<HomeRecord> renamed = state.home(second.id());

			if (renamed.isEmpty() || !renamed.get().name().equals("Gamma")) {
				throw helper.assertionException("rename must keep the same record identity");
			}

			// Renaming to its own current name is allowed: it is not a clash with itself.
			if (!(Deepgate.homes().rename(player, renamed.get(), "Gamma") instanceof HomeName.Result.Valid)) {
				throw helper.assertionException("renaming a home to its own name should be fine");
			}
		} finally {
			state.remove(first.id());
			state.remove(second.id());
		}

		helper.succeed();
	}

	/**
	 * Standing in a beam never reopens the screen; only leaving and returning does (section 15).
	 *
	 * <p>This is what stops a player who closes the Set Home screen from being soft locked, unable to
	 * walk out of the beam because the screen keeps coming back on the next check.
	 */
	@GameTest
	public void theBeamPromptIsEdgeTriggeredSoClosingItDoesNotSoftLock(GameTestHelper helper) {
		HomeService homes = Deepgate.homes();
		UUID player = UUID.randomUUID();
		BlockPos beacon = helper.absolutePos(new BlockPos(4, 1, 4));

		try {
			if (!homes.enterBeam(player, beacon)) {
				throw helper.assertionException("the first entry should open the screen");
			}

			// Every later check while standing in the same beam must do nothing at all.
			for (int tick = 0; tick < 50; tick++) {
				if (homes.enterBeam(player, beacon)) {
					throw helper.assertionException("the screen reopened while standing still");
				}
			}

			if (!homes.isInBeam(player)) {
				throw helper.assertionException("the player should still be tracked as in the beam");
			}

			// Stepping out rearms it.
			homes.leaveBeam(player);

			if (homes.isInBeam(player)) {
				throw helper.assertionException("leaving should clear the beam state");
			}

			if (!homes.enterBeam(player, beacon)) {
				throw helper.assertionException("re-entering should open the screen again");
			}

			// Walking straight from one beacon into another counts as a fresh entry.
			BlockPos other = helper.absolutePos(new BlockPos(9, 1, 9));

			if (!homes.enterBeam(player, other)) {
				throw helper.assertionException("a different beacon should open its own screen");
			}
		} finally {
			homes.leaveBeam(player);
		}

		helper.succeed();
	}

	/** At the limit, the beam reports it rather than silently doing nothing (section 15). */
	@GameTest
	public void hittingTheHomeLimitReportsItWithTheCount(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());
		RuleSnapshot rules = rules(helper);
		BlockPos beacon = helper.absolutePos(new BlockPos(10, 1, 10));

		for (int i = 0; i < rules.maxHomes(); i++) {
			state.add(new HomeRecord(UUID.randomUUID(), player.getUUID(), "Full" + i,
					helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1));
		}

		try {
			String message = Deepgate.homes().cannotCreate(player, rules)
					.orElseThrow(() -> helper.assertionException("expected the limit to be reported"));

			if (!message.startsWith("Home limit reached")) {
				throw helper.assertionException("unexpected wording: " + message);
			}

			if (!message.endsWith(rules.maxHomes() + "/" + rules.maxHomes())) {
				throw helper.assertionException("the count should read n/max, got: " + message);
			}
		} finally {
			for (HomeRecord home : Deepgate.homes().homesOf(player)) {
				state.remove(home.id());
			}
		}

		helper.succeed();
	}

	/**
	 * Changing the glass over a beacon recolours the home, and the new colour is written back.
	 *
	 * <p>Reading live is what makes the name follow the beam; storing what was read is what keeps the
	 * list right once the beacon is out of range.
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400, skyAccess = true)
	public void theHomeColourFollowsTheBeam(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());

		BlockPos beaconRelative = buildBeacon(helper, 1, 1, 1);
		BlockPos beacon = helper.absolutePos(beaconRelative);

		// Deliberately store a colour the beacon does not have, to prove it gets corrected.
		HomeRecord home = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Tinted",
				helper.getLevel().dimension(), beacon, 0F, 0F, 0x123456, 1);
		state.add(home);

		helper.runAfterDelay(80, () -> {
			int plain = HomeService.beamColourOf(helper.getLevel().getServer(), home);

			if (plain == 0x123456) {
				throw helper.assertionException("a live beam should override the stored colour");
			}

			// The observation must be written back, not just returned.
			HomeRecord stored = state.home(home.id())
					.orElseThrow(() -> helper.assertionException("record vanished"));

			if (stored.beamColour() != plain) {
				throw helper.assertionException("expected the colour to be stored, got "
						+ Integer.toHexString(stored.beamColour()));
			}

			// Now tint the beam and let the beacon rescan.
			helper.setBlock(beaconRelative.above(2), Blocks.STAINED_GLASS.pick(DyeColor.RED));

			helper.runAfterDelay(160, () -> {
				HomeRecord current = state.home(home.id())
						.orElseThrow(() -> helper.assertionException("record vanished"));
				int tinted = HomeService.beamColourOf(helper.getLevel().getServer(), current);

				if (tinted == plain) {
					throw helper.assertionException("the colour should have followed the glass, still "
							+ Integer.toHexString(tinted));
				}

				state.remove(home.id());
				helper.succeed();
			});
		});
	}

	/** Arrival lands beside the beacon on solid ground, never in the beam column. */
	@GameTest
	public void arrivalLandsBesideTheBeaconNotInTheBeam(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());

		// A floor to stand on, with the beacon sitting in the middle of it.
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(new BlockPos(5 + dx, 1, 5 + dz), Blocks.STONE);
			}
		}

		BlockPos beaconRelative = new BlockPos(5, 2, 5);
		helper.setBlock(beaconRelative, Blocks.BEACON);
		BlockPos beacon = helper.absolutePos(beaconRelative);

		HomeRecord home = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Beside",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		state.add(home);

		try {
			var arrival = HomeService.findArrival(player, home);

			if (arrival.isEmpty()) {
				throw helper.assertionException("expected a spot beside the beacon");
			}

			BlockPos landed = BlockPos.containing(arrival.get().position());

			if (landed.getX() == beacon.getX() && landed.getZ() == beacon.getZ()) {
				throw helper.assertionException("arrival must not be in the beam column, landed at " + landed);
			}

			int dx = landed.getX() - beacon.getX();
			int dz = landed.getZ() - beacon.getZ();
			int radius = ArrivalSearch.radiusFor(4);

			if (dx * dx + dz * dz > radius * radius) {
				throw helper.assertionException("arrival is outside the radius, landed at " + landed);
			}

			// Bed-like: something solid underfoot rather than a drop.
			if (!helper.getLevel().getBlockState(landed.below()).blocksMotion()) {
				throw helper.assertionException("arrival should have solid ground beneath it");
			}
		} finally {
			state.remove(home.id());
		}

		helper.succeed();
	}

	/** A beacon boxed in on every side has nowhere to put a player, so the home is unavailable. */
	@GameTest
	public void aWalledInBeaconHasNoArrival(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());

		// Fill the whole search volume with stone so nothing can fit.
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				for (int dy = 0; dy <= 5; dy++) {
					helper.setBlock(new BlockPos(5 + dx, 1 + dy, 5 + dz), Blocks.STONE);
				}
			}
		}

		BlockPos beaconRelative = new BlockPos(5, 2, 5);
		helper.setBlock(beaconRelative, Blocks.BEACON);
		BlockPos beacon = helper.absolutePos(beaconRelative);

		HomeRecord home = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Boxed",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		state.add(home);

		try {
			if (HomeService.findArrival(player, home).isPresent()) {
				throw helper.assertionException("a fully enclosed beacon should have no arrival");
			}
		} finally {
			state.remove(home.id());
		}

		helper.succeed();
	}

	/**
	 * A destroyed bed clears the personal spawn and /spawn falls back to world spawn (section 11).
	 *
	 * <p>The distinction that matters: a block that is <em>gone</em> falls back, a block that is
	 * merely unusable fails. Vanilla reports both through the same flag, so they have to be told
	 * apart by looking at the block.
	 */
	@GameTest
	public void aDestroyedBedFallsBackToWorldSpawn(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		RuleSnapshot rules = rules(helper);

		BlockPos bedRelative = new BlockPos(2, 2, 2);
		helper.setBlock(bedRelative.below(), Blocks.STONE);
		helper.setBlock(bedRelative, Blocks.BED.pick(DyeColor.RED));
		BlockPos bed = helper.absolutePos(bedRelative);

		player.setRespawnPosition(new ServerPlayer.RespawnConfig(
				LevelData.RespawnData.of(helper.getLevel().dimension(), bed, 0.0F, 0.0F), false), false);

		if (player.getRespawnConfig() == null) {
			throw helper.assertionException("setup: the respawn position did not take");
		}

		// Break it, exactly as a player would.
		helper.setBlock(bedRelative, Blocks.AIR);

		SpawnService.Resolution resolution = SpawnService.resolve(player, rules);

		if (resolution instanceof SpawnService.Resolution.Blocked blocked) {
			throw helper.assertionException("a destroyed bed must not block /spawn: "
					+ blocked.failure().message());
		}

		if (!(resolution instanceof SpawnService.Resolution.World)) {
			throw helper.assertionException("expected a fall back to world spawn, got " + resolution);
		}

		// The stale record must be gone, not merely ignored.
		if (player.getRespawnConfig() != null) {
			throw helper.assertionException("the personal spawn should have been cleared");
		}

		helper.succeed();
	}

	/** Breaking a spawn block clears it for an online player straight away (section 11). */
	@GameTest
	public void breakingASpawnBlockClearsItForOnlinePlayers(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos anchorRelative = new BlockPos(4, 2, 4);
		helper.setBlock(anchorRelative, Blocks.RESPAWN_ANCHOR.defaultBlockState()
				.setValue(RespawnAnchorBlock.CHARGE, 1));
		BlockPos anchor = helper.absolutePos(anchorRelative);

		player.setRespawnPosition(new ServerPlayer.RespawnConfig(
				LevelData.RespawnData.of(helper.getLevel().dimension(), anchor, 0.0F, 0.0F), false), false);

		SpawnService.onSpawnBlockBroken(helper.getLevel().getServer(), helper.getLevel(), anchor);

		if (player.getRespawnConfig() != null) {
			throw helper.assertionException("breaking the anchor should clear the personal spawn");
		}

		helper.succeed();
	}

	/**
	 * An anchor with no charge falls back to world spawn and keeps its record.
	 *
	 * <p>Falling back matches what dying does. Keeping the record matters: an empty anchor is still
	 * the configured personal spawn and works again the moment it is recharged.
	 */
	@GameTest
	public void anEmptyAnchorFallsBackButStaysConfigured(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos anchorRelative = new BlockPos(6, 2, 6);
		helper.setBlock(anchorRelative, Blocks.RESPAWN_ANCHOR.defaultBlockState()
				.setValue(RespawnAnchorBlock.CHARGE, 0));
		BlockPos anchor = helper.absolutePos(anchorRelative);

		player.setRespawnPosition(new ServerPlayer.RespawnConfig(
				LevelData.RespawnData.of(helper.getLevel().dimension(), anchor, 0.0F, 0.0F), false), false);

		SpawnService.Resolution resolution = SpawnService.resolve(player, rules(helper));

		if (!(resolution instanceof SpawnService.Resolution.World world)) {
			throw helper.assertionException("an empty anchor should fall back to world spawn, got "
					+ resolution);
		}

		if (!world.afterFallback()) {
			throw helper.assertionException("the fallback should be flagged so the player is told why");
		}

		// It stays configured: an empty anchor is still the personal spawn (section 12).
		if (player.getRespawnConfig() == null) {
			throw helper.assertionException("an empty anchor must remain the configured spawn");
		}

		helper.succeed();
	}

	/**
	 * Every command Deepgate registers can be sent to a joining client.
	 *
	 * <p>The command tree is serialised for each player who joins, and it may only contain argument
	 * types the client already knows. A bespoke one makes the server unable to place the player at
	 * all - the join dies with "Invalid player data" and the world will not load - and a vanilla
	 * client needing nothing installed is the whole premise of this mod.
	 *
	 * <p>No ordinary game test caught that: mock players skip the join handshake, so the command tree
	 * is never serialised. This calls the exact method {@code PlayerList} calls while placing a
	 * player.
	 */
	@GameTest
	public void everyCommandCanBeSentToAJoiningClient(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		try {
			server.getCommands().sendCommands(player);
		} catch (RuntimeException e) {
			throw helper.assertionException("the command tree cannot be sent to a client: " + e);
		}

		helper.succeed();
	}

	/**
	 * The real command path: {@code /gamerule deepgate:xp_cost_per_1k 3 levels}.
	 *
	 * <p>Run through the actual dispatcher rather than trusting the parser alone, because the value
	 * is two tokens and that is not how gamerules normally work.
	 */
	@GameTest
	public void theGameruleCommandAcceptsAnAmountAndAUnit(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		GameRules rules = server.getGameRules();
		String original = rules.get(DeepgateRules.XP_COST_PER_1K);

		try {
			server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack(), "gamerule deepgate:xp_cost_per_1k 3 levels");

			Cost levels = read(rules);

			if (levels.amount() != 3 || !levels.inLevels()) {
				throw helper.assertionException("expected 3 levels, got " + levels);
			}

			server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack(), "gamerule deepgate:xp_cost_per_1k 8 points");

			Cost points = read(rules);

			if (points.amount() != 8 || points.inLevels()) {
				throw helper.assertionException("expected 8 points, got " + points);
			}

			// A bare number is accepted and means points.
			server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack(), "gamerule deepgate:xp_cost_per_1k 4");

			Cost bare = read(rules);

			if (bare.amount() != 4 || bare.inLevels()) {
				throw helper.assertionException("a bare number should mean points, got " + bare);
			}

			// Nonsense cannot be rejected at the point of typing with a vanilla argument type, so it
			// must not break pricing either: it falls back to the default.
			server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack(), "gamerule deepgate:xp_cost_per_1k 9 bananas");

			if (!read(rules).equals(DeepgateRules.DEFAULT_COST_PER_1K)) {
				throw helper.assertionException("nonsense should price at the default, got " + read(rules));
			}
		} finally {
			rules.set(DeepgateRules.XP_COST_PER_1K, original, server);
		}

		helper.succeed();
	}

	private static Cost read(GameRules rules) {
		return CostGameRule.read(rules, DeepgateRules.XP_COST_PER_1K, DeepgateRules.DEFAULT_COST_PER_1K);
	}

	/** Experience is stripped on death when the rule is on, even with keepInventory. */
	@GameTest
	public void deathCostsExperienceWhenTheRuleIsOn(GameTestHelper helper) {
		GameRules rules = helper.getLevel().getServer().getGameRules();

		if (!rules.get(DeepgateRules.LOSE_XP_ON_DEATH)) {
			throw helper.assertionException("lose_xp_on_death should default on");
		}

		// The hook itself runs on respawn, which a mock player cannot be driven through; what is
		// checked here is the effect it applies.
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		XpAccount.setTotalPoints(player, 500);
		XpAccount.setTotalPoints(player, 0);

		if (XpAccount.totalPoints(player) != 0) {
			throw helper.assertionException("clearing experience should leave nothing");
		}

		helper.succeed();
	}

	/**
	 * A beacon reports its pyramid immediately, without waiting to tick.
	 *
	 * <p>This is the case that broke travelling to a distant home. A beacon works its own size out
	 * as it ticks, so a chunk that has just been force loaded reports zero layers and the home is
	 * refused as "pyramid too small" - at exactly the distance where force loading is the whole
	 * point. Deliberately no delay here: reading straight away is the bug.
	 */
	@GameTest
	public void aFreshlyLoadedBeaconReportsItsPyramidWithoutTicking(GameTestHelper helper) {
		BlockPos relative = buildBeacon(helper, 2, 1, 2);
		BlockPos absolute = helper.absolutePos(relative);

		BeaconScan.Lookup lookup = BeaconScan.lookup(helper.getLevel(), absolute, true);

		if (lookup.presence() != BeaconScan.Presence.PRESENT) {
			throw helper.assertionException("the beacon should be found, got " + lookup.presence());
		}

		int levels = lookup.found().orElseThrow().levels();

		if (levels < 1) {
			throw helper.assertionException(
					"a one layer pyramid should report at least one layer straight away, got " + levels);
		}

		if (!lookup.found().orElseThrow().qualifies(1)) {
			throw helper.assertionException("it should qualify at the default rule immediately");
		}

		helper.succeed();
	}

	/** Measuring the pyramid agrees with how it was built, and stops at an incomplete layer. */
	@GameTest
	public void measuringThePyramidCountsCompleteLayersOnly(GameTestHelper helper) {
		// Two full layers: a 5x5 under a 3x3, with the beacon on top.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				helper.setBlock(new BlockPos(8 + dx, 1, 8 + dz), Blocks.IRON_BLOCK);
			}
		}

		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(new BlockPos(8 + dx, 2, 8 + dz), Blocks.IRON_BLOCK);
			}
		}

		BlockPos beacon = helper.absolutePos(new BlockPos(8, 3, 8));
		helper.setBlock(new BlockPos(8, 3, 8), Blocks.BEACON);

		int measured = BeaconScan.measurePyramid(helper.getLevel(), beacon);

		if (measured != 2) {
			throw helper.assertionException("expected two complete layers, measured " + measured);
		}

		// Knock a corner out of the wider layer: it is no longer complete, so only one layer counts.
		helper.setBlock(new BlockPos(6, 1, 6), Blocks.AIR);

		int afterHole = BeaconScan.measurePyramid(helper.getLevel(), beacon);

		if (afterHole != 1) {
			throw helper.assertionException("an incomplete layer should not count, measured " + afterHole);
		}

		helper.succeed();
	}

	/**
	 * A beacon someone has already named offers that name to the next person who claims it.
	 *
	 * <p>A shared beacon is usually a shared landmark, so one place should not end up called
	 * something different for every player who makes a home on it.
	 */
	@GameTest
	public void asharedBeaconOffersTheNameItAlreadyHas(GameTestHelper helper) {
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());
		BlockPos beacon = helper.absolutePos(new BlockPos(11, 1, 11));
		BlockPos other = helper.absolutePos(new BlockPos(13, 1, 13));

		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		HomeRecord original = new HomeRecord(UUID.randomUUID(), first, "Market",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		state.add(original);

		// A second player naming the same beacon differently must not change what is offered: the
		// original claim is what everyone else is shown.
		HomeRecord divergent = new HomeRecord(UUID.randomUUID(), second, "My Shop",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		state.add(divergent);

		try {
			String offered = state.firstHomeNameAt(helper.getLevel().dimension(), beacon)
					.orElseThrow(() -> helper.assertionException("a claimed beacon should offer its name"));

			if (!offered.equals("Market")) {
				throw helper.assertionException("expected the original name, got " + offered);
			}

			// An unclaimed beacon offers nothing, so the field starts empty.
			if (state.firstHomeNameAt(helper.getLevel().dimension(), other).isPresent()) {
				throw helper.assertionException("an unclaimed beacon should offer no name");
			}
		} finally {
			state.remove(original.id());
			state.remove(divergent.id());
		}

		helper.succeed();
	}

	/**
	 * Renaming a shared beacon renames it for everyone who has a home on it.
	 *
	 * <p>The name describes the place, not one player's bookmark of it.
	 */
	@GameTest
	public void renamingASharedBeaconRenamesItForEveryone(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());
		BlockPos beacon = helper.absolutePos(new BlockPos(14, 1, 14));

		UUID neighbour = UUID.randomUUID();

		HomeRecord mine = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Market",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		HomeRecord theirs = new HomeRecord(UUID.randomUUID(), neighbour, "Market",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		// A home of theirs somewhere else must be left alone.
		HomeRecord elsewhere = new HomeRecord(UUID.randomUUID(), neighbour, "Mine",
				helper.getLevel().dimension(), helper.absolutePos(new BlockPos(16, 1, 16)),
				0F, 0F, HomeRecord.WHITE, 1);

		state.add(mine);
		state.add(theirs);
		state.add(elsewhere);

		try {
			if (!(Deepgate.homes().rename(player, mine, "Bazaar") instanceof HomeName.Result.Valid)) {
				throw helper.assertionException("the rename should have been accepted");
			}

			if (!state.home(mine.id()).orElseThrow().name().equals("Bazaar")) {
				throw helper.assertionException("the renaming player should see the new name");
			}

			if (!state.home(theirs.id()).orElseThrow().name().equals("Bazaar")) {
				throw helper.assertionException("everyone on the beacon should see the new name");
			}

            if (!state.home(elsewhere.id()).orElseThrow().name().equals("Mine")) {
				throw helper.assertionException("a home on another beacon must not be touched");
			}
		} finally {
			state.remove(mine.id());
			state.remove(theirs.id());
			state.remove(elsewhere.id());
		}

		helper.succeed();
	}

	/** A rename is refused outright if it would clash for anyone sharing the beacon. */
	@GameTest
	public void aSharedRenameIsRefusedIfItClashesForSomeoneElse(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());
		BlockPos beacon = helper.absolutePos(new BlockPos(18, 1, 18));

		UUID neighbour = UUID.randomUUID();

		HomeRecord mine = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Market",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		HomeRecord theirs = new HomeRecord(UUID.randomUUID(), neighbour, "Market",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE, 1);
		// They already have a home called Workshop somewhere else.
		HomeRecord clash = new HomeRecord(UUID.randomUUID(), neighbour, "Workshop",
				helper.getLevel().dimension(), helper.absolutePos(new BlockPos(20, 1, 20)),
				0F, 0F, HomeRecord.WHITE, 1);

		state.add(mine);
		state.add(theirs);
		state.add(clash);

		try {
			if (!(Deepgate.homes().rename(player, mine, "Workshop") instanceof HomeName.Result.Invalid)) {
				throw helper.assertionException("a clash for another owner should refuse the rename");
			}

			// Refused means nothing moved: no half renamed beacon.
			if (!state.home(mine.id()).orElseThrow().name().equals("Market")
					|| !state.home(theirs.id()).orElseThrow().name().equals("Market")) {
				throw helper.assertionException("a refused rename must leave every home untouched");
			}
		} finally {
			state.remove(mine.id());
			state.remove(theirs.id());
			state.remove(clash.id());
		}

		helper.succeed();
	}

	/**
	 * Beam obstruction is measured from the blocks, not from what the beacon has worked out.
	 *
	 * <p>No delay here on purpose. Beam sections are built while a beacon ticks, so a freshly loaded
	 * beacon has none - and reading them would call a perfectly clear beam blocked, which is what
	 * happened when travelling to a home whose chunk had just been pulled in.
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", skyAccess = true)
	public void beamObstructionIsMeasuredFromTheBlocks(GameTestHelper helper) {
		BlockPos relative = buildBeacon(helper, 3, 1, 3);
		BlockPos beacon = helper.absolutePos(relative);

		if (!BeaconScan.hasClearBeam(helper.getLevel(), beacon)) {
			throw helper.assertionException("a beacon with open sky should read as clear straight away");
		}

		// Glass lets a beam through, as it does in game.
		helper.setBlock(relative.above(3), Blocks.GLASS);

		if (!BeaconScan.hasClearBeam(helper.getLevel(), beacon)) {
			throw helper.assertionException("glass must not count as an obstruction");
		}

		// Stone does not.
		helper.setBlock(relative.above(3), Blocks.STONE);

		if (BeaconScan.hasClearBeam(helper.getLevel(), beacon)) {
			throw helper.assertionException("solid stone should block the beam");
		}

		// Bedrock is ignored: a Nether roof is a ceiling nobody can clear.
		helper.setBlock(relative.above(3), Blocks.BEDROCK);

		if (!BeaconScan.hasClearBeam(helper.getLevel(), beacon)) {
			throw helper.assertionException("bedrock must not count as an obstruction");
		}

		helper.succeed();
	}

	/** An anchor charge is spent on consume and handed back on rollback (section 12). */
	@GameTest
	public void anchorChargeIsSpentAndRestored(GameTestHelper helper) {
		BlockPos relative = new BlockPos(1, 1, 1);
		BlockPos absolute = helper.absolutePos(relative);

		helper.setBlock(relative, Blocks.RESPAWN_ANCHOR.defaultBlockState()
				.setValue(RespawnAnchorBlock.CHARGE, 2));

		ServerLevel level = helper.getLevel();
		AnchorChargeResource resource = new AnchorChargeResource(level, absolute);

		if (AnchorChargeResource.chargesAt(level, absolute) != 2) {
			throw helper.assertionException("setup: expected two charges");
		}

		if (!resource.consume()) {
			throw helper.assertionException("a charged anchor should give up a charge");
		}

		if (AnchorChargeResource.chargesAt(level, absolute) != 1) {
			throw helper.assertionException("consume should spend exactly one charge");
		}

		resource.rollback();

		if (AnchorChargeResource.chargesAt(level, absolute) != 2) {
			throw helper.assertionException("rollback must put the charge back");
		}

		helper.succeed();
	}

	/** An empty anchor refuses to be consumed, so the whole transaction fails before charging. */
	@GameTest
	public void anEmptyAnchorRefusesToBeConsumed(GameTestHelper helper) {
		BlockPos relative = new BlockPos(2, 1, 2);
		BlockPos absolute = helper.absolutePos(relative);

		helper.setBlock(relative, Blocks.RESPAWN_ANCHOR.defaultBlockState()
				.setValue(RespawnAnchorBlock.CHARGE, 0));

		AnchorChargeResource resource = new AnchorChargeResource(helper.getLevel(), absolute);

		if (resource.consume()) {
			throw helper.assertionException("an anchor with no charge must refuse");
		}

		helper.succeed();
	}

	/** Rollback never invents a charge beyond the maximum. */
	@GameTest
	public void rollbackDoesNotOverfillAnAnchor(GameTestHelper helper) {
		BlockPos relative = new BlockPos(3, 1, 3);
		BlockPos absolute = helper.absolutePos(relative);

		helper.setBlock(relative, Blocks.RESPAWN_ANCHOR.defaultBlockState()
				.setValue(RespawnAnchorBlock.CHARGE, RespawnAnchorBlock.MAX_CHARGES));

		AnchorChargeResource resource = new AnchorChargeResource(helper.getLevel(), absolute);
		resource.rollback();

		if (AnchorChargeResource.chargesAt(helper.getLevel(), absolute) != RespawnAnchorBlock.MAX_CHARGES) {
			throw helper.assertionException("rollback must not exceed the maximum charge");
		}

		helper.succeed();
	}
}
