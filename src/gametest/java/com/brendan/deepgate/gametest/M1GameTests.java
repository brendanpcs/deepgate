package com.brendan.deepgate.gametest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.brendan.deepgate.Deepgate;
import com.brendan.deepgate.DeepgateRules;
import com.brendan.deepgate.core.Destination;
import com.brendan.deepgate.core.Fare;
import com.brendan.deepgate.core.Pricing;
import com.brendan.deepgate.core.Quotes;
import com.brendan.deepgate.core.RuleSnapshot;
import com.brendan.deepgate.core.TeleportService;
import com.brendan.deepgate.core.XpAccount;
import com.brendan.deepgate.core.XpCurve;
import com.brendan.deepgate.dialog.DialogService;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * World-dependent checks for M1: the experience transaction, {@code /back}, and combat gating.
 *
 * <p>These need a real level and a real player, which is exactly the line the plan draws between
 * this layer and the pure JUnit suite. Pricing arithmetic, the experience curve, failure precedence
 * and nonce handling are all tested without the game and are not repeated here.
 */
public final class M1GameTests {
	private static RuleSnapshot rules(GameTestHelper helper) {
		return DeepgateRules.snapshot(helper.getLevel().getServer());
	}

	/** The twelve gamerules are registered and carry the defaults from spec section 3. */
	@GameTest
	public void gameRulesRegisterWithSpecDefaults(GameTestHelper helper) {
		RuleSnapshot rules = rules(helper);

		assertTrue(helper, rules.allowCrossDimension(), "allow_cross_dimension should default true");
		assertTrue(helper, !rules.allowInCombat(), "allow_in_combat should default false");
		assertEquals(helper, 10, rules.combatSeconds(), "combat_seconds");
		assertTrue(helper, rules.allowHomes(), "allow_homes should default true");
		assertEquals(helper, 10, rules.maxHomes(), "max_homes");
		assertEquals(helper, 1, rules.homeBeaconLayers(), "home_beacon_layers");
		assertEquals(helper, 5, rules.xpCostPer1k(), "xp_cost_per_1k");
		assertTrue(helper, !rules.xpCostInLevels(), "xp_cost_in_levels should default false");
		assertEquals(helper, 25, rules.xpCrossDimension(), "xp_cross_dimension");
		assertEquals(helper, 1000, rules.xpFreeDistance(), "xp_free_distance");
		assertEquals(helper, 15, rules.backWindowSeconds(), "back_window_seconds");
		assertEquals(helper, 64, rules.portalMaxFrameBlocks(), "portal_max_frame_blocks");

		helper.succeed();
	}

	/** Reading experience back out of a player round-trips exactly through the vanilla curve. */
	@GameTest
	public void experienceReadsAndWritesExactly(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		for (int total : new int[] {0, 1, 7, 351, 352, 394, 1507, 1628, 5000}) {
			XpAccount.setTotalPoints(player, total);

			assertEquals(helper, total, XpAccount.totalPoints(player), "round trip at " + total);
			assertEquals(helper, XpCurve.levelForTotal(total), player.experienceLevel,
					"level at total " + total);
		}

		helper.succeed();
	}

	/** A paid teleport charges exactly the fare and lands the player where it said it would. */
	@GameTest
	public void paidTeleportChargesExactlyAndArrives(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		XpAccount.setTotalPoints(player, 500);

		Vec3 origin = player.position();
		Vec3 target = origin.add(0.0D, 0.0D, 2.0D);

		TeleportService teleports = Deepgate.teleports();
		teleports.clearBack(player.getUUID());

		TeleportService.Result result = teleports.execute(
				player,
				new Destination(helper.getLevel(), target, 0.0F, 0.0F),
				new Fare(20, false, 0.0D, false),
				List.of(),
				List.of(),
				rules(helper));

		assertTrue(helper, result instanceof TeleportService.Result.Success,
				"teleport should have succeeded, got " + result);
		assertEquals(helper, 480, XpAccount.totalPoints(player), "experience after a 20 point fare");
		assertTrue(helper, player.position().distanceTo(target) < 0.001D,
				"player should be at the destination, was " + player.position());

		helper.succeed();
	}

	/** A fare the player cannot afford charges nothing and moves nobody. */
	@GameTest
	public void anUnaffordableFareLeavesExperienceUntouched(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		XpAccount.setTotalPoints(player, 5);

		Vec3 origin = player.position();

		TeleportService.Result result = Deepgate.teleports().execute(
				player,
				new Destination(helper.getLevel(), origin.add(0.0D, 0.0D, 2.0D), 0.0F, 0.0F),
				new Fare(400, false, 0.0D, false),
				List.of(),
				List.of(),
				rules(helper));

		assertTrue(helper, result instanceof TeleportService.Result.Failed, "should have been refused");
		assertEquals(helper, 5, XpAccount.totalPoints(player), "experience must be untouched");
		assertTrue(helper, player.position().distanceTo(origin) < 0.001D, "player must not have moved");

		helper.succeed();
	}

	/** A successful undo restores the exact position and refunds the exact charge, once. */
	@GameTest
	public void backRefundsExactlyOnceAndRejectsASecondUse(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		XpAccount.setTotalPoints(player, 500);

		Vec3 origin = player.position();
		TeleportService teleports = Deepgate.teleports();
		teleports.clearBack(player.getUUID());

		TeleportService.Result outbound = teleports.execute(
				player,
				new Destination(helper.getLevel(), origin.add(0.0D, 0.0D, 2.0D), 0.0F, 0.0F),
				new Fare(20, false, 0.0D, false),
				List.of(),
				List.of(),
				rules(helper));

		assertTrue(helper, outbound instanceof TeleportService.Result.Success, "outbound leg failed");
		assertEquals(helper, 480, XpAccount.totalPoints(player), "charged");

		TeleportService.Result back = teleports.back(player, rules(helper));

		assertTrue(helper, back instanceof TeleportService.Result.Success, "back should have succeeded");
		assertEquals(helper, 500, XpAccount.totalPoints(player), "refund must be exact");
		assertTrue(helper, player.position().distanceTo(origin) < 0.001D,
				"back must restore the exact position, was " + player.position());

		// Single use: a successful back never leaves another one behind.
		TeleportService.Result second = teleports.back(player, rules(helper));

		assertTrue(helper, second instanceof TeleportService.Result.Failed, "second back must be rejected");
		assertEquals(helper, 500, XpAccount.totalPoints(player), "second back must not refund again");

		helper.succeed();
	}

	/** Combat blocks a teleport and nothing is charged while it does. */
	@GameTest
	public void combatBlocksTheCommitWithoutCharging(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		XpAccount.setTotalPoints(player, 500);

		Vec3 origin = player.position();
		RuleSnapshot rules = rules(helper);

		Deepgate.combat().tag(helper.getLevel().getServer(), player, rules.combatSeconds());

		TeleportService.Result result = Deepgate.teleports().execute(
				player,
				new Destination(helper.getLevel(), origin.add(0.0D, 0.0D, 2.0D), 0.0F, 0.0F),
				new Fare(20, false, 0.0D, false),
				List.of(),
				List.of(),
				rules);

		assertTrue(helper, result instanceof TeleportService.Result.Failed, "combat should block the commit");
		assertEquals(helper, 500, XpAccount.totalPoints(player), "nothing may be charged while blocked");
		assertTrue(helper, player.position().distanceTo(origin) < 0.001D, "player must not have moved");

		Deepgate.combat().clear(player.getUUID());
		helper.succeed();
	}

	/** An obstructed arrival fails outright; Deepgate never looks for somewhere nearby instead. */
	@GameTest
	public void anObstructedArrivalFailsRatherThanRelocating(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		XpAccount.setTotalPoints(player, 500);

		Vec3 origin = player.position();

		// Straight down into bedrock: solid, and definitely not somewhere a player fits.
		Vec3 inside = new Vec3(origin.x(), helper.getLevel().getMinY() + 0.5D, origin.z());

		TeleportService.Result result = Deepgate.teleports().execute(
				player,
				new Destination(helper.getLevel(), inside, 0.0F, 0.0F),
				new Fare(20, false, 0.0D, false),
				List.of(),
				List.of(),
				rules(helper));

		assertTrue(helper, result instanceof TeleportService.Result.Failed, "obstructed arrival must fail");
		assertEquals(helper, 500, XpAccount.totalPoints(player), "nothing may be charged");

		helper.succeed();
	}

	/**
	 * A dialog action runs once per issued nonce, however many times it is delivered.
	 *
	 * <p>This is the property that kept a mixin threading bug from becoming a double charge: vanilla
	 * enters the packet handler twice for one click, and an injection at HEAD fired on both passes.
	 * The nonce turned the second delivery into a no-op instead of a second teleport. The injection
	 * now sits at TAIL so only one delivery happens at all, but the guarantee is worth pinning down,
	 * because it is what makes any duplicate - lag, a double click, a replayed packet - harmless.
	 */
	@GameTest
	public void aDialogActionRunsOnceHoweverOftenItIsDelivered(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DialogService dialogs = Deepgate.dialogs();

		registerProbeOnce(dialogs);
		PROBE_RUNS.set(0);

		CompoundTag payload = new CompoundTag();
		payload.putString(DialogService.NONCE_KEY,
				dialogs.nonces().issue(player.getUUID(), helper.getLevel().getServer().getTickCount()));

		dialogs.dispatch(player, PROBE_ID, Optional.<Tag>of(payload));
		dialogs.dispatch(player, PROBE_ID, Optional.<Tag>of(payload));
		dialogs.dispatch(player, PROBE_ID, Optional.<Tag>of(payload));

		assertEquals(helper, 1, PROBE_RUNS.get(), "handler runs per issued nonce");

		// An action carrying no nonce at all is refused outright.
		dialogs.dispatch(player, PROBE_ID, Optional.<Tag>of(new CompoundTag()));
		assertEquals(helper, 1, PROBE_RUNS.get(), "a nonce-less action must not run");

		helper.succeed();
	}

	/**
	 * A creative player is quoted free, and still travels with an empty experience bar.
	 *
	 * <p>Without the bypass a creative player is refused a teleport for want of a resource creative
	 * mode does not ask them to gather.
	 *
	 * <p>The mock player is already creative and {@code setGameMode} does not take on a mock, so the
	 * survival half is proved by pricing the identical distance through the normal engine. That also
	 * makes the point sharper: the same trip is not free, so the bypass is attributable to the game
	 * mode rather than to the distance being short.
	 */
	@GameTest
	public void creativeTravelsFree(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		XpAccount.setTotalPoints(player, 0);

		assertTrue(helper, player.isCreative(), "this test needs a creative player to be meaningful");

		RuleSnapshot rules = rules(helper);
		Vec3 faraway = player.position().add(50_000.0D, 0.0D, 50_000.0D);

		Fare quoted = Quotes.quote(player, helper.getLevel(), faraway, rules);

		assertTrue(helper, quoted.isFree(), "creative must be quoted free, was " + quoted.amount());
		assertTrue(helper, quoted.distance() > 1000.0D,
				"the distance must still be reported, was " + quoted.distance());

		// The same distance, priced normally, is emphatically not free.
		int survivalFare = Pricing.quote(quoted.distance(), false, rules).amount();
		assertTrue(helper, survivalFare > 0,
				"the same trip should cost a survival player something, was " + survivalFare);

		// And with zero experience the creative player still gets where they are going.
		TeleportService.Result result = Deepgate.teleports().execute(
				player,
				new Destination(helper.getLevel(), player.position().add(0.0D, 0.0D, 2.0D), 0.0F, 0.0F),
				quoted,
				List.of(),
				List.of(),
				rules);

		assertTrue(helper, result instanceof TeleportService.Result.Success,
				"a creative player with no experience must still travel, got " + result);
		assertEquals(helper, 0, XpAccount.totalPoints(player), "nothing may be taken from a creative player");

		helper.succeed();
	}

	// ------------------------------------------------------------ probe action

	private static final Identifier PROBE_ID = Deepgate.id("gametest/probe");
	private static final AtomicInteger PROBE_RUNS = new AtomicInteger();
	private static boolean probeRegistered;

	private static void registerProbeOnce(DialogService dialogs) {
		if (!probeRegistered) {
			dialogs.register(PROBE_ID, (player, payload) -> PROBE_RUNS.incrementAndGet());
			probeRegistered = true;
		}
	}

	// ------------------------------------------------------------ assertions

	private static void assertTrue(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw helper.assertionException(message);
		}
	}

	private static void assertEquals(GameTestHelper helper, int expected, int actual, String what) {
		if (expected != actual) {
			throw helper.assertionException(what + ": expected " + expected + " but was " + actual);
		}
	}
}
