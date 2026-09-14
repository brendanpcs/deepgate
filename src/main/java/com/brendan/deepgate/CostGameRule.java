package com.brendan.deepgate;

import com.brendan.deepgate.core.Cost;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRuleType;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Gamerules whose value is an amount plus a unit, written {@code 5 points} or {@code 3 levels}.
 *
 * <p>The value is carried as a plain string and parsed on read. That looks indirect, and it is
 * deliberate: the command tree is serialised to every client that joins, and a vanilla client can
 * only be sent argument types it already knows. A bespoke argument type makes the server unable to
 * place a player at all - the join fails with "Invalid player data" - which is precisely the
 * breakage Deepgate exists to avoid, since a vanilla client must need nothing installed.
 *
 * <p>So the argument is {@link StringArgumentType#greedyString()}, which is vanilla and consumes the
 * rest of the line, giving room for both tokens. The cost of that choice is no tab completion for
 * the unit, and a typo being accepted by the command rather than rejected at the point of typing;
 * {@link #read} handles that by falling back and saying so in the log.
 *
 * <p>Fabric's {@code GameRuleBuilder} only exposes boolean, integer, double and enum, so the rule is
 * built directly. The generic {@code visit} on the visitor is what keeps a non-integer value safe
 * despite the rule declaring INT, which is all vanilla offers.
 */
public final class CostGameRule {
	private CostGameRule() {
	}

	/** Register a cost rule, storing its value as the same text the command takes. */
	public static GameRule<String> register(String path, Cost defaultValue) {
		GameRule<String> rule = new GameRule<>(
				GameRuleCategory.MISC,
				GameRuleType.INT,
				// Vanilla argument type: anything else cannot be sent to a vanilla client.
				StringArgumentType.greedyString(),
				GameRuleTypeVisitor::visit,
				Codec.STRING,
				CostGameRule::commandResult,
				defaultValue.toString(),
				FeatureFlagSet.of());

		return Registry.register(BuiltInRegistries.GAME_RULE, Deepgate.id(path), rule);
	}

	/**
	 * Read a cost rule, falling back when the stored text is not a valid cost.
	 *
	 * <p>Nothing stops an operator typing nonsense, so a bad value must not break pricing. The
	 * fallback is the rule default, and the reason is logged once per read so it is discoverable.
	 */
	public static Cost read(GameRules rules, GameRule<String> rule, Cost fallback) {
		String raw = rules.get(rule);

		return Cost.parse(raw).orElseGet(() -> {
			Deepgate.LOGGER.warn(
					"Gamerule {} is set to \"{}\", which is not an amount and a unit such as \"5 points\" "
							+ "or \"3 levels\". Using {} instead.",
					rule.getIdentifier(), raw, fallback);
			return fallback;
		});
	}

	/** The command result is the amount, so {@code /gamerule} reports something meaningful. */
	private static int commandResult(String raw) {
		return Cost.parse(raw).map(Cost::amount).orElse(0);
	}

	/** Validation helper for anything that wants to check a value before storing it. */
	public static DataResult<Cost> validate(String raw) {
		return Cost.parse(raw)
				.map(DataResult::success)
				.orElseGet(() -> DataResult.error(() -> "Not a valid cost: " + raw));
	}
}
