package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class CostTest {
	@Test
	void parsesAnAmountAndAUnit() {
		assertEquals(new Cost(5, Cost.Unit.POINTS), Cost.parse("5 points").orElseThrow());
		assertEquals(new Cost(3, Cost.Unit.LEVELS), Cost.parse("3 levels").orElseThrow());
	}

	@Test
	void aBareNumberMeansPoints() {
		assertEquals(new Cost(5, Cost.Unit.POINTS), Cost.parse("5").orElseThrow());
	}

	@Test
	void surroundingAndRepeatedWhitespaceIsTolerated() {
		assertEquals(new Cost(5, Cost.Unit.POINTS), Cost.parse("  5   points  ").orElseThrow());
	}

	@Test
	void theUnitIsCaseInsensitiveAndAcceptsTheSingular() {
		assertEquals(Cost.Unit.POINTS, Cost.parse("5 POINTS").orElseThrow().unit());
		assertEquals(Cost.Unit.POINTS, Cost.parse("1 point").orElseThrow().unit());
		assertEquals(Cost.Unit.POINTS, Cost.parse("5 xp").orElseThrow().unit());
		assertEquals(Cost.Unit.LEVELS, Cost.parse("1 Level").orElseThrow().unit());
	}

	@Test
	void nonsenseIsRejected() {
		assertTrue(Cost.parse("").isEmpty());
		assertTrue(Cost.parse("   ").isEmpty());
		assertTrue(Cost.parse("points").isEmpty());
		assertTrue(Cost.parse("5 bananas").isEmpty());
		assertTrue(Cost.parse("5 points extra").isEmpty());
		assertTrue(Cost.parse("-1 points").isEmpty());
		assertTrue(Cost.parse(null).isEmpty());
	}

	@Test
	void roundTripsThroughItsOwnText() {
		for (Cost cost : new Cost[] {
				new Cost(0, Cost.Unit.POINTS),
				new Cost(5, Cost.Unit.POINTS),
				new Cost(3, Cost.Unit.LEVELS)}) {
			Optional<Cost> parsed = Cost.parse(cost.toString());
			assertEquals(cost, parsed.orElseThrow(), cost.toString());
		}
	}

	@Test
	void writtenTextIsWhatTheCommandTakes() {
		assertEquals("5 points", new Cost(5, Cost.Unit.POINTS).toString());
		assertEquals("3 levels", new Cost(3, Cost.Unit.LEVELS).toString());
	}

	@Test
	void scalingKeepsTheUnitAndRoundsUp() {
		Cost rate = new Cost(5, Cost.Unit.LEVELS);

		assertEquals(new Cost(10, Cost.Unit.LEVELS), rate.times(2.0D));
		// 5 * 1.1 = 5.5, rounded up: a part-charge is never rounded away.
		assertEquals(new Cost(6, Cost.Unit.LEVELS), rate.times(1.1D));
		assertEquals(new Cost(0, Cost.Unit.LEVELS), rate.times(0.0D));
	}

	@Test
	void zeroIsFree() {
		assertTrue(new Cost(0, Cost.Unit.LEVELS).isFree());
		assertTrue(Cost.FREE.isFree());
	}
}
