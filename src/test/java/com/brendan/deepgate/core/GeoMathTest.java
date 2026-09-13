package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeoMathTest {
	private static final double EPSILON = 1.0e-9D;

	@Test
	void sameDimensionIsPlainEuclideanDistance() {
		double distance = GeoMath.distance(0, 0, 0, 1.0D, 3, 4, 0, 1.0D);
		assertEquals(5.0D, distance, EPSILON);
	}

	@Test
	void overworldToNetherScalesHorizontallyOnly() {
		// 800 blocks out in the overworld is 100 blocks out in the Nether frame.
		double distance = GeoMath.distance(800, 64, 0, 1.0D, 100, 64, 0, 8.0D);
		assertEquals(0.0D, distance, EPSILON);
	}

	@Test
	void heightIsNeverScaled() {
		double distance = GeoMath.distance(800, 200, 0, 1.0D, 100, 100, 0, 8.0D);
		assertEquals(100.0D, distance, EPSILON);
	}

	@Test
	void netherToOverworldScalesTheOtherWay() {
		double distance = GeoMath.distance(100, 64, 0, 8.0D, 800, 64, 0, 1.0D);
		assertEquals(0.0D, distance, EPSILON);
	}

	@Test
	void convertHorizontalUsesTheRatioNotAHardcodedEight() {
		// A custom dimension at 3:1 must be honoured, not silently treated as Nether.
		assertEquals(300.0D, GeoMath.convertHorizontal(100.0D, 3.0D, 1.0D), EPSILON);
		assertEquals(25.0D, GeoMath.convertHorizontal(100.0D, 1.0D, 4.0D), EPSILON);
	}

	@Test
	void distanceIsSymmetricOnceBothSidesAreInTheSameFrame() {
		double a = GeoMath.distance(1000, 70, -2000, 1.0D, 0, 70, 0, 1.0D);
		double b = GeoMath.distance(0, 70, 0, 1.0D, 1000, 70, -2000, 1.0D);
		assertEquals(a, b, EPSILON);
		assertTrue(a > 2200.0D && a < 2250.0D);
	}
}
