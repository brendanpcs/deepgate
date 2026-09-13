package com.brendan.deepgate.core;

import java.util.List;
import java.util.UUID;

/**
 * Which pending request a bare {@code /tpaccept} or {@code /tpadeny} refers to.
 *
 * <p>A recipient may hold several incoming requests at once (spec section 8), so a command with no
 * argument is only unambiguous when exactly one is waiting. Guessing would be the wrong kind of
 * convenience: accepting the wrong request teleports you somewhere you did not intend and charges
 * someone for it, so an ambiguous command refuses and asks for a name instead.
 *
 * <p>Deliberately free of Minecraft imports so the rule can be unit tested without the game.
 */
public final class RequestChoice {
	private RequestChoice() {
	}

	public enum Kind {
		/** Nothing is waiting on this player at all. */
		NONE_PENDING,
		/** Exactly one candidate matched; {@link Result#index()} points at it. */
		CHOSEN,
		/** Several are waiting and no sender was named. */
		AMBIGUOUS,
		/** A sender was named, but they have no request outstanding with this player. */
		NO_MATCH_FOR_SENDER
	}

	/**
	 * @param index position in the candidate list, or -1 when nothing was chosen
	 */
	public record Result(Kind kind, int index) {
		public boolean isChosen() {
			return kind == Kind.CHOSEN;
		}
	}

	/**
	 * Pick a request.
	 *
	 * @param senderIds    the sender of each pending request, in the order they arrived
	 * @param wantedSender the sender the player named, or null for a bare command
	 */
	public static Result choose(List<UUID> senderIds, UUID wantedSender) {
		if (senderIds.isEmpty()) {
			return new Result(Kind.NONE_PENDING, -1);
		}

		if (wantedSender != null) {
			int index = senderIds.indexOf(wantedSender);
			return index >= 0
					? new Result(Kind.CHOSEN, index)
					: new Result(Kind.NO_MATCH_FOR_SENDER, -1);
		}

		if (senderIds.size() > 1) {
			return new Result(Kind.AMBIGUOUS, -1);
		}

		return new Result(Kind.CHOSEN, 0);
	}
}
