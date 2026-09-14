package com.brendan.deepgate.gametest;

import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.DeepgateRules;
import com.brendan.deepgate.core.RuleSnapshot;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
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
				helper.getLevel().dimension(), beacon, 0.0F, 0.0F, HomeRecord.WHITE);
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

		state.add(new HomeRecord(UUID.randomUUID(), alice, "A", level.dimension(), beacon, 0F, 0F, HomeRecord.WHITE));
		state.add(new HomeRecord(UUID.randomUUID(), bob, "B", level.dimension(), beacon, 0F, 0F, HomeRecord.WHITE));
		// A home on a different beacon must be left alone.
		BlockPos elsewhere = helper.absolutePos(new BlockPos(5, 1, 5));
		HomeRecord survivor = new HomeRecord(UUID.randomUUID(), alice, "C", level.dimension(), elsewhere, 0F, 0F, HomeRecord.WHITE);
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
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE);
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
				helper.getLevel().dimension(), empty, 0F, 0F, HomeRecord.WHITE);
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
					helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE));
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
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE);
		HomeRecord second = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Beta",
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE);
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
					helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE));
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
				helper.getLevel().dimension(), beacon, 0F, 0F, 0x123456);
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
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE);
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

			if (dx * dx + dz * dz > ArrivalSearch.RADIUS * ArrivalSearch.RADIUS) {
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
				helper.getLevel().dimension(), beacon, 0F, 0F, HomeRecord.WHITE);
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

	/** An anchor that still exists but has no charge fails rather than falling back (section 12). */
	@GameTest
	public void anEmptyAnchorBlocksSpawnRatherThanFallingBack(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos anchorRelative = new BlockPos(6, 2, 6);
		helper.setBlock(anchorRelative, Blocks.RESPAWN_ANCHOR.defaultBlockState()
				.setValue(RespawnAnchorBlock.CHARGE, 0));
		BlockPos anchor = helper.absolutePos(anchorRelative);

		player.setRespawnPosition(new ServerPlayer.RespawnConfig(
				LevelData.RespawnData.of(helper.getLevel().dimension(), anchor, 0.0F, 0.0F), false), false);

		SpawnService.Resolution resolution = SpawnService.resolve(player, rules(helper));

		if (!(resolution instanceof SpawnService.Resolution.Blocked blocked)) {
			throw helper.assertionException("an empty anchor must block, got " + resolution);
		}

		if (!blocked.failure().message().contains("charge")) {
			throw helper.assertionException("the reason should name the charge: "
					+ blocked.failure().message());
		}

		// It stays configured: an empty anchor is still the personal spawn (section 12).
		if (player.getRespawnConfig() == null) {
			throw helper.assertionException("an empty anchor must remain the configured spawn");
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
