package com.brendan.deepgate;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.brendan.deepgate.core.Cost;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRuleType;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;

/**
 * Registers gamerules whose value is an amount plus a unit, written {@code 5 points} or
 * {@code 3 levels}.
 *
 * <p>Vanilla gamerules are single valued, and {@code GameRuleType} only knows BOOL and INT - but the
 * rule itself is generic over its value type, and the visitor interface has a generic
 * {@code visit} alongside the typed ones. That is the same seam Fabric uses for its own double and
 * enum rules, which likewise declare INT while carrying something else.
 *
 * <p>Two tokens fit in one gamerule argument because Brigadier hands the argument type the whole
 * reader, so it can consume the unit after the number. A bare number is accepted and means points.
 *
 * <p>Fabric's {@code GameRuleBuilder} only exposes boolean, integer, double and enum, so this builds
 * the rule directly rather than going through it.
 */
public final class CostGameRule {
	private static final DynamicCommandExceptionType INVALID = new DynamicCommandExceptionType(
			value -> Component.literal("Expected an amount and a unit, such as \"5 points\" or \"3 levels\", got "
					+ value));

	/** Encodes as the same text the command takes, so the world file stays readable. */
	private static final Codec<Cost> CODEC = Codec.STRING.comapFlatMap(
			raw -> Cost.parse(raw)
					.map(DataResult::success)
					.orElseGet(() -> DataResult.error(() -> "Not a valid cost: " + raw)),
			Cost::toString);

	private CostGameRule() {
	}

	/** Register a cost rule and return it. */
	public static GameRule<Cost> register(String path, Cost defaultValue) {
		GameRule<Cost> rule = new GameRule<>(
				GameRuleCategory.MISC,
				// Declared INT because that is all vanilla offers; the generic visitor below is what
				// keeps the real type safe, so nothing ever casts this to an Integer.
				GameRuleType.INT,
				new CostArgument(),
				GameRuleTypeVisitor::visit,
				CODEC,
				cost -> cost.amount(),
				defaultValue,
				FeatureFlagSet.of());

		return Registry.register(BuiltInRegistries.GAME_RULE, Deepgate.id(path), rule);
	}

	/** Reads "5 points", "3 levels", or a bare "5". */
	private static final class CostArgument implements ArgumentType<Cost> {
		@Override
		public Cost parse(StringReader reader) throws CommandSyntaxException {
			int start = reader.getCursor();
			int amount = reader.readInt();

			if (amount < 0) {
				reader.setCursor(start);
				throw INVALID.createWithContext(reader, amount);
			}

			if (!reader.canRead() || reader.peek() != ' ') {
				// A bare number is allowed and means points.
				return new Cost(amount, Cost.Unit.POINTS);
			}

			reader.skip();
			String word = reader.readUnquotedString();
			Optional<Cost.Unit> unit = Cost.Unit.parse(word);

			if (unit.isEmpty()) {
				reader.setCursor(start);
				throw INVALID.createWithContext(reader, word);
			}

			return new Cost(amount, unit.get());
		}

		@Override
		public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context,
				SuggestionsBuilder builder) {
			String remaining = builder.getRemaining();
			int space = remaining.indexOf(' ');

			if (space < 0) {
				// Still typing the number; nothing useful to offer yet.
				return Suggestions.empty();
			}

			SuggestionsBuilder units = builder.createOffset(builder.getStart() + space + 1);

			for (Cost.Unit unit : Cost.Unit.values()) {
				units.suggest(unit.label());
			}

			return units.buildFuture();
		}

		@Override
		public Collection<String> getExamples() {
			return List.of("5", "5 points", "3 levels");
		}
	}
}
