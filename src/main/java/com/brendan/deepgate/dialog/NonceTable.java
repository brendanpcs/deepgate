package com.brendan.deepgate.dialog;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Single-use tokens that make a dialog button click provably the one the server offered
 * (spec sections 45 and 46).
 *
 * <p>Every committing button carries a nonce issued when its screen was built. The server consumes
 * the nonce before touching any state, so a duplicated packet - whether from a laggy client, a
 * double click, or a replay - performs the action exactly once.
 *
 * <p>A nonce is necessary but never sufficient. It proves the click is not a replay; it says
 * nothing about whether the world still looks the way it did when the screen was drawn, which is
 * why every handler re-validates ownership, position, combat and the rest regardless.
 *
 * <p>Memory-only, dropped on disconnect (section 48).
 */
public final class NonceTable {
	/** Long enough to survive a player reading a dialog, short enough to bound the table. */
	private static final long LIFETIME_TICKS = 20L * 300L;

	/** Hard ceiling per player, in case a client spams screen opens without ever clicking. */
	private static final int MAX_PER_PLAYER = 64;

	private final Map<UUID, Map<String, Long>> issued = new HashMap<>();

	/** Issue a nonce for a button the given player is about to be shown. */
	public String issue(UUID playerId, long currentTick) {
		Map<String, Long> forPlayer = issued.computeIfAbsent(playerId, id -> new HashMap<>());
		purge(forPlayer, currentTick);

		if (forPlayer.size() >= MAX_PER_PLAYER) {
			// Drop the oldest rather than refusing to draw a screen.
			forPlayer.entrySet().stream()
					.min(Map.Entry.comparingByValue())
					.map(Map.Entry::getKey)
					.ifPresent(forPlayer::remove);
		}

		String nonce = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36)
				+ Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
		forPlayer.put(nonce, currentTick + LIFETIME_TICKS);
		return nonce;
	}

	/**
	 * Validate and consume a nonce.
	 *
	 * @return true exactly once per issued nonce; false for a replay, a forgery or an expired token
	 */
	public boolean consume(UUID playerId, String nonce, long currentTick) {
		if (nonce == null || nonce.isEmpty()) {
			return false;
		}

		Map<String, Long> forPlayer = issued.get(playerId);

		if (forPlayer == null) {
			return false;
		}

		purge(forPlayer, currentTick);

		Long expiry = forPlayer.remove(nonce);
		return expiry != null && currentTick < expiry;
	}

	private static void purge(Map<String, Long> forPlayer, long currentTick) {
		Iterator<Map.Entry<String, Long>> iterator = forPlayer.entrySet().iterator();

		while (iterator.hasNext()) {
			if (currentTick >= iterator.next().getValue()) {
				iterator.remove();
			}
		}
	}

	public void clear(UUID playerId) {
		issued.remove(playerId);
	}

	public void clearAll() {
		issued.clear();
	}
}
