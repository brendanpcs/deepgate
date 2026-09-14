package com.brendan.deepgate.home;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.brendan.deepgate.home.HomeService.Facing;

import org.junit.jupiter.api.Test;

class FacingTest {
	@Test
	void cardinalsSnapToThemselves() {
		// Minecraft yaw: 0 is south, 90 is west, 180 is north, 270 is east.
		assertEquals(Facing.S, Facing.snap(0.0F, 0.0F));
		assertEquals(Facing.W, Facing.snap(90.0F, 0.0F));
		assertEquals(Facing.N, Facing.snap(180.0F, 0.0F));
		assertEquals(Facing.E, Facing.snap(270.0F, 0.0F));
	}

	@Test
	void negativeYawIsHandledTheSameAsItsPositiveEquivalent() {
		assertEquals(Facing.E, Facing.snap(-90.0F, 0.0F));
		assertEquals(Facing.N, Facing.snap(-180.0F, 0.0F));
		assertEquals(Facing.NE, Facing.snap(-135.0F, 0.0F));
	}

	@Test
	void yawWrapsBeyondAFullTurn() {
		assertEquals(Facing.S, Facing.snap(360.0F, 0.0F));
		assertEquals(Facing.W, Facing.snap(450.0F, 0.0F));
		assertEquals(Facing.S, Facing.snap(-360.0F, 0.0F));
	}

	@Test
	void intercardinalsAreReachable() {
		assertEquals(Facing.SW, Facing.snap(45.0F, 0.0F));
		assertEquals(Facing.NW, Facing.snap(135.0F, 0.0F));
		assertEquals(Facing.NE, Facing.snap(225.0F, 0.0F));
		assertEquals(Facing.SE, Facing.snap(315.0F, 0.0F));
	}

	@Test
	void anglesRoundToTheNearestOctant() {
		assertEquals(Facing.S, Facing.snap(20.0F, 0.0F));
		assertEquals(Facing.SW, Facing.snap(25.0F, 0.0F));
		assertEquals(Facing.W, Facing.snap(88.0F, 0.0F));
	}

	@Test
	void steepPitchesBecomeUpOrDown() {
		assertEquals(Facing.UP, Facing.snap(0.0F, -90.0F));
		assertEquals(Facing.UP, Facing.snap(123.0F, -60.0F));
		assertEquals(Facing.DOWN, Facing.snap(0.0F, 90.0F));
		assertEquals(Facing.DOWN, Facing.snap(-45.0F, 60.0F));
	}

	@Test
	void shallowPitchesKeepTheirCompassDirection() {
		assertEquals(Facing.S, Facing.snap(0.0F, -59.0F));
		assertEquals(Facing.N, Facing.snap(180.0F, 59.0F));
	}

	@Test
	void everyFacingHasAConsistentPitch() {
		for (Facing facing : Facing.values()) {
			float expected = switch (facing) {
				case UP -> -90.0F;
				case DOWN -> 90.0F;
				default -> 0.0F;
			};

			assertEquals(expected, facing.pitch(), facing.name());
		}
	}

	@Test
	void snappedFacingsRoundTripThroughTheirOwnYaw() {
		for (Facing facing : Facing.values()) {
			if (facing == Facing.UP || facing == Facing.DOWN) {
				continue;
			}

			assertEquals(facing, Facing.snap(facing.yaw(), 0.0F), facing.name());
		}
	}
}
