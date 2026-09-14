package com.brendan.deepgate;

import com.brendan.deepgate.core.Cost;
import com.brendan.deepgate.core.RuleSnapshot;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;

/**
 * Deepgate's twelve gamerules (spec section 3).
 *
 * <p>All primitive booleans and integers: V1 Lite deliberately avoids enum rules so there is no
 * custom parser to maintain. Portal travel has no experience rule at all because Deepgate portals
 * are always free.
 */
public final class DeepgateRules {
	private DeepgateRules() {
	}

	public static final GameRule<Boolean> ALLOW_CROSS_DIMENSION = bool("allow_cross_dimension", true);
	public static final GameRule<Boolean> ALLOW_IN_COMBAT = bool("allow_in_combat", false);
	public static final GameRule<Integer> COMBAT_SECONDS = intRule("combat_seconds", 10, 0, 300);
	public static final GameRule<Boolean> ALLOW_HOMES = bool("allow_homes", true);
	public static final GameRule<Integer> MAX_HOMES = intRule("max_homes", 10, 0, 100);
	public static final GameRule<Integer> HOME_BEACON_LAYERS = intRule("home_beacon_layers", 1, 1, 4);
	/** Prices carry their own unit: {@code /gamerule deepgate:xp_cost_per_1k 3 levels}. */
	public static final Cost DEFAULT_COST_PER_1K = new Cost(5, Cost.Unit.POINTS);
	public static final Cost DEFAULT_CROSS_DIMENSION = new Cost(25, Cost.Unit.POINTS);

	public static final GameRule<String> XP_COST_PER_1K =
			CostGameRule.register("xp_cost_per_1k", DEFAULT_COST_PER_1K);
	public static final GameRule<String> XP_CROSS_DIMENSION =
			CostGameRule.register("xp_cross_dimension", DEFAULT_CROSS_DIMENSION);
	public static final GameRule<Integer> XP_FREE_DISTANCE = intRule("xp_free_distance", 1000, 0, 100_000_000);
	public static final GameRule<Integer> BACK_WINDOW_SECONDS = intRule("back_window_seconds", 15, 0, 3600);
	public static final GameRule<Integer> PORTAL_MAX_FRAME_BLOCKS = intRule("portal_max_frame_blocks", 64, 8, 4096);

	/**
	 * Whether dying costs you all of your experience even with {@code keepInventory} on.
	 *
	 * <p>Experience is the currency every Deepgate fare is paid in, so keeping it through death makes
	 * travel free for anyone who has died recently. On by default for that reason; turn it off to
	 * restore the vanilla behaviour of keeping experience alongside your items.
	 */
	public static final GameRule<Boolean> LOSE_XP_ON_DEATH = bool("lose_xp_on_death", true);

	private static GameRule<Boolean> bool(String path, boolean defaultValue) {
		return GameRuleBuilder.forBoolean(defaultValue)
				.category(GameRuleCategory.MISC)
				.buildAndRegister(Identifier.fromNamespaceAndPath(Deepgate.MOD_ID, path));
	}

	private static GameRule<Integer> intRule(String path, int defaultValue, int min, int max) {
		return GameRuleBuilder.forInteger(defaultValue)
				.range(min, max)
				.category(GameRuleCategory.MISC)
				.buildAndRegister(Identifier.fromNamespaceAndPath(Deepgate.MOD_ID, path));
	}

	/**
	 * Touching this class triggers its static initialisers, which is what registers the rules.
	 * Called from the mod entrypoint so registration order is explicit rather than incidental.
	 */
	public static void register() {
		// Intentionally empty.
	}

	/**
	 * Capture every rule this teleport decision depends on, at one instant.
	 *
	 * <p>A snapshot means a quote and its commit compare identical inputs even if an operator edits
	 * a rule while a dialog is open, and it keeps the pricing engine free of Minecraft imports.
	 */
	public static RuleSnapshot snapshot(MinecraftServer server) {
		GameRules rules = server.getGameRules();

		return new RuleSnapshot(
				rules.get(ALLOW_CROSS_DIMENSION),
				rules.get(ALLOW_IN_COMBAT),
				rules.get(COMBAT_SECONDS),
				rules.get(ALLOW_HOMES),
				rules.get(MAX_HOMES),
				rules.get(HOME_BEACON_LAYERS),
				CostGameRule.read(rules, XP_COST_PER_1K, DEFAULT_COST_PER_1K),
				CostGameRule.read(rules, XP_CROSS_DIMENSION, DEFAULT_CROSS_DIMENSION),
				rules.get(XP_FREE_DISTANCE),
				rules.get(BACK_WINDOW_SECONDS),
				rules.get(PORTAL_MAX_FRAME_BLOCKS));
	}
}
