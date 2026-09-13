package com.brendan.deepgate.core;

/**
 * Vanilla experience curve, expressed in exact integer arithmetic.
 *
 * <p>Deepgate charges and refunds experience in <em>points</em>, never by nudging levels, because
 * {@code /back} must refund exactly what a teleport removed (spec section 20). Levels-mode pricing
 * (section 6) is converted to a point count at charge time and that point count is what gets stored.
 *
 * <p>Deliberately free of Minecraft imports so it can be unit tested without the game.
 */
public final class XpCurve {
	private XpCurve() {
	}

	/** Points required to advance from {@code level} to {@code level + 1}. */
	public static int pointsToNextLevel(int level) {
		if (level >= 30) {
			return 112 + (level - 30) * 9;
		} else if (level >= 15) {
			return 37 + (level - 15) * 5;
		} else {
			return 7 + level * 2;
		}
	}

	/** Total points accumulated from zero to reach exactly {@code level}. */
	public static int totalAtLevel(int level) {
		if (level < 0) {
			throw new IllegalArgumentException("level must be >= 0, got " + level);
		}

		if (level <= 16) {
			return level * level + 6 * level;
		} else if (level <= 31) {
			return (5 * level * level - 81 * level + 720) / 2;
		} else {
			return (9 * level * level - 325 * level + 4440) / 2;
		}
	}

	/**
	 * Total points a player holding {@code level} levels plus {@code progress} of the current bar has.
	 *
	 * @param progress the vanilla 0..1 progress fraction towards the next level
	 */
	public static int totalPoints(int level, float progress) {
		int base = totalAtLevel(level);
		int barSize = pointsToNextLevel(level);
		// Vanilla renders progress as a float; round rather than truncate so a full bar reads as full.
		int inBar = Math.round(progress * barSize);
		// Guard against a float that rounds up past the bar and silently gifts a level.
		inBar = Math.max(0, Math.min(barSize - 1, inBar));
		return base + inBar;
	}

	/** The level a player with {@code totalPoints} sits at. */
	public static int levelForTotal(int totalPoints) {
		if (totalPoints < 0) {
			throw new IllegalArgumentException("totalPoints must be >= 0, got " + totalPoints);
		}

		int level = 0;

		// Quadratic growth; a bounded climb from an estimate is exact and avoids float edge cases.
		while (totalAtLevel(level + 1) <= totalPoints) {
			level++;
		}

		return level;
	}

	/** The 0..1 progress bar fraction for a player holding {@code totalPoints}. */
	public static float progressForTotal(int totalPoints) {
		int level = levelForTotal(totalPoints);
		int into = totalPoints - totalAtLevel(level);
		return (float) into / (float) pointsToNextLevel(level);
	}

	/**
	 * Points equivalent to {@code levels} whole levels for a player currently holding
	 * {@code currentTotalPoints}, counted downward from where they stand.
	 *
	 * <p>Section 6 says a levels-mode fare of "3 Levels" costs three levels. Those levels are not a
	 * fixed number of points: dropping from 33 to 30 costs far more than dropping from 3 to 0. The
	 * fare is therefore resolved against the payer's current total at quote and charge time.
	 */
	public static int pointsForLevels(int currentTotalPoints, int levels) {
		if (levels <= 0) {
			return 0;
		}

		int currentLevel = levelForTotal(currentTotalPoints);
		int targetLevel = Math.max(0, currentLevel - levels);
		int target = totalAtLevel(targetLevel);
		return currentTotalPoints - target;
	}
}
