package com.brendan.deepgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class FailureTest {
	@Test
	void commandOrderPrefersFeatureUnavailableOverEverything() {
		List<Failure> failures = List.of(
				Failure.insufficientXp(new Fare(20, 0, 0, false), 20, 3),
				Failure.combat(6),
				Failure.of(Failure.Reason.FEATURE_UNAVAILABLE, "Homes are disabled"));

		Optional<Failure> primary = Failure.primary(failures, Failure.COMMAND_ORDER);

		assertEquals(Failure.Reason.FEATURE_UNAVAILABLE, primary.orElseThrow().reason());
	}

	@Test
	void combatOutranksInsufficientXp() {
		List<Failure> failures = List.of(
				Failure.insufficientXp(new Fare(20, 0, 0, false), 20, 3),
				Failure.combat(6));

		assertEquals(Failure.Reason.COMBAT, Failure.primary(failures, Failure.COMMAND_ORDER).orElseThrow().reason());
	}

	@Test
	void crossDimensionOutranksPhysicalDestinationAndCombat() {
		List<Failure> failures = List.of(
				Failure.combat(2),
				Failure.of(Failure.Reason.DESTINATION_INVALID, "Home is obstructed"),
				Failure.crossDimensionDisabled());

		assertEquals(Failure.Reason.CROSS_DIMENSION_DISABLED,
				Failure.primary(failures, Failure.COMMAND_ORDER).orElseThrow().reason());
	}

	@Test
	void fullCommandOrderIsRespectedPairwise() {
		for (int i = 0; i < Failure.COMMAND_ORDER.size(); i++) {
			for (int j = i + 1; j < Failure.COMMAND_ORDER.size(); j++) {
				Failure earlier = Failure.of(Failure.COMMAND_ORDER.get(i), "a");
				Failure later = Failure.of(Failure.COMMAND_ORDER.get(j), "b");

				assertEquals(earlier.reason(),
						Failure.primary(List.of(later, earlier), Failure.COMMAND_ORDER).orElseThrow().reason(),
						"index " + i + " should outrank " + j);
			}
		}
	}

	@Test
	void portalOrderPutsInvalidPortalFirstAndDestinationLast() {
		List<Failure> failures = List.of(
				Failure.of(Failure.Reason.DESTINATION_UNAVAILABLE, "gone"),
				Failure.combat(3),
				Failure.of(Failure.Reason.ACCESS_DENIED, "private"),
				Failure.of(Failure.Reason.PORTAL_INVALID, "broken"));

		assertEquals(Failure.Reason.PORTAL_INVALID,
				Failure.primary(failures, Failure.PORTAL_ORDER).orElseThrow().reason());

		List<Failure> tail = List.of(
				Failure.of(Failure.Reason.DESTINATION_UNAVAILABLE, "gone"),
				Failure.combat(3));

		assertEquals(Failure.Reason.COMBAT, Failure.primary(tail, Failure.PORTAL_ORDER).orElseThrow().reason());
	}

	@Test
	void unknownReasonForTheOrderSortsLastInsteadOfThrowing() {
		List<Failure> failures = List.of(
				Failure.of(Failure.Reason.PORTAL_INVALID, "portal reason in a command flow"),
				Failure.combat(1));

		assertEquals(Failure.Reason.COMBAT, Failure.primary(failures, Failure.COMMAND_ORDER).orElseThrow().reason());
	}

	@Test
	void emptyInputYieldsNoFailure() {
		assertTrue(Failure.primary(List.of(), Failure.COMMAND_ORDER).isEmpty());
	}

	@Test
	void messagesStateTheCorrectiveAction() {
		assertEquals("In combat: 6s remaining", Failure.combat(6).message());

		Failure shortOnXp = Failure.insufficientXp(new Fare(20, 0, 0, false), 20, 3);
		assertTrue(shortOnXp.message().contains("need 20 XP"), shortOnXp.message());
		assertTrue(shortOnXp.message().contains("short 17 XP"), shortOnXp.message());

		// A levels fare still quotes the shortfall in points, because that is what you go and earn.
		Failure shortOnLevels = Failure.insufficientXp(new Fare(0, 3, 0, false), 247, 100);
		assertTrue(shortOnLevels.message().contains("3 Levels"), shortOnLevels.message());
		assertTrue(shortOnLevels.message().contains("247 XP"), shortOnLevels.message());
		assertTrue(shortOnLevels.message().contains("short 147 XP"), shortOnLevels.message());
	}
}
