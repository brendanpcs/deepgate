package com.brendan.deepgate.home;

import java.util.Collection;
import java.util.Locale;

/**
 * Validation for home names, shared by creation and rename (spec section 16).
 *
 * <p>Names come from a text field a player types into, so every rule here is defending against
 * something a client can send: overlong input, padding, control characters, and the section sign
 * that would let a name inject colour or formatting into any screen that lists it.
 *
 * <p>Deliberately free of Minecraft imports so the rules can be unit tested without the game.
 */
public final class HomeName {
	public static final int MAX_LENGTH = 32;

	/** The character vanilla uses to introduce a formatting code. */
	private static final char FORMATTING_CHAR = '§';

	private HomeName() {
	}

	public sealed interface Result {
		/** Accepted, carrying the trimmed name exactly as it should be stored. */
		record Valid(String name) implements Result {
		}

		/** Rejected, carrying the reason to show the player. */
		record Invalid(String reason) implements Result {
		}
	}

	/**
	 * Validate a typed name.
	 *
	 * @param raw      exactly what the player typed
	 * @param existing the names this player already has; comparison is case-insensitive
	 */
	public static Result validate(String raw, Collection<String> existing) {
		if (raw == null) {
			return new Result.Invalid("Enter a name");
		}

		String name = raw.trim();

		if (name.isEmpty()) {
			return new Result.Invalid("Enter a name");
		}

		if (name.length() > MAX_LENGTH) {
			return new Result.Invalid("Names can be at most " + MAX_LENGTH + " characters");
		}

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);

			if (c == FORMATTING_CHAR) {
				return new Result.Invalid("Names cannot contain formatting codes");
			}

			if (Character.isISOControl(c)) {
				return new Result.Invalid("Names cannot contain control characters");
			}
		}

		if (isTaken(name, existing)) {
			return new Result.Invalid("You already have a home called " + name);
		}

		return new Result.Valid(name);
	}

	/**
	 * Whether this player already uses the name, ignoring case.
	 *
	 * <p>Case-insensitive so "workshop" and "Workshop" cannot both exist: they are indistinguishable
	 * when a player types one into {@code /home}.
	 */
	public static boolean isTaken(String name, Collection<String> existing) {
		String folded = fold(name);

		for (String other : existing) {
			if (fold(other).equals(folded)) {
				return true;
			}
		}

		return false;
	}

	/** The comparison form of a name. */
	public static String fold(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
