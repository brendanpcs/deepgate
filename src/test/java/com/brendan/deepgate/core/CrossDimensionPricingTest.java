package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The cross-dimension cases from spec sections 4 and 5, spelled out with real coordinates.
 *
 * <p>Two rules interact here and are easy to conflate: distance is measured after converting the
 * source into the coordinate frame of the destination, and the dimensional surcharge is added on
 * top regardless of what that distance came to.
 */
class CrossDimensionPricingTest {
	private static final double OVERWORLD = 1.0D;
	private static final double NETHER = 8.0D;

	/** Defaults: 5 XP per 1000 blocks, 25 flat across dimensions, under 1000 blocks is free. */
	private static final RuleSnapshot RULES = new RuleSnapshot(
			true, false, 10, true, 10, 1,
			new Cost(5, Cost.Unit.POINTS), new Cost(25, Cost.Unit.POINTS), 1000, 15, 64);

	private static int fare(double sx, double sy, double sz, double sourceScale,
			double dx, double dy, double dz, double destScale, boolean crossDimension) {
		double distance = GeoMath.distance(sx, sy, sz, sourceScale, dx, dy, dz, destScale);
		return Pricing.quote(distance, crossDimension, RULES).points();
	}

	@Test
	void overworldToNetherIsPricedAsANetherToNetherTrip() {
		// Standing at overworld (0,64,0), going to nether (100,64,0).
		// The source converts to nether coordinates (0,64,0), so the gap is 100 nether blocks.
		double distance = GeoMath.distance(0, 64, 0, OVERWORLD, 100, 64, 0, NETHER);
		assertEquals(100.0D, distance, 1.0e-9D);

		// Under the free distance, so the whole fare is the dimensional surcharge.
		assertEquals(25, fare(0, 64, 0, OVERWORLD, 100, 64, 0, NETHER, true));
	}

	@Test
	void netherToOverworldIsPricedAsAnOverworldToOverworldTrip() {
		// Standing at nether (100,64,0), going to overworld (800,64,0).
		// The source converts to overworld coordinates (800,64,0): the same place, so zero distance.
		double distance = GeoMath.distance(100, 64, 0, NETHER, 800, 64, 0, OVERWORLD);
		assertEquals(0.0D, distance, 1.0e-9D);

		assertEquals(25, fare(100, 64, 0, NETHER, 800, 64, 0, OVERWORLD, true));
	}

	@Test
	void theSurchargeIsAddedOnTopOfARealDistanceCharge() {
		// Overworld (0,64,0) to nether (2000,64,0): 2000 nether blocks apart.
		assertEquals(2000.0D, GeoMath.distance(0, 64, 0, OVERWORLD, 2000, 64, 0, NETHER), 1.0e-9D);

		// ceil(2000 / 1000 * 5) = 10, plus the flat 25.
		assertEquals(35, fare(0, 64, 0, OVERWORLD, 2000, 64, 0, NETHER, true));
	}

	@Test
	void theSurchargeAppliesEvenWhenStandingOnTheDestination() {
		// Zero distance across dimensions still costs the surcharge and nothing else.
		assertEquals(25, fare(0, 64, 0, OVERWORLD, 0, 64, 0, NETHER, true));
	}

	@Test
	void theSameDistanceWithinOneDimensionCostsNoSurcharge() {
		assertEquals(10, fare(0, 64, 0, OVERWORLD, 2000, 64, 0, OVERWORLD, false));
		assertEquals(0, fare(0, 64, 0, OVERWORLD, 100, 64, 0, OVERWORLD, false));
	}

	@Test
	void heightIsNeverScaledSoADeepNetherTripStillCosts() {
		// Same horizontal spot once converted, but 200 blocks of height between them.
		double distance = GeoMath.distance(800, 250, 0, OVERWORLD, 100, 50, 0, NETHER);
		assertEquals(200.0D, distance, 1.0e-9D);
		assertEquals(25, fare(800, 250, 0, OVERWORLD, 100, 50, 0, NETHER, true));
	}

	@Test
	void aCustomDimensionUsesItsOwnScaleRatherThanTheNetherRatio() {
		// A 3:1 dimension converts by three, not by eight.
		assertEquals(300.0D, GeoMath.convertHorizontal(100.0D, 3.0D, 1.0D), 1.0e-9D);
		assertEquals(25.0D, GeoMath.convertHorizontal(100.0D, 1.0D, 4.0D), 1.0e-9D);
	}
}
