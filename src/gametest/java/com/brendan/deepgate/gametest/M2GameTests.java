package com.brendan.deepgate.gametest;

import java.util.Optional;
import java.util.UUID;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.DeepgateRules;
import com.brendan.deepgate.core.RuleSnapshot;
import com.brendan.deepgate.home.BeaconScan;
import com.brendan.deepgate.home.HomeName;
import com.brendan.deepgate.home.HomeRecord;
import com.brendan.deepgate.home.HomeService;
import com.brendan.deepgate.spawn.AnchorChargeResource;
import com.brendan.deepgate.state.DeepgateState;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;

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
				helper.getLevel().dimension(), beacon, 0.0F, 0.0F);
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

		state.add(new HomeRecord(UUID.randomUUID(), alice, "A", level.dimension(), beacon, 0F, 0F));
		state.add(new HomeRecord(UUID.randomUUID(), bob, "B", level.dimension(), beacon, 0F, 0F));
		// A home on a different beacon must be left alone.
		BlockPos elsewhere = helper.absolutePos(new BlockPos(5, 1, 5));
		HomeRecord survivor = new HomeRecord(UUID.randomUUID(), alice, "C", level.dimension(), elsewhere, 0F, 0F);
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

	/** A home whose beacon is gone reports unavailable rather than deleting itself on read. */
	@GameTest
	public void aMissingBeaconMakesAHomeUnavailableWithoutDeletingIt(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DeepgateState state = DeepgateState.get(helper.getLevel().getServer());

		// Deliberately a position with no beacon on it.
		BlockPos empty = helper.absolutePos(new BlockPos(6, 1, 6));
		HomeRecord home = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Ghost",
				helper.getLevel().dimension(), empty, 0F, 0F);
		state.add(home);

		try {
			HomeService.Availability availability = Deepgate.homes().availability(player, home, rules(helper));

			if (availability.usable()) {
				throw helper.assertionException("a home with no beacon must not be usable");
			}

			// Reading must never delete: only an observed break does that (section 18).
			if (state.home(home.id()).isEmpty()) {
				throw helper.assertionException("checking availability must not delete the record");
			}
		} finally {
			state.remove(home.id());
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
					helper.getLevel().dimension(), beacon, 0F, 0F));
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
				helper.getLevel().dimension(), beacon, 0F, 0F);
		HomeRecord second = new HomeRecord(UUID.randomUUID(), player.getUUID(), "Beta",
				helper.getLevel().dimension(), beacon, 0F, 0F);
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
					helper.getLevel().dimension(), beacon, 0F, 0F));
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
