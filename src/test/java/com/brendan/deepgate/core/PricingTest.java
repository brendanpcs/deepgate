package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PricingTest {
	private static RuleSnapshot rules(int costPer1k, boolean inLevels, int crossDimension, int freeDistance) {
		return new RuleSnapshot(true, false, 10, true, 10, 1,
				costPer1k, inLevels, crossDimension, freeDistance, 15, 64);
	}

	private static final RuleSnapshot DEFAULTS = rules(5, false, 25, 1000);

	@Test
	void belowFreeDistanceCostsNothing() {
		assertEquals(0, Pricing.quote(999.9D, false, DEFAULTS).amount());
		assertTrue(Pricing.quote(0.0D, false, DEFAULTS).isFree());
	}

	@Test
	void freeDistanceBoundaryIsExclusiveBelowAndChargedAt() {
		// "distance below this is free" - 1000 itself is not below 1000.
		assertEquals(0, Pricing.distanceFare(999.999D, DEFAULTS));
		assertEquals(5, Pricing.distanceFare(1000.0D, DEFAULTS));
	}

	@Test
	void distanceFareRoundsUp() {
		assertEquals(5, Pricing.distanceFare(1000.0D, DEFAULTS));
		assertEquals(6, Pricing.distanceFare(1001.0D, DEFAULTS));
		assertEquals(10, Pricing.distanceFare(2000.0D, DEFAULTS));
		assertEquals(50, Pricing.distanceFare(10000.0D, DEFAULTS));
	}

	@Test
	void crossDimensionChargeIsAdditiveNotAMultiplier() {
		Fare same = Pricing.quote(2000.0D, false, DEFAULTS);
		Fare cross = Pricing.quote(2000.0D, true, DEFAULTS);

		assertEquals(10, same.amount());
		assertEquals(35, cross.amount());
		assertEquals(same.amount() + 25, cross.amount());
	}

	@Test
	void crossDimensionChargeAppliesEvenWhenDistanceIsFree() {
		Fare fare = Pricing.quote(10.0D, true, DEFAULTS);

		assertEquals(25, fare.amount());
		assertFalse(fare.isFree());
		assertTrue(fare.crossDimension());
	}

	@Test
	void zeroCostPerThousandDisablesTheDistanceComponentOnly() {
		RuleSnapshot free = rules(0, false, 25, 1000);

		assertEquals(0, Pricing.quote(50000.0D, false, free).amount());
		assertEquals(25, Pricing.quote(50000.0D, true, free).amount());
	}

	@Test
	void levelsModeLabelsTheFareWithoutChangingTheNumber() {
		Fare points = Pricing.quote(3000.0D, false, rules(5, false, 25, 1000));
		Fare levels = Pricing.quote(3000.0D, false, rules(5, true, 25, 1000));

		assertEquals(15, points.amount());
		assertEquals(15, levels.amount());
		assertFalse(points.inLevels());
		assertTrue(levels.inLevels());
	}

	@Test
	void pointsModeChargesTheFaceValue() {
		Fare fare = Pricing.quote(3000.0D, false, DEFAULTS);
		assertEquals(15, fare.pointsFor(XpCurve.totalAtLevel(40)));
	}

	@Test
	void levelsModeResolvesAgainstThePayersCurrentTotal() {
		Fare fare = Pricing.quote(600.0D, true, rules(5, true, 3, 1000));

		// Distance is free, so the fare is the flat cross-dimension charge of 3 levels.
		assertEquals(3, fare.amount());

		int rich = XpCurve.totalAtLevel(33);
		int poor = XpCurve.totalAtLevel(4);

		assertEquals(rich - XpCurve.totalAtLevel(30), fare.pointsFor(rich));
		assertEquals(poor - XpCurve.totalAtLevel(1), fare.pointsFor(poor));
	}

	@Test
	void freeFareCostsNoPointsRegardlessOfMode() {
		assertEquals(0, Fare.FREE.pointsFor(9999));
		assertEquals(0, Pricing.quote(10.0D, false, DEFAULTS).pointsFor(9999));
	}
}
