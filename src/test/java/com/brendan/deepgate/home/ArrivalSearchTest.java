package com.brendan.deepgate.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ArrivalSearchTest {
	private final List<ArrivalSearch.Offset> candidates = ArrivalSearch.candidates();

	@Test
	void theBeamColumnIsNeverACandidate() {
		// Landing in the beam would reopen the home screen the moment the player arrived.
		for (ArrivalSearch.Offset offset : candidates) {
			assertFalse(offset.dx() == 0 && offset.dz() == 0,
					"the beam column must be excluded, found " + offset);
		}
	}

	@Test
	void everyCandidateIsInsideTheRadius() {
		int limit = ArrivalSearch.RADIUS * ArrivalSearch.RADIUS;

		for (ArrivalSearch.Offset offset : candidates) {
			assertTrue(offset.horizontalDistanceSquared() <= limit,
					offset + " is outside the radius");
			assertTrue(Math.abs(offset.dy()) <= ArrivalSearch.VERTICAL_REACH,
					offset + " is outside the vertical reach");
		}
	}

	@Test
	void theRadiusReachesThreeBlocksOut() {
		assertTrue(candidates.stream().anyMatch(o -> o.dx() == 3 && o.dz() == 0),
				"three blocks out should be reachable");
		assertFalse(candidates.stream().anyMatch(o -> Math.abs(o.dx()) > 3 || Math.abs(o.dz()) > 3),
				"nothing beyond three blocks should be considered");
		// A corner at (3,3) is further than three blocks away, so it is correctly excluded.
		assertFalse(candidates.stream().anyMatch(o -> o.dx() == 3 && o.dz() == 3),
				"the search is a radius, not a square");
	}

	@Test
	void nearerSpotsAreTriedFirst() {
		int previous = -1;

		for (ArrivalSearch.Offset offset : candidates) {
			assertTrue(offset.horizontalDistanceSquared() >= previous,
					"candidates must be ordered nearest first");
			previous = offset.horizontalDistanceSquared();
		}
	}

	@Test
	void theFirstCandidateIsDirectlyAdjacentAndLevel() {
		ArrivalSearch.Offset first = candidates.get(0);

		assertEquals(1, first.horizontalDistanceSquared(), "should start one block out");
		assertEquals(0, first.dy(), "should start level with the top of the beacon");
	}

	@Test
	void stepUpIsPreferredOverDroppingDown() {
		// At equal horizontal distance and equal vertical magnitude, up comes first: a step up is
		// safer than a drop into whatever is below.
		int up = indexOf(1, 1, 0);
		int down = indexOf(1, -1, 0);

		assertTrue(up < down, "a step up should be tried before an equal drop");
	}

	@Test
	void orderingIsDeterministic() {
		assertEquals(ArrivalSearch.candidates(), ArrivalSearch.candidates());
	}

	@Test
	void everyCandidateIsDistinct() {
		Set<ArrivalSearch.Offset> seen = new HashSet<>(candidates);
		assertEquals(candidates.size(), seen.size(), "the search should not try the same spot twice");
	}

	@Test
	void theSearchSpaceIsSmall() {
		// Bounded by construction: 28 columns within a radius of three, each at five heights.
		assertEquals(28 * 5, candidates.size());
	}

	private int indexOf(int dx, int dy, int dz) {
		int index = candidates.indexOf(new ArrivalSearch.Offset(dx, dy, dz));
		assertTrue(index >= 0, "expected candidate " + dx + "," + dy + "," + dz);
		return index;
	}
}
