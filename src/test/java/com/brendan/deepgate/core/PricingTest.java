package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PricingTest {
	private static RuleSnapshot rules(int costPer1k, boolean inLevels, int crossDimension, int freeDistance) {
		Cost.Unit unit = inLevels ? Cost.Unit.LEVELS : Cost.Unit.POINTS;
		return new RuleSnapshot(true, false, 10, true, 10, 1,
				new Cost(costPer1k, unit), new Cost(crossDimension, Cost.Unit.POINTS), freeDistance, 15, 64);
	}

	private static final RuleSnapshot DEFAULTS = rules(5, false, 25, 1000);

	@Test
	void belowFreeDistanceCostsNothing() {
		assertEquals(0, Pricing.quote(999.9D, false, DEFAULTS).points());
		assertTrue(Pricing.quote(0.0D, false, DEFAULTS).isFree());
	}

	@Test
	void freeDistanceBoundaryIsExclusiveBelowAndChargedAt() {
		// "distance below this is free" - 1000 itself is not below 1000.
		assertEquals(0, Pricing.distanceCost(999.999D, DEFAULTS).amount());
		assertEquals(5, Pricing.distanceCost(1000.0D, DEFAULTS).amount());
	}

	@Test
	void distanceFareRoundsUp() {
		assertEquals(5, Pricing.distanceCost(1000.0D, DEFAULTS).amount());
		assertEquals(6, Pricing.distanceCost(1001.0D, DEFAULTS).amount());
		assertEquals(10, Pricing.distanceCost(2000.0D, DEFAULTS).amount());
		assertEquals(50, Pricing.distanceCost(10000.0D, DEFAULTS).amount());
	}

	@Test
	void crossDimensionChargeIsAdditiveNotAMultiplier() {
		Fare same = Pricing.quote(2000.0D, false, DEFAULTS);
		Fare cross = Pricing.quote(2000.0D, true, DEFAULTS);

		assertEquals(10, same.points());
		assertEquals(35, cross.points());
		assertEquals(same.points() + 25, cross.points());
	}

	@Test
	void crossDimensionChargeAppliesEvenWhenDistanceIsFree() {
		Fare fare = Pricing.quote(10.0D, true, DEFAULTS);

		assertEquals(25, fare.points());
		assertFalse(fare.isFree());
		assertTrue(fare.crossDimension());
	}

	@Test
	void zeroCostPerThousandDisablesTheDistanceComponentOnly() {
		RuleSnapshot free = rules(0, false, 25, 1000);

		assertEquals(0, Pricing.quote(50000.0D, false, free).points());
		assertEquals(25, Pricing.quote(50000.0D, true, free).points());
	}

	@Test
	void aRuleSetInLevelsProducesALevelComponent() {
		Fare points = Pricing.quote(3000.0D, false, rules(5, false, 25, 1000));
		Fare levels = Pricing.quote(3000.0D, false, rules(5, true, 25, 1000));

		assertEquals(15, points.points());
		assertEquals(0, points.levels());

		// The same number, counted in the unit its rule was set in.
		assertEquals(0, levels.points());
		assertEquals(15, levels.levels());
	}

	@Test
	void unitsFromDifferentRulesStayApart() {
		// Distance priced in levels, the dimensional surcharge in points.
		RuleSnapshot mixed = rules(5, true, 25, 1000);
		Fare fare = Pricing.quote(3000.0D, true, mixed);

		assertEquals(15, fare.levels());
		assertEquals(25, fare.points());
		assertEquals("XP 25 + 15 Levels", fare.describe());
	}

	@Test
	void aFareReadsAsTheUnitsItIsMadeOf() {
		assertEquals("free", Fare.FREE.describe());
		assertEquals("XP 15", new Fare(15, 0, 0, false).describe());
		assertEquals("3 Levels", new Fare(0, 3, 0, false).describe());
		assertEquals("1 Level", new Fare(0, 1, 0, false).describe());
	}

	@Test
	void pointsModeChargesTheFaceValue() {
		Fare fare = Pricing.quote(3000.0D, false, DEFAULTS);
		assertEquals(15, fare.pointsFor(XpCurve.totalAtLevel(40)));
	}

	@Test
	void aLevelFareResolvesAgainstThePayersCurrentTotal() {
		Fare fare = new Fare(0, 3, 600.0D, true);

		int rich = XpCurve.totalAtLevel(33);
		int poor = XpCurve.totalAtLevel(4);

		assertEquals(rich - XpCurve.totalAtLevel(30), fare.pointsFor(rich));
		assertEquals(poor - XpCurve.totalAtLevel(1), fare.pointsFor(poor));
	}

	@Test
	void aMixedFareTakesThePointsFirstThenCountsLevelsFromWhatIsLeft() {
		int held = XpCurve.totalAtLevel(30);
		Fare fare = new Fare(10, 2, 0.0D, false);

		int expected = 10 + XpCurve.pointsForLevels(held - 10, 2);
		assertEquals(expected, fare.pointsFor(held));
	}

	@Test
	void freeFareCostsNoPointsRegardlessOfMode() {
		assertEquals(0, Fare.FREE.pointsFor(9999));
		assertEquals(0, Pricing.quote(10.0D, false, DEFAULTS).pointsFor(9999));
	}
}
