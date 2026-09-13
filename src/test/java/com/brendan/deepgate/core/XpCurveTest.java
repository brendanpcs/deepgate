package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class XpCurveTest {
	@Test
	void pointsToNextLevelMatchesVanillaBranches() {
		assertEquals(7, XpCurve.pointsToNextLevel(0));
		assertEquals(35, XpCurve.pointsToNextLevel(14));
		assertEquals(37, XpCurve.pointsToNextLevel(15));
		assertEquals(42, XpCurve.pointsToNextLevel(16));
		assertEquals(107, XpCurve.pointsToNextLevel(29));
		assertEquals(112, XpCurve.pointsToNextLevel(30));
		assertEquals(121, XpCurve.pointsToNextLevel(31));
	}

	@Test
	void totalAtLevelMatchesRunningSum() {
		int running = 0;

		for (int level = 0; level <= 200; level++) {
			assertEquals(running, XpCurve.totalAtLevel(level), "total at level " + level);
			running += XpCurve.pointsToNextLevel(level);
		}
	}

	@Test
	void knownTotals() {
		assertEquals(0, XpCurve.totalAtLevel(0));
		assertEquals(352, XpCurve.totalAtLevel(16));
		assertEquals(394, XpCurve.totalAtLevel(17));
		assertEquals(1507, XpCurve.totalAtLevel(31));
		assertEquals(1628, XpCurve.totalAtLevel(32));
	}

	@Test
	void levelForTotalInvertsTotalAtLevel() {
		for (int level = 0; level <= 200; level++) {
			int exact = XpCurve.totalAtLevel(level);
			assertEquals(level, XpCurve.levelForTotal(exact), "exact boundary at level " + level);

			int justBefore = exact - 1;

			if (justBefore >= 0) {
				assertEquals(Math.max(0, level - 1), XpCurve.levelForTotal(justBefore),
						"one point below level " + level);
			}
		}
	}

	@Test
	void totalPointsRoundTripsThroughLevelAndProgress() {
		for (int total = 0; total <= 5000; total++) {
			int level = XpCurve.levelForTotal(total);
			float progress = XpCurve.progressForTotal(total);
			assertEquals(total, XpCurve.totalPoints(level, progress), "round trip at total " + total);
		}
	}

	@Test
	void progressStaysInsideTheBar() {
		for (int total = 0; total <= 5000; total++) {
			float progress = XpCurve.progressForTotal(total);
			assertTrue(progress >= 0.0F && progress < 1.0F, "progress out of range at total " + total);
		}
	}

	@Test
	void pointsForLevelsCountsDownFromWhereThePlayerStands() {
		// Three levels off a level-33 player costs far more than off a level-3 player.
		int atThirtyThree = XpCurve.totalAtLevel(33);
		assertEquals(atThirtyThree - XpCurve.totalAtLevel(30), XpCurve.pointsForLevels(atThirtyThree, 3));

		int atThree = XpCurve.totalAtLevel(3);
		assertEquals(atThree, XpCurve.pointsForLevels(atThree, 3));

		assertTrue(XpCurve.pointsForLevels(atThirtyThree, 3) > XpCurve.pointsForLevels(atThree, 3));
	}

	@Test
	void pointsForLevelsNeverExceedsWhatThePlayerHas() {
		int held = XpCurve.totalAtLevel(2) + 5;
		assertEquals(held, XpCurve.pointsForLevels(held, 99));
		assertEquals(0, XpCurve.pointsForLevels(held, 0));
		assertEquals(0, XpCurve.pointsForLevels(held, -1));
	}

	@Test
	void partialBarIsChargedFromTheExactTotal() {
		// A player mid-bar at level 20 loses everything down to the level-18 boundary for 2 levels.
		int total = XpCurve.totalAtLevel(20) + 10;
		assertEquals(total - XpCurve.totalAtLevel(18), XpCurve.pointsForLevels(total, 2));
	}
}
