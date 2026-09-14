package com.brendan.deepgate.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ArrivalSearchTest {
	private final List<ArrivalSearch.Offset> oneLayer = ArrivalSearch.candidatesFor(1);
	private final List<ArrivalSearch.Offset> fourLayers = ArrivalSearch.candidatesFor(4);

	@Test
	void theRadiusGrowsWithThePyramid() {
		assertEquals(1, ArrivalSearch.radiusFor(1));
		assertEquals(2, ArrivalSearch.radiusFor(2));
		assertEquals(3, ArrivalSearch.radiusFor(3));
		assertEquals(4, ArrivalSearch.radiusFor(4));
	}

	@Test
	void anUnreadablePyramidStillGetsSomewhereToLand() {
		// Zero or nonsense means the smallest a home can be anchored to, not "nowhere".
		assertEquals(1, ArrivalSearch.radiusFor(0));
		assertEquals(1, ArrivalSearch.radiusFor(-3));
	}

	@Test
	void aBiggerBeaconOffersMoreSpots() {
		assertTrue(fourLayers.size() > oneLayer.size(),
				"a four layer beacon should search wider than a one layer beacon");
	}

	@Test
	void theBeamColumnIsNeverACandidate() {
		// Landing in the beam would reopen the home screen the moment the player arrived.
		for (ArrivalSearch.Offset offset : fourLayers) {
			assertFalse(offset.dx() == 0 && offset.dz() == 0,
					"the beam column must be excluded, found " + offset);
		}
	}

	@Test
	void everyCandidateIsInsideTheRadiusForItsBeacon() {
		for (int layers = 1; layers <= 4; layers++) {
			int limit = ArrivalSearch.radiusFor(layers) * ArrivalSearch.radiusFor(layers);

			for (ArrivalSearch.Offset offset : ArrivalSearch.candidatesFor(layers)) {
				assertTrue(offset.horizontalDistanceSquared() <= limit,
						offset + " is outside the radius for " + layers + " layers");
				assertTrue(Math.abs(offset.dy()) <= ArrivalSearch.VERTICAL_REACH,
						offset + " is outside the vertical reach");
			}
		}
	}

	@Test
	void aOneLayerBeaconLandsYouRightBesideTheBeam() {
		for (ArrivalSearch.Offset offset : oneLayer) {
			assertTrue(offset.horizontalDistanceSquared() <= 1,
					"a one layer beacon should only reach the four adjacent columns, found " + offset);
		}
	}

	@Test
	void theSearchIsARadiusNotASquare() {
		// A corner at (3,3) is 4.2 blocks away, so a four block radius excludes it.
		assertFalse(fourLayers.stream().anyMatch(o -> o.dx() == 3 && o.dz() == 3));
		assertTrue(fourLayers.stream().anyMatch(o -> o.dx() == 4 && o.dz() == 0));
	}

	@Test
	void nearerSpotsAreTriedFirst() {
		int previous = -1;

		for (ArrivalSearch.Offset offset : fourLayers) {
			assertTrue(offset.horizontalDistanceSquared() >= previous,
					"candidates must be ordered nearest first");
			previous = offset.horizontalDistanceSquared();
		}
	}

	@Test
	void theFirstCandidateIsDirectlyAdjacentAndLevel() {
		ArrivalSearch.Offset first = fourLayers.get(0);

		assertEquals(1, first.horizontalDistanceSquared(), "should start one block out");
		assertEquals(0, first.dy(), "should start level with the top of the beacon");
	}

	@Test
	void stepUpIsPreferredOverDroppingDown() {
		int up = fourLayers.indexOf(new ArrivalSearch.Offset(1, 1, 0));
		int down = fourLayers.indexOf(new ArrivalSearch.Offset(1, -1, 0));

		assertTrue(up >= 0 && down >= 0, "both candidates should exist");
		assertTrue(up < down, "a step up should be tried before an equal drop");
	}

	@Test
	void orderingIsDeterministic() {
		assertEquals(ArrivalSearch.candidatesFor(3), ArrivalSearch.candidatesFor(3));
	}

	@Test
	void everyCandidateIsDistinct() {
		Set<ArrivalSearch.Offset> seen = new HashSet<>(fourLayers);
		assertEquals(fourLayers.size(), seen.size(), "the search should not try the same spot twice");
	}
}
